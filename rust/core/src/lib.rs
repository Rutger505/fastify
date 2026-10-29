//! Fastify core: a thin, cache-first layer over librespot exposed to Kotlin through UniFFI.
//!
//! Kotlin owns UI, metadata (Web API) and playback (Media3). Rust owns the Spotify session,
//! audio keys, CDN downloads, decryption and the audio file cache.

mod error;
mod keystore;
mod stream;

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use librespot_audio::{AudioDecrypt, AudioFile};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::SessionConfig;
use librespot_core::{FileId, Session, SpotifyId, SpotifyUri};
use librespot_metadata::audio::AudioFileFormat;
use librespot_metadata::{Metadata, Track};
use tokio::runtime::Runtime;
use tokio::sync::Mutex;

pub use error::CoreError;
use keystore::{KeyEntry, KeyStore};
pub use stream::TrackStream;

uniffi::setup_scaffolding!();

/// Desktop client id. Same as librespot on Linux; the OAuth token Kotlin obtains is issued for it,
/// and keymaster accepts Web API scopes for it.
pub const KEYMASTER_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

/// Preferred formats, best first. All Ogg Vorbis: ExoPlayer plays these natively.
const FORMAT_PREFERENCE: [AudioFileFormat; 3] = [
    AudioFileFormat::OGG_VORBIS_320,
    AudioFileFormat::OGG_VORBIS_160,
    AudioFileFormat::OGG_VORBIS_96,
];

#[derive(uniffi::Record)]
pub struct AccessToken {
    pub token: String,
    pub expires_at_ms: u64,
}

#[derive(uniffi::Object)]
pub struct FastifyCore {
    rt: Runtime,
    cache: Cache,
    keys: KeyStore,
    session: Mutex<Option<Session>>,
    device_id: String,
}

#[uniffi::export]
impl FastifyCore {
    /// `files_dir`: persistent storage (credentials, key index).
    /// `cache_dir`: audio file cache, bounded by `audio_cache_bytes`.
    #[uniffi::constructor]
    pub fn new(
        files_dir: String,
        cache_dir: String,
        audio_cache_bytes: u64,
    ) -> Result<Arc<Self>, CoreError> {
        init_logging();
        let files = PathBuf::from(files_dir);
        let audio = PathBuf::from(cache_dir).join("audio");
        let cache = Cache::new(
            Some(files.join("credentials")),
            None,
            Some(audio),
            Some(audio_cache_bytes),
        )
        .map_err(CoreError::other)?;
        let keys = KeyStore::open(files.join("keys"))?;
        let device_id = keys.device_id()?;
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("fastify-core")
            .enable_all()
            .build()
            .map_err(CoreError::other)?;
        Ok(Arc::new(Self {
            rt,
            cache,
            keys,
            session: Mutex::new(None),
            device_id,
        }))
    }

    /// True when reusable credentials are stored, i.e. no login UI is needed.
    pub fn has_stored_credentials(&self) -> bool {
        self.cache.credentials().is_some()
    }

    /// First login with an OAuth access token (scope `streaming`). Stores reusable credentials.
    /// Returns the canonical username.
    pub async fn login(self: Arc<Self>, access_token: String) -> Result<String, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this
                    .connect_with(Credentials::with_access_token(access_token))
                    .await?;
                Ok(session.username())
            })
            .await
            .map_err(CoreError::other)?
    }

    /// Connect (or reuse the live session) with stored credentials. Cheap when already connected.
    pub async fn connect(self: Arc<Self>) -> Result<String, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move { Ok(this.session().await?.username()) })
            .await
            .map_err(CoreError::other)?
    }

    /// Forget credentials and drop the session. Cached audio stays (it is useless without keys,
    /// so the key index is wiped too).
    pub fn logout(&self) -> Result<(), CoreError> {
        if let Some(s) = self.session.blocking_lock().take() {
            s.shutdown();
        }
        self.keys.clear()?;
        let creds = self.cache_credentials_path();
        if let Some(p) = creds {
            let _ = std::fs::remove_file(p);
        }
        Ok(())
    }

    /// Web API token through keymaster (fallback for the OAuth token Kotlin holds).
    /// `scopes` is comma-separated.
    pub async fn access_token(self: Arc<Self>, scopes: String) -> Result<AccessToken, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                let token = session
                    .token_provider()
                    .get_token_with_client_id(&scopes, KEYMASTER_CLIENT_ID)
                    .await
                    .map_err(CoreError::from)?;
                let now = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default();
                Ok(AccessToken {
                    token: token.access_token,
                    expires_at_ms: (now + token.expires_in).as_millis() as u64,
                })
            })
            .await
            .map_err(CoreError::other)?
    }

    /// True when the track can start without any network (audio file and key on disk).
    pub fn is_track_cached(&self, track_id: String) -> bool {
        self.keys
            .get(&track_id)
            .and_then(|e| self.cache.file_path(e.file_id()))
            .is_some_and(|p| p.exists())
    }

    /// Open a decrypted, seekable Ogg stream for a track. Blocking: call from a worker thread
    /// (ExoPlayer's loader thread). Cache hits need no session at all.
    pub fn open_track(&self, track_id: String) -> Result<Arc<TrackStream>, CoreError> {
        if let Some(stream) = self.open_cached(&track_id)? {
            return Ok(Arc::new(stream));
        }
        let handle = self.rt.handle().clone();
        let stream = handle.block_on(async {
            let session = self.session().await?;
            let entry = self.resolve(&session, &track_id).await?;
            let file = AudioFile::open(&session, entry.file_id(), entry.bytes_per_second())
                .await
                .map_err(CoreError::from)?;
            TrackStream::new(file, entry.key(), false)
        })?;
        Ok(Arc::new(stream))
    }

    /// Download a whole track into the cache in the background. Returns once fully cached.
    pub async fn prefetch(self: Arc<Self>, track_id: String) -> Result<(), CoreError> {
        if self.is_track_cached(track_id.clone()) {
            return Ok(());
        }
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                let entry = this.resolve(&session, &track_id).await?;
                let file = AudioFile::open(&session, entry.file_id(), entry.bytes_per_second())
                    .await
                    .map_err(CoreError::from)?;
                if file.is_cached() {
                    return Ok(());
                }
                if let Ok(ctl) = file.get_stream_loader_controller() {
                    ctl.set_stream_mode();
                }
                // Reading to the end makes librespot finish the download and commit it to the cache.
                tokio::task::spawn_blocking(move || {
                    let mut reader = AudioDecrypt::new(Some(entry.key()), file);
                    std::io::copy(&mut reader, &mut std::io::sink()).map(|_| ())
                })
                .await
                .map_err(CoreError::other)?
                .map_err(CoreError::other)?;
                // The cache write happens in a spawned task right after completion.
                tokio::time::sleep(Duration::from_millis(200)).await;
                Ok(())
            })
            .await
            .map_err(CoreError::other)?
    }
}

