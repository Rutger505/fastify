//! Library, playlists and search through Spotify's internal APIs (spclient) on the librespot
//! session. The public Web API answers 429 for tokens of the desktop client id, so nothing here
//! touches api.spotify.com.

use std::collections::HashMap;

use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::{Session, SpotifyId};
use librespot_protocol::extended_metadata::{BatchedEntityRequest, EntityRequest, ExtensionQuery};
use librespot_protocol::extension_kind::ExtensionKind;
use librespot_protocol::metadata;
use librespot_protocol::playlist4_external::SelectedListContent;
use protobuf::{EnumOrUnknown, Message};

use crate::CoreError;

#[derive(uniffi::Record, Clone)]
pub struct TrackInfo {
    pub id: String,
    pub title: String,
    pub artists: String,
    pub album: String,
    pub image_url: Option<String>,
    pub duration_ms: u64,
}

#[derive(uniffi::Record, Clone)]
pub struct PlaylistInfo {
    pub id: String,
    pub name: String,
    pub owner: String,
    pub image_url: Option<String>,
    pub length: u32,
}

#[derive(uniffi::Record, Clone)]
pub struct AlbumInfo {
    pub id: String,
    pub name: String,
    pub artists: String,
    pub image_url: Option<String>,
}

const IMAGE_BASE: &str = "https://i.scdn.co/image/";
const METADATA_BATCH: usize = 100;
const ROOTLIST_PAGE: usize = 500;

fn image_url(file_id: &[u8]) -> Option<String> {
    (!file_id.is_empty()).then(|| format!("{IMAGE_BASE}{}", hex::encode(file_id)))
}

/// Prefer LARGE (640px), then the widest image.
fn best_image(images: &[metadata::Image]) -> Option<String> {
    let large = images
        .iter()
        .find(|i| i.size.map(|s| s.value()) == Some(metadata::image::Size::LARGE as i32));
    large
        .or_else(|| images.iter().max_by_key(|i| i.width.unwrap_or(0)))
        .and_then(|i| image_url(i.file_id()))
}

fn album_image(album: &metadata::Album) -> Option<String> {
    best_image(&album.cover_group.image).or_else(|| best_image(&album.cover))
}

fn artist_names(artists: &[metadata::Artist]) -> String {
    artists.iter().map(|a| a.name()).collect::<Vec<_>>().join(", ")
}

fn base62(gid: &[u8]) -> Option<String> {
    SpotifyId::from_raw(gid).ok()?.to_base62().ok()
}

fn id_from_uri<'a>(uri: &'a str, kind: &str) -> Option<&'a str> {
    let rest = uri.strip_prefix("spotify:")?.strip_prefix(kind)?.strip_prefix(':')?;
    (!rest.is_empty() && rest.chars().all(|c| c.is_ascii_alphanumeric())).then_some(rest)
}

fn parse_id(id: &str) -> Result<SpotifyId, CoreError> {
    SpotifyId::from_base62(id).map_err(|_| CoreError::NotFound { msg: format!("invalid id {id}") })
}

