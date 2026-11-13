//! Spotify's web GraphQL API ("pathfinder"), used only for data no spclient endpoint carries:
//! play counts of an artist's top tracks, album/playlist search (context-resolve search is tracks
//! only) and the personal Wrapped "Your Top Songs <year>" playlists (not in the rootlist unless saved).
//!
//! Queries are persisted: the client sends an operation name plus the sha256 of the query text.
//! Spotify changes those hashes when it redeploys the web player, so when the server rejects a
//! hash the current one is scraped from the web player bundle and the request is retried once.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use bytes::Bytes;
use http::{Method, Request, header};
use http_body_util::{BodyExt, Full};
use hyper_util::client::legacy::Client;
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::rt::TokioExecutor;
use librespot_core::Session;
use serde_json::{Value, json};

use crate::CoreError;
use crate::library::{AlbumInfo, ArtistRef, PlaylistInfo};

const ENDPOINT: &str = "https://api-partner.spotify.com/pathfinder/v2/query";
const WEB_PLAYER: &str = "https://open.spotify.com/";
/// A desktop browser UA: other agents get the mobile web player, whose bundles lack the hashes.
const BROWSER_UA: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

/// Hashes known to work (scraped 2026-10-30). Replaced at runtime when they go stale.
const KNOWN_HASHES: &[(&str, &str)] = &[
    ("queryArtistOverview", "9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6"),
    ("searchDesktop", "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03"),
    ("searchPlaylists", "d520014e748f9ea44f7707d8df1819867ac1205e8b7f3e28f22fe5fc858921b1"),
];

type HttpsClient = Client<hyper_rustls::HttpsConnector<HttpConnector>, Full<Bytes>>;

fn client() -> &'static HttpsClient {
    static CLIENT: OnceLock<HttpsClient> = OnceLock::new();
    CLIENT.get_or_init(|| {
        let tls = hyper_rustls::HttpsConnectorBuilder::new()
            .with_webpki_roots()
            .https_only()
            .enable_http1()
            .enable_http2()
            .build();
        Client::builder(TokioExecutor::new()).build(tls)
    })
}

fn hashes() -> &'static Mutex<HashMap<String, String>> {
    static HASHES: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    HASHES.get_or_init(|| {
        Mutex::new(
            KNOWN_HASHES
                .iter()
                .map(|(op, h)| (op.to_string(), h.to_string()))
                .collect(),
        )
    })
}

pub(crate) async fn send(req: Request<Full<Bytes>>) -> Result<(http::StatusCode, Bytes), CoreError> {
    let res = client()
        .request(req)
        .await
        .map_err(|e| CoreError::Network { msg: e.to_string() })?;
    let status = res.status();
    let body = res
        .into_body()
        .collect()
        .await
        .map_err(|e| CoreError::Network { msg: e.to_string() })?
        .to_bytes();
    Ok((status, body))
}

