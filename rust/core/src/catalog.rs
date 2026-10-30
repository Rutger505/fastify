//! Artist pages, album headers and lyrics, all over spclient (extended metadata, color-lyrics).

use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::Session;
use librespot_protocol::extension_kind::ExtensionKind;
use librespot_protocol::metadata;
use protobuf::Message;
use serde::Deserialize;

use crate::CoreError;
use crate::library::{
    AlbumInfo, TrackInfo, album_info, base62, best_image, collection_set, extended,
    hydrate_albums, hydrate_tracks, id_from_uri, parse_id,
};

#[derive(uniffi::Record, Clone)]
pub struct ArtistPage {
    pub id: String,
    pub name: String,
    pub image_url: Option<String>,
    /// Spotify sends light HTML (links to other artists).
    pub biography: Option<String>,
    pub top_tracks: Vec<TrackInfo>,
    pub albums: Vec<AlbumInfo>,
    pub singles: Vec<AlbumInfo>,
    pub compilations: Vec<AlbumInfo>,
    pub appears_on: Vec<AlbumInfo>,
}

#[derive(uniffi::Record, Clone)]
pub struct AlbumPage {
    pub album: AlbumInfo,
    pub tracks: Vec<TrackInfo>,
}

#[derive(uniffi::Record, Clone)]
pub struct LyricLine {
    pub start_ms: u64,
    pub words: String,
}

#[derive(uniffi::Record, Clone)]
pub struct Lyrics {
    /// True when start_ms is meaningful (LINE_SYNCED).
    pub synced: bool,
    pub lines: Vec<LyricLine>,
    pub provider: String,
}

/// Caps per section. "Appears on" can be hundreds of albums for popular artists, and every
/// album costs an ALBUM_V4 lookup.
const MAX_RELEASES: usize = 100;
const MAX_APPEARS_ON: usize = 50;
const TOP_TRACKS: usize = 10;

/// Each AlbumGroup holds one release (sometimes several regional versions): take the first.
fn group_ids(groups: &[metadata::AlbumGroup], max: usize) -> Vec<String> {
    groups
        .iter()
        .filter_map(|g| g.album.first().and_then(|a| base62(a.gid())))
        .take(max)
        .collect()
}

/// Newest first; releases without a year last. Stable, so same-year order is kept.
fn sort_releases(albums: &mut [AlbumInfo]) {
    albums.sort_by(|a, b| b.year.unwrap_or(i32::MIN).cmp(&a.year.unwrap_or(i32::MIN)));
}

pub async fn artist(session: &Session, id: &str) -> Result<ArtistPage, CoreError> {
    let uri = format!(
        "spotify:artist:{}",
        parse_id(id)?.to_base62().map_err(CoreError::from)?
    );
    let data = extended(session, std::slice::from_ref(&uri), ExtensionKind::ARTIST_V4).await?;
    let bytes = data.get(&uri).ok_or_else(|| CoreError::NotFound {
        msg: format!("artist {id}"),
    })?;
    let a = metadata::Artist::parse_from_bytes(bytes).map_err(CoreError::other)?;

    // Top tracks are per country: prefer the account's country, else the first list.
    let country = session.country();
    let top = a
        .top_track
        .iter()
        .find(|t| t.country() == country)
        .or_else(|| a.top_track.first());
    let top_ids: Vec<String> = top
        .map(|t| {
            t.track
                .iter()
                .filter_map(|t| base62(t.gid()))
                .take(TOP_TRACKS)
                .collect()
        })
        .unwrap_or_default();

    let album_ids = group_ids(&a.album_group, MAX_RELEASES);
    let single_ids = group_ids(&a.single_group, MAX_RELEASES);
    let compilation_ids = group_ids(&a.compilation_group, MAX_RELEASES);
    let appears_ids = group_ids(&a.appears_on_group, MAX_APPEARS_ON);

    let (top_tracks, mut albums, mut singles, mut compilations, mut appears_on) = tokio::try_join!(
        hydrate_tracks(session, &top_ids),
        hydrate_albums(session, &album_ids),
        hydrate_albums(session, &single_ids),
        hydrate_albums(session, &compilation_ids),
        hydrate_albums(session, &appears_ids),
    )?;
    for list in [&mut albums, &mut singles, &mut compilations, &mut appears_on] {
        sort_releases(list);
    }

    let image_url = a
        .portrait_group
        .as_ref()
        .and_then(|g| best_image(&g.image))
        .or_else(|| best_image(&a.portrait))
        .or_else(|| {
            a.biography
                .iter()
                .flat_map(|b| b.portrait_group.iter())
                .find_map(|g| best_image(&g.image))
        });
    let biography = a
        .biography
        .iter()
        .map(|b| b.text().trim().to_owned())
        .find(|t| !t.is_empty());

    log::info!(
        "artist {}: {} top, {} albums, {} singles, {} compilations, {} appears on",
        a.name(),
        top_tracks.len(),
        albums.len(),
        singles.len(),
        compilations.len(),
        appears_on.len()
    );
    Ok(ArtistPage {
        id: id.to_owned(),
        name: a.name().to_owned(),
        image_url,
        biography,
        top_tracks,
        albums,
        singles,
        compilations,
        appears_on,
    })
}