/// Batched extended metadata; returns raw protobuf payloads keyed by entity uri.
async fn extended(
    session: &Session,
    uris: &[String],
    kind: ExtensionKind,
) -> Result<HashMap<String, Vec<u8>>, CoreError> {
    let mut out = HashMap::new();
    for chunk in uris.chunks(METADATA_BATCH) {
        let req = BatchedEntityRequest {
            entity_request: chunk
                .iter()
                .map(|uri| EntityRequest {
                    entity_uri: uri.clone(),
                    query: vec![ExtensionQuery {
                        extension_kind: EnumOrUnknown::new(kind),
                        ..Default::default()
                    }],
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        let res = session.spclient().get_extended_metadata(req).await?;
        for array in res.extended_metadata {
            for data in array.extension_data {
                if let Some(any) = data.extension_data.into_option() {
                    if !any.value.is_empty() {
                        out.insert(data.entity_uri, any.value);
                    }
                }
            }
        }
    }
    Ok(out)
}

/// Track ids -> rows, in the given order. Unknown/unavailable tracks are dropped.
pub async fn hydrate_tracks(session: &Session, ids: &[String]) -> Result<Vec<TrackInfo>, CoreError> {
    let uris: Vec<String> = ids.iter().map(|id| format!("spotify:track:{id}")).collect();
    let data = extended(session, &uris, ExtensionKind::TRACK_V4).await?;
    let mut out = Vec::with_capacity(ids.len());
    for (id, uri) in ids.iter().zip(&uris) {
        let Some(bytes) = data.get(uri) else { continue };
        let Ok(t) = metadata::Track::parse_from_bytes(bytes) else { continue };
        out.push(TrackInfo {
            id: id.clone(),
            title: t.name().to_owned(),
            artists: artist_names(&t.artist),
            album: t.album.name().to_owned(),
            image_url: album_image(&t.album),
            duration_ms: t.duration().max(0) as u64,
        });
    }
    log::info!("hydrated {}/{} tracks", out.len(), ids.len());
    Ok(out)
}

async fn hydrate_albums(session: &Session, ids: &[String]) -> Result<Vec<AlbumInfo>, CoreError> {
    let uris: Vec<String> = ids.iter().map(|id| format!("spotify:album:{id}")).collect();
    let data = extended(session, &uris, ExtensionKind::ALBUM_V4).await?;
    Ok(ids
        .iter()
        .zip(&uris)
        .filter_map(|(id, uri)| {
            let a = metadata::Album::parse_from_bytes(data.get(uri)?).ok()?;
            Some(AlbumInfo {
                id: id.clone(),
                name: a.name().to_owned(),
                artists: artist_names(&a.artist),
                image_url: album_image(&a),
            })
        })
        .collect())
}

fn playlist_image(attrs: &librespot_protocol::playlist4_external::ListAttributes) -> Option<String> {
    let sized = |name: &str| {
        attrs
            .picture_size
            .iter()
            .find(|p| p.target_name() == name)
            .map(|p| p.url().to_owned())
            .filter(|u| !u.is_empty())
    };
    sized("default")
        .or_else(|| sized("large"))
        .or_else(|| attrs.picture_size.iter().map(|p| p.url().to_owned()).find(|u| !u.is_empty()))
        .or_else(|| image_url(attrs.picture()))
}

pub async fn rootlist(session: &Session) -> Result<Vec<PlaylistInfo>, CoreError> {
    let mut out = Vec::new();
    let mut from = 0;
    loop {
        let bytes = session.spclient().get_rootlist(from, Some(ROOTLIST_PAGE)).await?;
        let list = SelectedListContent::parse_from_bytes(&bytes).map_err(CoreError::other)?;
        let contents = list.contents.get_or_default();
        let n = contents.items.len();
        for (i, item) in contents.items.iter().enumerate() {
            // Folders show up as spotify:start-group / end-group markers: flatten them.
            let Some(id) = id_from_uri(item.uri(), "playlist") else { continue };
            let meta = contents.meta_items.get(i);
            let attrs = meta.and_then(|m| m.attributes.as_ref());
            out.push(PlaylistInfo {
                id: id.to_owned(),
                name: attrs.map(|a| a.name().to_owned()).unwrap_or_default(),
                owner: meta.map(|m| m.owner_username().to_owned()).unwrap_or_default(),
                image_url: attrs.and_then(playlist_image),
                length: meta.map(|m| m.length().max(0) as u32).unwrap_or(0),
            });
        }
        if !contents.truncated() || n == 0 {
            break;
        }
        from += n;
    }
    log::info!("rootlist: {} playlists", out.len());
    Ok(out)
}

pub async fn playlist_tracks(session: &Session, id: &str) -> Result<Vec<TrackInfo>, CoreError> {
    let bytes = session.spclient().get_playlist(&parse_id(id)?).await?;
    let list = SelectedListContent::parse_from_bytes(&bytes).map_err(CoreError::other)?;
    let ids: Vec<String> = list
        .contents
        .items
        .iter()
        .filter_map(|i| id_from_uri(i.uri(), "track").map(str::to_owned))
        .collect();
    hydrate_tracks(session, &ids).await
}

pub async fn album_tracks(session: &Session, id: &str) -> Result<Vec<TrackInfo>, CoreError> {
    let uri = format!("spotify:album:{}", parse_id(id)?.to_base62().map_err(CoreError::from)?);
    let data = extended(session, std::slice::from_ref(&uri), ExtensionKind::ALBUM_V4).await?;
    let bytes = data.get(&uri).ok_or_else(|| CoreError::NotFound { msg: format!("album {id}") })?;
    let album = metadata::Album::parse_from_bytes(bytes).map_err(CoreError::other)?;
    let ids: Vec<String> = album
        .disc
        .iter()
        .flat_map(|d| d.track.iter())
        .filter_map(|t| base62(t.gid()))
        .collect();
    hydrate_tracks(session, &ids).await
}

/// Liked songs (newest first) and saved albums share the "collection" set.
async fn collection(session: &Session) -> Result<Vec<String>, CoreError> {
    let username = session.username();
    let mut uris = Vec::new();
    let mut token = String::new();
    for _ in 0..200 {
        let body = encode_page_request(&username, "collection", &token, 300);
        let mut headers = HeaderMap::new();
        let ct = HeaderValue::from_static("application/vnd.collection-v2.spotify.proto");
        headers.insert(header::CONTENT_TYPE, ct.clone());
        headers.insert(header::ACCEPT, ct);
        let res = session
            .spclient()
            .request(&Method::POST, "/collection/v2/paging", Some(headers), Some(&body))
            .await?;
        let (items, next) = decode_page_response(&res).map_err(CoreError::other)?;
        uris.extend(items);
        if next.is_empty() {
            break;
        }
        token = next;
    }
    log::info!("collection: {} items", uris.len());
    Ok(uris)
}

pub async fn liked_tracks(session: &Session) -> Result<Vec<TrackInfo>, CoreError> {
    let ids: Vec<String> = match collection(session).await {
        Ok(uris) => uris.iter().filter_map(|u| id_from_uri(u, "track").map(str::to_owned)).collect(),
        Err(e) => {
            log::warn!("collection paging failed ({e}), falling back to context-resolve");
            let ctx = session
                .spclient()
                .get_context(&format!("spotify:user:{}:collection", session.username()))
                .await?;
            ctx.pages
                .iter()
                .flat_map(|p| p.tracks.iter())
                .filter_map(|t| id_from_uri(t.uri(), "track").map(str::to_owned))
                .collect()
        }
    };
    hydrate_tracks(session, &ids).await
}

pub async fn saved_albums(session: &Session) -> Result<Vec<AlbumInfo>, CoreError> {
    let uris = collection(session).await?;
    let ids: Vec<String> = uris.iter().filter_map(|u| id_from_uri(u, "album").map(str::to_owned)).collect();
    hydrate_albums(session, &ids).await
}

/// Catalog search through context-resolve. Tracks only (that's all this endpoint returns).
pub async fn search_tracks(session: &Session, query: &str) -> Result<Vec<TrackInfo>, CoreError> {
    let q = query
        .split_whitespace()
        .map(percent_encode)
        .collect::<Vec<_>>()
        .join("+");
    if q.is_empty() {
        return Ok(Vec::new());
    }
    let ctx = session.spclient().get_context(&format!("spotify:search:{q}")).await?;
    let ids: Vec<String> = ctx
        .pages
        .iter()
        .flat_map(|p| p.tracks.iter())
        .filter_map(|t| id_from_uri(t.uri().split('?').next().unwrap_or(""), "track").map(str::to_owned))
        .take(50)
        .collect();
    log::info!("search '{query}': {} track ids", ids.len());
    hydrate_tracks(session, &ids).await
}

fn percent_encode(word: &str) -> String {
    let mut s = String::new();
    for b in word.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            s.push(b as char);
        } else {
            s.push_str(&format!("%{b:02X}"));
        }
    }
    s
}

// ---- collection2v2 wire format (not compiled by librespot-protocol) ----

fn put_varint(buf: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        buf.push((v as u8) | 0x80);
        v >>= 7;
    }
    buf.push(v as u8);
}

