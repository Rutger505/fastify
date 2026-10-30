//! Spotify's web GraphQL API ("pathfinder"), used only for data no spclient endpoint carries:
//! play counts of an artist's top tracks.
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

const ENDPOINT: &str = "https://api-partner.spotify.com/pathfinder/v2/query";
const WEB_PLAYER: &str = "https://open.spotify.com/";
/// A desktop browser UA: other agents get the mobile web player, whose bundles lack the hashes.
const BROWSER_UA: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

/// Hashes known to work (scraped 2026-10-30). Replaced at runtime when they go stale.
const KNOWN_HASHES: &[(&str, &str)] = &[(
    "queryArtistOverview",
    "9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6",
)];

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

async fn send(req: Request<Full<Bytes>>) -> Result<(http::StatusCode, Bytes), CoreError> {
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

/// Finds `"<op>","query","<hash>"` in the web player's scripts.
async fn scrape_hash(op: &str) -> Result<String, CoreError> {
    let html = get_text(WEB_PLAYER).await?;
    let needle = format!("\"{op}\",\"query\",\"");
    let scripts = html
        .split("src=\"")
        .skip(1)
        .filter_map(|s| s.split('"').next())
        .filter(|u| u.starts_with("https://") && u.ends_with(".js") && u.contains("web-player"));
    for url in scripts {
        let js = get_text(url).await?;
        if let Some(rest) = js.split(&needle).nth(1) {
            let hash: String = rest.chars().take_while(char::is_ascii_hexdigit).collect();
            if hash.len() == 64 {
                log::info!("pathfinder: {op} hash refreshed from {url}");
                return Ok(hash);
            }
        }
    }
    Err(CoreError::other(format!(
        "pathfinder: no hash for {op} in the web player"
    )))
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