pub async fn album(session: &Session, id: &str) -> Result<AlbumPage, CoreError> {
    let uri = format!(
        "spotify:album:{}",
        parse_id(id)?.to_base62().map_err(CoreError::from)?
    );
    let data = extended(session, std::slice::from_ref(&uri), ExtensionKind::ALBUM_V4).await?;
    let bytes = data.get(&uri).ok_or_else(|| CoreError::NotFound {
        msg: format!("album {id}"),
    })?;
    let a = metadata::Album::parse_from_bytes(bytes).map_err(CoreError::other)?;
    let ids: Vec<String> = a
        .disc
        .iter()
        .flat_map(|d| d.track.iter())
        .filter_map(|t| base62(t.gid()))
        .collect();
    Ok(AlbumPage {
        album: album_info(id.to_owned(), &a),
        tracks: hydrate_tracks(session, &ids).await?,
    })
}

/// Latest releases per followed artist that are checked. Spotify lists groups newest first.
const NEW_PER_GROUP: usize = 3;

/// Albums, singles and EPs by followed artists released in the last `days` days, newest first.
pub async fn new_releases(session: &Session, days: u32) -> Result<Vec<AlbumInfo>, CoreError> {
    let artists: Vec<String> = collection_set(session, "artist")
        .await?
        .iter()
        .filter_map(|u| id_from_uri(u, "artist").map(|id| format!("spotify:artist:{id}")))
        .collect();
    let data = extended(session, &artists, ExtensionKind::ARTIST_V4).await?;
    let mut ids = Vec::new();
    for uri in &artists {
        let Some(a) = data
            .get(uri)
            .and_then(|b| metadata::Artist::parse_from_bytes(b).ok())
        else {
            continue;
        };
        ids.extend(group_ids(&a.album_group, NEW_PER_GROUP));
        ids.extend(group_ids(&a.single_group, NEW_PER_GROUP));
    }
    let mut seen = std::collections::HashSet::new();
    ids.retain(|id| seen.insert(id.clone()));

    let cutoff = cutoff_date(days);
    let mut albums: Vec<AlbumInfo> = hydrate_albums(session, &ids)
        .await?
        .into_iter()
        .filter(|a| a.release_date.as_deref().is_some_and(|d| d >= cutoff.as_str()))
        .collect();
    albums.sort_by(|a, b| b.release_date.cmp(&a.release_date));
    log::info!(
        "new releases: {} artists, {} candidates, {} in the last {days} days",
        artists.len(),
        ids.len(),
        albums.len()
    );
    Ok(albums)
}

/// "YYYY-MM-DD" for today minus `days` (UTC), without a date crate.
fn cutoff_date(days: u32) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    let (y, m, d) = civil_from_days(now / 86_400 - days as i64);
    format!("{y:04}-{m:02}-{d:02}")
}

/// Howard Hinnant's days-to-civil algorithm.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y, m, d)
}

// ---- lyrics (color-lyrics v2, JSON) ----

#[derive(Deserialize)]
struct LyricsResponse {
    lyrics: LyricsBody,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LyricsBody {
    #[serde(default)]
    sync_type: String,
    #[serde(default)]
    lines: Vec<LineJson>,
    #[serde(default)]
    provider_display_name: String,
    #[serde(default)]
    provider: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LineJson {
    #[serde(default)]
    start_time_ms: String,
    #[serde(default)]
    words: String,
}

/// Ok(None) when Spotify has no lyrics for the track (404 / empty body).
pub async fn lyrics(session: &Session, track_id: &str) -> Result<Option<Lyrics>, CoreError> {
    let id = parse_id(track_id)?.to_base62().map_err(CoreError::from)?;
    let path =
        format!("/color-lyrics/v2/track/{id}?format=json&vocalRemoval=false&market=from_token");
    let mut headers = HeaderMap::new();
    headers.insert(header::ACCEPT, HeaderValue::from_static("application/json"));
    headers.insert("app-platform", HeaderValue::from_static("WebPlayer"));
    let body = match session
        .spclient()
        .request(&Method::GET, &path, Some(headers), None)
        .await
    {
        Ok(b) => b,
        Err(e) if e.kind == librespot_core::error::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    if body.is_empty() {
        return Ok(None);
    }
    let parsed: LyricsResponse = serde_json::from_slice(&body).map_err(CoreError::other)?;
    let l = parsed.lyrics;
    let lines = l
        .lines
        .into_iter()
        .map(|line| LyricLine {
            start_ms: line.start_time_ms.parse().unwrap_or(0),
            words: line.words,
        })
        .collect::<Vec<_>>();
    Ok(Some(Lyrics {
        synced: l.sync_type == "LINE_SYNCED",
        lines,
        provider: if l.provider_display_name.is_empty() {
            l.provider
        } else {
            l.provider_display_name
        },
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(20_391), (2025, 10, 30));
    }
}