impl FastifyCore {
    fn cache_credentials_path(&self) -> Option<PathBuf> {
        // Mirrors librespot's layout: <credentials dir>/credentials.json
        self.keys.root().parent().map(|p| p.join("credentials").join("credentials.json"))
    }

    fn open_cached(&self, track_id: &str) -> Result<Option<TrackStream>, CoreError> {
        let Some(entry) = self.keys.get(track_id) else {
            return Ok(None);
        };
        let Some(file) = self.cache.file(entry.file_id()) else {
            return Ok(None);
        };
        TrackStream::new(AudioFile::Cached(file), entry.key(), true).map(Some)
    }

    /// Live session, connecting with stored credentials if needed. The lock is held while
    /// connecting so concurrent callers share one connection attempt.
    async fn session(&self) -> Result<Session, CoreError> {
        let mut guard = self.session.lock().await;
        if let Some(s) = guard.as_ref() {
            if !s.is_invalid() {
                return Ok(s.clone());
            }
        }
        let creds = self.cache.credentials().ok_or(CoreError::NotLoggedIn)?;
        self.connect_locked(&mut guard, creds).await
    }

    async fn connect_with(&self, creds: Credentials) -> Result<Session, CoreError> {
        let mut guard = self.session.lock().await;
        self.connect_locked(&mut guard, creds).await
    }

    async fn connect_locked(
        &self,
        slot: &mut Option<Session>,
        creds: Credentials,
    ) -> Result<Session, CoreError> {
        let config = SessionConfig {
            client_id: KEYMASTER_CLIENT_ID.to_owned(),
            device_id: self.device_id.clone(),
            ..SessionConfig::default()
        };
        let session = Session::new(config, Some(self.cache.clone()));
        session.connect(creds, true).await.map_err(CoreError::from)?;
        if let Some(old) = slot.replace(session.clone()) {
            old.shutdown();
        }
        Ok(session)
    }

    /// Track id -> playable file + key. Uses the key index first, then metadata and the AP.
    async fn resolve(&self, session: &Session, track_id: &str) -> Result<KeyEntry, CoreError> {
        if let Some(e) = self.keys.get(track_id) {
            return Ok(e);
        }
        let id = SpotifyId::from_base62(track_id).map_err(|_| CoreError::NotFound {
            msg: format!("invalid track id {track_id}"),
        })?;
        let uri = SpotifyUri::Track { id };
        let track = Track::get(session, &uri).await.map_err(CoreError::from)?;

        // Region-locked tracks list playable alternatives.
        let mut candidates = vec![track];
        for alt in candidates[0].alternatives.0.clone() {
            if let Ok(t) = Track::get(session, &alt).await {
                candidates.push(t);
            }
        }
        for t in &candidates {
            for format in FORMAT_PREFERENCE {
                if let Some(file_id) = t.files.get(&format) {
                    let SpotifyUri::Track { id: play_id } = t.id else {
                        continue;
                    };
                    let key = session
                        .audio_key()
                        .request(play_id, *file_id)
                        .await
                        .map_err(CoreError::from)?;
                    let entry = KeyEntry::new(*file_id, key, format);
                    self.keys.put(track_id, &entry)?;
                    return Ok(entry);
                }
            }
        }
        Err(CoreError::NotFound {
            msg: format!("no playable Ogg Vorbis file for {track_id}"),
        })
    }
}

fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("fastify-core"),
    );
}

// FileId is needed by keystore.
pub(crate) fn file_id_from_hex(hex_str: &str) -> Option<FileId> {
    let raw = hex::decode(hex_str).ok()?;
    (raw.len() == 20).then(|| FileId::from_raw(&raw))
}
