//! Song recognition through Shazam's (unofficial) tag API, using SongRec's port of the
//! Shazam fingerprint algorithm (https://github.com/marin-m/SongRec, GPL-3.0).

mod algorithm;
mod hanning;
mod signature_format;

use std::time::SystemTime;

use bytes::Bytes;
use http::{Request, header};
use http_body_util::Full;
use serde_json::{Value, json};

use crate::CoreError;
use algorithm::SignatureGenerator;

/// Shazam wants 16 kHz mono; SongRec sends at most 12 s.
pub const SAMPLE_RATE: u32 = 16_000;
const MAX_SECONDS: usize = 12;
const USER_AGENT: &str = "Dalvik/2.1.0 (Linux; U; Android 15; Pixel 8 Build/AP4A.250105.002)";

#[derive(Debug, Clone, uniffi::Record)]
pub struct ShazamMatch {
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub cover_url: Option<String>,
    pub isrc: Option<String>,
    /// Spotify track id when Shazam links one (often only a search URI, so may be None).
    pub spotify_track_id: Option<String>,
}

/// Fingerprint 16 kHz mono PCM and ask Shazam. Ok(None) = Shazam heard nothing it knows.
pub async fn recognize(pcm: Vec<i16>) -> Result<Option<ShazamMatch>, CoreError> {
    let pcm = &pcm[..pcm.len().min(MAX_SECONDS * SAMPLE_RATE as usize)];
    if pcm.len() < 3 * SAMPLE_RATE as usize {
        return Err(CoreError::other(format!("recording too short: {} samples", pcm.len())));
    }
    let signature = SignatureGenerator::make_signature(pcm);
    let uri = signature.encode_to_uri().map_err(CoreError::other)?;
    let now_ms = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_err(CoreError::other)?
        .as_millis() as u32;
    let body = json!({
        "geolocation": { "altitude": 300, "latitude": 45, "longitude": 2 },
        "signature": {
            "samplems": (signature.number_samples as f32 / signature.sample_rate_hz as f32 * 1000.) as u32,
            "timestamp": now_ms,
            "uri": uri,
        },
        "timestamp": now_ms,
        "timezone": "Europe/Amsterdam",
    })
    .to_string();
    let url = format!(
        "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/{}/{}?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3",
        uuid::Uuid::new_v4().hyphenated().to_string().to_uppercase(),
        uuid::Uuid::new_v4().hyphenated(),
    );
    let req = Request::post(url)
        .header(header::USER_AGENT, USER_AGENT)
        .header(header::CONTENT_TYPE, "application/json")
        .header(header::CONTENT_LANGUAGE, "en_US")
        .body(Full::new(Bytes::from(body)))
        .map_err(CoreError::other)?;
    let (status, body) = crate::pathfinder::send(req).await?;
    if !status.is_success() {
        let text = String::from_utf8_lossy(&body);
        return Err(CoreError::Network { msg: format!("Shazam HTTP {status}: {}", &text[..text.len().min(300)]) });
    }
    let v: Value = serde_json::from_slice(&body).map_err(CoreError::other)?;
    Ok(parse(&v))
}

fn parse(v: &Value) -> Option<ShazamMatch> {
    let track = v.get("track")?;
    let s = |v: &Value| v.as_str().map(str::to_owned);
    let album = track["sections"].as_array().and_then(|secs| {
        secs.iter()
            .filter_map(|sec| sec["metadata"].as_array())
            .flatten()
            .find(|m| m["title"] == "Album")
            .and_then(|m| s(&m["text"]))
    });
    // Spotify links live under hub.providers[].actions[].uri ("spotify:track:<id>" or "spotify:search:...").
    let spotify_track_id = track["hub"]["providers"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|p| p["actions"].as_array())
        .flatten()
        .filter_map(|a| a["uri"].as_str())
        .find_map(|u| u.strip_prefix("spotify:track:").map(str::to_owned));
    Some(ShazamMatch {
        title: s(&track["title"])?,
        artist: s(&track["subtitle"]).unwrap_or_default(),
        album,
        cover_url: s(&track["images"]["coverarthq"]).or_else(|| s(&track["images"]["coverart"])),
        isrc: s(&track["isrc"]),
        spotify_track_id,
    })
}