async fn get_text(url: &str) -> Result<String, CoreError> {
    let req = Request::get(url)
        .header(header::USER_AGENT, BROWSER_UA)
        .body(Full::new(Bytes::new()))
        .map_err(CoreError::other)?;
    let (status, body) = send(req).await?;
    if !status.is_success() {
        return Err(CoreError::other(format!("GET {url}: HTTP {status}")));
    }
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// Finds `"<op>","query","<hash>"` in the web player's scripts: the entry bundles first, then the
/// lazily loaded chunks (search queries live in `xpui-routes-search`), named ones first.
async fn scrape_hash(op: &str) -> Result<String, CoreError> {
    let html = get_text(WEB_PLAYER).await?;
    let needle = format!("\"{op}\",\"query\",\"");
    let find = |js: &str| {
        let rest = js.split(&needle).nth(1)?;
        let hash: String = rest.chars().take_while(char::is_ascii_hexdigit).collect();
        (hash.len() == 64).then_some(hash)
    };
    let scripts: Vec<String> = html
        .split("src=\"")
        .skip(1)
        .filter_map(|s| s.split('"').next())
        .filter(|u| u.starts_with("https://") && u.ends_with(".js") && u.contains("web-player"))
        .map(str::to_owned)
        .collect();
    let mut chunks = Vec::new();
    for url in &scripts {
        let js = get_text(url).await?;
        if let Some(hash) = find(&js) {
            log::info!("pathfinder: {op} hash refreshed from {url}");
            return Ok(hash);
        }
        if let Some(base) = url.rsplit_once('/').map(|(b, _)| b) {
            chunks.extend(chunk_urls(&js).into_iter().map(|c| format!("{base}/{c}")));
        }
    }
    for url in chunks {
        // A chunk that fails to load can't hold the hash; keep looking.
        let Ok(js) = get_text(&url).await else { continue };
        if let Some(hash) = find(&js) {
            log::info!("pathfinder: {op} hash refreshed from {url}");
            return Ok(hash);
        }
    }
    Err(CoreError::other(format!(
        "pathfinder: no hash for {op} in the web player"
    )))
}

/// Lazy chunk file names from the bundler's url function:
/// `.u=e=>""+(({<id>:"<name>",...})[e]||e)+"."+({<id>:"<hash>",...})[e]+".js"`. Named chunks first.
fn chunk_urls(js: &str) -> Vec<String> {
    const START: &str = ".u=e=>\"\"+(({";
    const MID: &str = "})[e]||e)+\".\"+({";
    const END: &str = "})[e]+\".js\"";
    let Some(rest) = js.split(START).nth(1) else { return Vec::new() };
    let Some((names, rest)) = rest.split_once(MID) else { return Vec::new() };
    let Some((hashes, _)) = rest.split_once(END) else { return Vec::new() };
    let pairs = |s: &str| -> Vec<(String, String)> {
        s.split(',')
            .filter_map(|kv| {
                let (k, v) = kv.split_once(':')?;
                Some((k.trim_matches('"').to_owned(), v.trim_matches('"').to_owned()))
            })
            .collect()
    };
    let names: HashMap<String, String> = pairs(names).into_iter().collect();
    let mut urls: Vec<(bool, String)> = pairs(hashes)
        .into_iter()
        .map(|(id, hash)| match names.get(&id) {
            Some(name) => (false, format!("{name}.{hash}.js")),
            None => (true, format!("{id}.{hash}.js")),
        })
        .collect();
    urls.sort_by_key(|(unnamed, _)| *unnamed);
    urls.into_iter().map(|(_, u)| u).collect()
}

/// Runs a persisted query and returns its `data` object.
pub async fn query(session: &Session, op: &str, variables: Value) -> Result<Value, CoreError> {
    let known = hashes().lock().unwrap().get(op).cloned();
    let hash = match known {
        Some(h) => h,
        None => scrape_hash(op).await?,
    };
    match query_with(session, op, &hash, &variables).await {
        Err(Stale(msg)) => {
            log::warn!("pathfinder: {op} hash rejected ({msg}), scraping the current one");
            let fresh = scrape_hash(op).await?;
            hashes()
                .lock()
                .unwrap()
                .insert(op.to_owned(), fresh.clone());
            query_with(session, op, &fresh, &variables)
                .await
                .map_err(|Stale(msg)| CoreError::other(format!("pathfinder {op}: {msg}")))?
        }
        Ok(r) => r,
    }
}

/// The server didn't accept the hash (or failed in a way a fresh hash might fix).
struct Stale(String);

async fn query_with(
    session: &Session,
    op: &str,
    hash: &str,
    variables: &Value,
) -> Result<Result<Value, CoreError>, Stale> {
    let body = json!({
        "variables": variables,
        "operationName": op,
        "extensions": { "persistedQuery": { "version": 1, "sha256Hash": hash } },
    });
    let token = match session.login5().auth_token().await {
        Ok(t) => t.access_token,
        Err(e) => return Ok(Err(e.into())),
    };
    let client_token = match session.spclient().client_token().await {
        Ok(t) => t,
        Err(e) => return Ok(Err(e.into())),
    };
    let req = Request::builder()
        .method(Method::POST)
        .uri(ENDPOINT)
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header("client-token", client_token)
        .header("app-platform", "WebPlayer")
        .header(header::CONTENT_TYPE, "application/json;charset=UTF-8")
        .header(header::ACCEPT, "application/json")
        .header(header::USER_AGENT, BROWSER_UA)
        .body(Full::new(Bytes::from(body.to_string())));
    let req = match req {
        Ok(r) => r,
        Err(e) => return Ok(Err(CoreError::other(e))),
    };
    let (status, bytes) = match send(req).await {
        Ok(r) => r,
        Err(e) => return Ok(Err(e)),
    };
    let text = String::from_utf8_lossy(&bytes);
    if text.contains("PersistedQueryNotFound") || status == http::StatusCode::BAD_REQUEST {
        return Err(Stale(format!(
            "HTTP {status}: {}",
            text.chars().take(200).collect::<String>()
        )));
    }
    if !status.is_success() {
        return Ok(Err(CoreError::other(format!(
            "pathfinder {op}: HTTP {status}: {}",
            text.chars().take(200).collect::<String>()
        ))));
    }
    let mut v: Value = match serde_json::from_slice(&bytes) {
        Ok(v) => v,
        Err(e) => return Ok(Err(CoreError::other(e))),
    };
    match v.get_mut("data").map(Value::take) {
        Some(data) if !data.is_null() => Ok(Ok(data)),
        _ => Ok(Err(CoreError::other(format!(
            "pathfinder {op}: no data: {}",
            text.chars().take(200).collect::<String>()
        )))),
    }
}

/// Play counts of an artist's top tracks, keyed by base62 track id.
pub async fn artist_playcounts(
    session: &Session,
    artist_id: &str,
) -> Result<HashMap<String, u64>, CoreError> {
    let data = query(
        session,
        "queryArtistOverview",
        json!({ "uri": format!("spotify:artist:{artist_id}"), "locale": "" }),
    )
    .await?;
    let items = data
        .pointer("/artistUnion/discography/topTracks/items")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let counts: HashMap<String, u64> = items
        .iter()
        .filter_map(|i| {
            let t = i.get("track")?;
            let id = t
                .get("uri")?
                .as_str()?
                .strip_prefix("spotify:track:")?
                .to_owned();
            // Spotify sends the count as a string.
            let count = match t.get("playcount")? {
                Value::String(s) => s.parse().ok()?,
                Value::Number(n) => n.as_u64()?,
                _ => return None,
            };
            Some((id, count))
        })
        .collect();
    log::info!("artist {artist_id}: {} play counts", counts.len());
    Ok(counts)
}

/// Albums + playlists for a search query (`searchDesktop`, like the web player's search page).
pub async fn search_catalog(
    session: &Session,
    query: &str,
    limit: u32,
) -> Result<(Vec<AlbumInfo>, Vec<PlaylistInfo>), CoreError> {
    let data = search_desktop(session, query, limit).await?;
    let items = |path: &str| {
        data.pointer(path)
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(|i| i.get("data")).collect::<Vec<_>>())
            .unwrap_or_default()
    };
    let albums: Vec<AlbumInfo> = items("/searchV2/albumsV2/items").into_iter().filter_map(album).collect();
    let playlists: Vec<PlaylistInfo> = items("/searchV2/playlists/items").into_iter().filter_map(|p| playlist(p).map(|(p, _)| p)).collect();
    log::info!("search '{query}': {} albums, {} playlists", albums.len(), playlists.len());
    Ok((albums, playlists))
}