fn put_str(buf: &mut Vec<u8>, field: u32, s: &str) {
    if s.is_empty() {
        return;
    }
    put_varint(buf, ((field << 3) | 2) as u64);
    put_varint(buf, s.len() as u64);
    buf.extend_from_slice(s.as_bytes());
}

/// PageRequest { username = 1; set = 2; pagination_token = 3; limit = 4 }
fn encode_page_request(username: &str, set: &str, token: &str, limit: u32) -> Vec<u8> {
    let mut b = Vec::new();
    put_str(&mut b, 1, username);
    put_str(&mut b, 2, set);
    put_str(&mut b, 3, token);
    put_varint(&mut b, 4 << 3);
    put_varint(&mut b, limit as u64);
    b
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn varint(&mut self) -> Result<u64, String> {
        let mut v = 0u64;
        for shift in (0..64).step_by(7) {
            let (&b, rest) = self.0.split_first().ok_or("truncated varint")?;
            self.0 = rest;
            v |= ((b & 0x7f) as u64) << shift;
            if b < 0x80 {
                return Ok(v);
            }
        }
        Err("varint too long".into())
    }

    fn bytes(&mut self) -> Result<&'a [u8], String> {
        let n = self.varint()? as usize;
        if n > self.0.len() {
            return Err("truncated field".into());
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Ok(head)
    }

    /// Calls `f(field, wire_type_2_payload)` for length-delimited fields, skips the rest.
    fn fields(mut self, mut f: impl FnMut(u32, &'a [u8])) -> Result<(), String> {
        while !self.0.is_empty() {
            let key = self.varint()?;
            let field = (key >> 3) as u32;
            match key & 7 {
                0 => {
                    self.varint()?;
                }
                1 => self.skip(8)?,
                2 => {
                    let b = self.bytes()?;
                    f(field, b);
                }
                5 => self.skip(4)?,
                t => return Err(format!("unsupported wire type {t}")),
            }
        }
        Ok(())
    }

    fn skip(&mut self, n: usize) -> Result<(), String> {
        if n > self.0.len() {
            return Err("truncated fixed field".into());
        }
        self.0 = &self.0[n..];
        Ok(())
    }
}

/// PageResponse { repeated CollectionItem items = 1; next_page_token = 2 },
/// CollectionItem { uri = 1; added_at = 2; is_removed = 3 }
fn decode_page_response(data: &[u8]) -> Result<(Vec<String>, String), String> {
    let mut items = Vec::new();
    let mut next = String::new();
    let mut err = None;
    Reader(data).fields(|field, payload| match field {
        1 => {
            let mut uri = String::new();
            let mut removed = false;
            let mut r = Reader(payload);
            // Parse manually to also see the varint is_removed flag.
            while !r.0.is_empty() {
                let Ok(key) = r.varint() else { break };
                match (key >> 3, key & 7) {
                    (1, 2) => match r.bytes() {
                        Ok(b) => uri = String::from_utf8_lossy(b).into_owned(),
                        Err(e) => err = Some(e),
                    },
                    (3, 0) => removed = r.varint().unwrap_or(0) != 0,
                    (_, 0) => {
                        let _ = r.varint();
                    }
                    (_, 2) => {
                        let _ = r.bytes();
                    }
                    (_, 1) => {
                        let _ = r.skip(8);
                    }
                    (_, 5) => {
                        let _ = r.skip(4);
                    }
                    _ => break,
                }
            }
            if !removed && !uri.is_empty() {
                items.push(uri);
            }
        }
        2 => next = String::from_utf8_lossy(payload).into_owned(),
        _ => {}
    })?;
    match err {
        Some(e) => Err(e),
        None => Ok((items, next)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn page_roundtrip() {
        // Build a fake PageResponse: two items (one removed) + token.
        let mut item1 = Vec::new();
        put_str(&mut item1, 1, "spotify:track:4uLU6hMCjMI75M1A2tKUQC");
        let mut item2 = Vec::new();
        put_str(&mut item2, 1, "spotify:album:abc");
        put_varint(&mut item2, 3 << 3);
        put_varint(&mut item2, 1);
        let mut resp = Vec::new();
        for item in [&item1, &item2] {
            put_varint(&mut resp, (1 << 3) | 2);
            put_varint(&mut resp, item.len() as u64);
            resp.extend_from_slice(item);
        }
        put_str(&mut resp, 2, "next");
        let (items, next) = decode_page_response(&resp).unwrap();
        assert_eq!(items, vec!["spotify:track:4uLU6hMCjMI75M1A2tKUQC"]);
        assert_eq!(next, "next");
    }

    #[test]
    fn ids() {
        assert_eq!(id_from_uri("spotify:track:abc123", "track"), Some("abc123"));
        assert_eq!(id_from_uri("spotify:start-group:x", "playlist"), None);
        assert_eq!(percent_encode("a b&ü"), "a%20b%26%C3%BC");
    }
}