/// The user's Wrapped "Your Top Songs <year>" playlists, newest first. Spotify only finds them by
/// search; they're recognised by owner `spotify` + a `wrapped-<year>-...` format.
pub async fn top_songs_playlists(session: &Session) -> Result<Vec<PlaylistInfo>, CoreError> {
    let data = search_desktop(session, "Your Top Songs", 50).await?;
    let mut found: Vec<(i32, PlaylistInfo)> = data
        .pointer("/searchV2/playlists/items")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|i| i.get("data")).filter_map(playlist).collect::<Vec<_>>())
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(p, format)| {
            let year = format.strip_prefix("wrapped-")?.split('-').next()?.parse().ok()?;
            (p.owner == "spotify").then_some((year, p))
        })
        .collect();
    found.sort_by(|a, b| b.0.cmp(&a.0));
    found.dedup_by(|a, b| a.1.id == b.1.id);
    log::info!("top songs playlists: {:?}", found.iter().map(|(y, _)| y).collect::<Vec<_>>());
    Ok(found.into_iter().map(|(_, p)| p).collect())
}

async fn search_desktop(session: &Session, term: &str, limit: u32) -> Result<Value, CoreError> {
    query(
        session,
        "searchDesktop",
        json!({
            "searchTerm": term,
            "offset": 0,
            "limit": limit,
            "numberOfTopResults": 5,
            "includeAudiobooks": false,
            "includeArtistHasConcertsField": false,
            "includePreReleases": false,
            "includeLocalConcertsField": false,
            "includeAuthors": false,
        }),
    )
    .await
}

fn largest_source(sources: Option<&Value>) -> Option<String> {
    sources?
        .as_array()?
        .iter()
        .max_by_key(|s| s.get("width").and_then(Value::as_u64).unwrap_or(0))?
        .get("url")?
        .as_str()
        .map(str::to_owned)
}

fn album(a: &Value) -> Option<AlbumInfo> {
    let id = a.get("uri")?.as_str()?.strip_prefix("spotify:album:")?.to_owned();
    let artist_refs: Vec<ArtistRef> = a
        .pointer("/artists/items")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(|x| {
                    Some(ArtistRef {
                        id: x.get("uri")?.as_str()?.strip_prefix("spotify:artist:")?.to_owned(),
                        name: x.pointer("/profile/name")?.as_str()?.to_owned(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    Some(AlbumInfo {
        id,
        name: a.get("name")?.as_str()?.to_owned(),
        artists: artist_refs.iter().map(|r| r.name.as_str()).collect::<Vec<_>>().join(", "),
        image_url: largest_source(a.pointer("/coverArt/sources")),
        artist_refs,
        year: a.pointer("/date/year").and_then(Value::as_i64).map(|y| y as i32),
        album_type: a.get("type").and_then(Value::as_str).unwrap_or("").to_lowercase(),
        release_date: None,
    })
}

/// Playlist + its `format` ("wrapped-2025-top100", usually empty).
fn playlist(p: &Value) -> Option<(PlaylistInfo, String)> {
    let id = p.get("uri")?.as_str()?.strip_prefix("spotify:playlist:")?.to_owned();
    let info = PlaylistInfo {
        id,
        name: p.get("name")?.as_str()?.to_owned(),
        owner: p.pointer("/ownerV2/data/username").and_then(Value::as_str).unwrap_or("").to_owned(),
        owner_name: p.pointer("/ownerV2/data/name").and_then(Value::as_str).unwrap_or("").to_owned(),
        image_url: largest_source(p.pointer("/images/items/0/sources")),
        length: 0,
    };
    Some((info, p.get("format").and_then(Value::as_str).unwrap_or("").to_owned()))
}
