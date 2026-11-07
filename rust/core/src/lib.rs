//! Fastify core: a thin, cache-first layer over librespot exposed to Kotlin through UniFFI.
//!
//! Kotlin owns UI, metadata (Web API) and playback (Media3). Rust owns the Spotify session,
//! audio keys, CDN downloads, decryption and the audio file cache.

mod catalog;
mod connect;
mod edit;
mod error;
mod keystore;
mod jam;
mod library;
mod pathfinder;
mod shazam;
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

pub use catalog::{AlbumPage, ArtistPage, LyricLine, Lyrics};
pub use connect::{
    ConnectRequest, IncomingRequest, LocalPlayer, RemoteCommand, RemoteDevice, RemoteState,
    RemoteTrack,
};
pub use error::CoreError;
pub use jam::{JamInfo, JamMember, JamPreview};

/// A Jam push: read the Jam again. reason: Spotify's (YOU_WERE_KICKED, SESSION_DELETED, ...).
#[derive(uniffi::Record, Clone, Debug)]
pub struct JamEvent {
    pub version: u64,
    pub reason: String,
}
use keystore::{KeyEntry, KeyStore};
pub use library::{AlbumInfo, ArtistRef, PlaylistInfo, TrackInfo};
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
    /// Downloaded tracks: encrypted audio files outside the LRU cache, never evicted.
    downloads: PathBuf,
    connect: connect::Hub,
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
        let downloads = files.join("downloads");
        std::fs::create_dir_all(&downloads)?;
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
            downloads,
            connect: connect::Hub::new(),
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
        // Downloads can't be decrypted without the keys.
        if let Ok(dir) = std::fs::read_dir(&self.downloads) {
            for e in dir.flatten() {
                let _ = std::fs::remove_file(e.path());
            }
        }
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

    /// Username of the connected session (connects if needed).
    pub async fn username(self: Arc<Self>) -> Result<String, CoreError> {
        self.connect().await
    }

    /// Raw GET against Spotify's internal API (spclient), e.g. `/playlist/v2/user/x/rootlist`.
    /// Used for diagnostics now and as the base for internal-API library/search.
    /// Recognize a song from 16 kHz mono PCM (Shazam via SongRec's fingerprint). None = no match.
    pub async fn shazam(self: Arc<Self>, pcm: Vec<i16>) -> Result<Option<shazam::ShazamMatch>, CoreError> {
        self.rt.spawn(shazam::recognize(pcm)).await.map_err(CoreError::other)?
    }

    pub async fn spclient_get(self: Arc<Self>, path: String) -> Result<Vec<u8>, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                let body = session
                    .spclient()
                    .request(&http::Method::GET, &path, None, None)
                    .await
                    .map_err(CoreError::from)?;
                Ok(body.to_vec())
            })
            .await
            .map_err(CoreError::other)?
    }

    /// The Jam this account is in, if any. Jam calls register the Connect device first: a Jam
    /// is bound to it.
    pub async fn jam_get(self: Arc<Self>) -> Result<Option<JamInfo>, CoreError> {
        self.with_jam(|s| async move { jam::get(&s).await }).await
    }

    /// Long-poll Jam pushes (same dealer as Connect): returns once the revision is past
    /// `after_version` or after ~25 s. Read the Jam again when the version changed.
    pub async fn jam_watch(self: Arc<Self>, after_version: u64) -> Result<JamEvent, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                this.connect.ensure(&session);
                let (version, reason) = this.connect.wait_jam(after_version, Duration::from_secs(25)).await;
                Ok(JamEvent { version, reason })
            })
            .await
            .map_err(CoreError::other)?
    }

    /// Host a Jam on this Connect device (Premium only).
    pub async fn jam_start(self: Arc<Self>) -> Result<JamInfo, CoreError> {
        self.with_jam(|s| async move { jam::start(&s).await }).await
    }

    /// Who's in a Jam before joining. invite: open.spotify.com link, spotify:socialsession:, token.
    pub async fn jam_preview(self: Arc<Self>, invite: String) -> Result<JamPreview, CoreError> {
        self.with_jam(move |s| async move { jam::preview(&s, &invite).await }).await
    }

    /// Join: listen_here plays the Jam on this phone (falls back to controlling the host's
    /// speaker when Spotify refuses), else control the host's speaker.
    pub async fn jam_join(self: Arc<Self>, invite: String, listen_here: bool) -> Result<JamInfo, CoreError> {
        self.with_jam(move |s| async move { jam::join(&s, &invite, listen_here).await }).await
    }

    /// Leave as a guest or end the Jam as its host. Refuses to touch a different session.
    pub async fn jam_leave(self: Arc<Self>, session_id: String) -> Result<(), CoreError> {
        self.with_jam(move |s| async move { jam::leave(&s, &session_id).await }).await
    }

    pub async fn jam_set_queue_only(self: Arc<Self>, session_id: String, queue_only: bool) -> Result<(), CoreError> {
        self.with_jam(move |s| async move { jam::set_queue_only(&s, &session_id, queue_only).await }).await
    }

    pub async fn jam_kick(self: Arc<Self>, session_id: String, member_id: String) -> Result<(), CoreError> {
        self.with_jam(move |s| async move { jam::kick(&s, &session_id, &member_id).await }).await
    }

    /// Guest listening here: a skip/seek/queue/play goes to the Jam, which relays it back.
    pub async fn jam_command(self: Arc<Self>, session_id: String, command: RemoteCommand) -> Result<(), CoreError> {
        self.with_jam(move |s| async move { jam::command(&s, &session_id, &command).await }).await
    }

    /// Spotify Connect: the account's devices and what the active one plays. Starts the observer
    /// (and reconnects the session) when needed, then returns once the state's version is past
    /// `after_version`, or after ~25 s with the unchanged state. Call in a loop while visible.
    pub async fn connect_watch(self: Arc<Self>, after_version: u64) -> Result<RemoteState, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                this.connect.ensure(&session);
                Ok(this.connect.wait(after_version, Duration::from_secs(25)).await)
            })
            .await
            .map_err(CoreError::other)?
    }

    /// Send a player command to Connect device `device_id`.
    pub async fn connect_command(
        self: Arc<Self>,
        device_id: String,
        command: RemoteCommand,
    ) -> Result<(), CoreError> {
        self.with_session(move |s| async move { connect::command(&s, &device_id, &command).await })
            .await
    }

    /// Move playback between Connect devices (connect_device_id() = this phone). From this
    /// phone, its current state is made active and PUT first: the transfer is built from it.
    pub async fn connect_transfer(
        self: Arc<Self>,
        from_device_id: String,
        to_device_id: String,
    ) -> Result<(), CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let s = this.session().await?;
                this.connect.ensure(&s);
                if from_device_id == s.device_id() {
                    this.connect.activate_now(&s).await?;
                }
                connect::transfer(&s, &from_device_id, &to_device_id).await
            })
            .await
            .map_err(CoreError::other)?
    }

    /// This phone's Connect device id.
    pub fn connect_device_id(&self) -> String {
        self.device_id.clone()
    }

    /// Register the local player as a Connect device (Some, call on every change) or go back
    /// to a hidden observer (None).
    pub fn connect_set_player(&self, player: Option<LocalPlayer>) {
        self.connect.set_player(player);
    }

    /// Local playback started here: take over as the account's active device.
    pub fn connect_activate(&self) {
        self.connect.activate();
    }

    /// Nothing loaded any more: stop being the active device.
    pub fn connect_deactivate(&self) {
        self.connect.deactivate();
    }

    /// Next command from another device for the local player; None after ~25 s. Keeps the
    /// Connect registration alive (reconnects the session when needed): call in a loop.
    pub async fn connect_next_request(self: Arc<Self>) -> Result<Option<IncomingRequest>, CoreError> {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                this.connect.ensure(&session);
                Ok(this.connect.next_request(Duration::from_secs(25)).await)
            })
            .await
            .map_err(CoreError::other)?
    }

    /// Answer a request from connect_next_request (ok = handled).
    pub fn connect_reply(&self, request_id: u64, ok: bool) {
        self.connect.reply(request_id, ok);
    }

    /// The user's playlists (rootlist, folders flattened).
    pub async fn playlists(self: Arc<Self>) -> Result<Vec<PlaylistInfo>, CoreError> {
        self.with_session(|s| async move { library::rootlist(&s).await })
            .await
    }

    pub async fn playlist_tracks(
        self: Arc<Self>,
        playlist_id: String,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move { library::playlist_tracks(&s, &playlist_id).await })
            .await
    }

    /// The first four playlist tracks, used to build Spotify-style playlist artwork.
    pub async fn playlist_cover_tracks(
        self: Arc<Self>,
        playlist_id: String,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move { library::playlist_cover_tracks(&s, &playlist_id).await })
            .await
    }

    pub async fn liked_tracks(self: Arc<Self>) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move { library::liked_tracks(&s).await })
            .await
    }

    pub async fn saved_albums(self: Arc<Self>) -> Result<Vec<AlbumInfo>, CoreError> {
        self.with_session(|s| async move { library::saved_albums(&s).await })
            .await
    }

    pub async fn album_tracks(
        self: Arc<Self>,
        album_id: String,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move { library::album_tracks(&s, &album_id).await })
            .await
    }

    pub async fn search_tracks(
        self: Arc<Self>,
        query: String,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move { library::search_tracks(&s, &query).await })
            .await
    }

    /// Autoplay recommendations after `context_uri` (playlist/album/artist/collection/track uri)
    /// ended. `recent_track_ids`: what was just played, oldest first; never returned.
    pub async fn autoplay(
        self: Arc<Self>,
        context_uri: String,
        recent_track_ids: Vec<String>,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move {
            library::autoplay(&s, &context_uri, &recent_track_ids).await
        })
        .await
    }

    /// Artist page: top tracks, albums, singles, compilations, appears on.
    pub async fn artist(self: Arc<Self>, artist_id: String) -> Result<ArtistPage, CoreError> {
        self.with_session(|s| async move { catalog::artist(&s, &artist_id).await })
            .await
    }

    /// Releases by followed artists from the last `days` days, newest first.
    pub async fn new_releases(self: Arc<Self>, days: u32) -> Result<Vec<AlbumInfo>, CoreError> {
        self.with_session(move |s| async move { catalog::new_releases(&s, days).await })
            .await
    }

    /// Album header (name, artists, year) plus its tracks.
    pub async fn album(self: Arc<Self>, album_id: String) -> Result<AlbumPage, CoreError> {
        self.with_session(|s| async move { catalog::album(&s, &album_id).await })
            .await
    }

    /// Lyrics, None when Spotify has none for this track.
    pub async fn lyrics(self: Arc<Self>, track_id: String) -> Result<Option<Lyrics>, CoreError> {
        self.with_session(|s| async move { catalog::lyrics(&s, &track_id).await })
            .await
    }

    /// Create an empty private playlist at the top of the library; returns its id.
    pub async fn playlist_create(self: Arc<Self>, name: String) -> Result<String, CoreError> {
        self.with_session(|s| async move { edit::playlist_create(&s, &name).await })
            .await
    }

    pub async fn playlist_add(
        self: Arc<Self>,
        playlist_id: String,
        track_ids: Vec<String>,
    ) -> Result<(), CoreError> {
        self.with_session(|s| async move { edit::playlist_add(&s, &playlist_id, &track_ids).await })
            .await
    }

    /// Remove one row: the `occurrence`-th copy (0-based) of the track in the playlist.
    pub async fn playlist_remove(
        self: Arc<Self>,
        playlist_id: String,
        track_id: String,
        occurrence: u32,
    ) -> Result<(), CoreError> {
        self.with_session(move |s| async move {
            edit::playlist_remove(&s, &playlist_id, &track_id, occurrence).await
        })
        .await
    }

    pub async fn set_liked(
        self: Arc<Self>,
        track_ids: Vec<String>,
        liked: bool,
    ) -> Result<(), CoreError> {
        self.with_session(move |s| async move { edit::set_liked(&s, &track_ids, liked).await })
            .await
    }

    /// Download a track for offline use (kept outside the LRU audio cache). No-op when already
    /// downloaded. Returns once the file is complete on disk.
    pub async fn download(self: Arc<Self>, track_id: String) -> Result<(), CoreError> {
        let target = self
            .download_path(&track_id)
            .ok_or_else(|| CoreError::other(format!("bad track id {track_id}")))?;
        if self.is_track_downloaded(track_id.clone()) {
            return Ok(());
        }
        let this = self.clone();
        self.rt
            .spawn(async move {
                let tmp = target.with_extension("part");
                // Fast path: the file is already in the LRU cache, copy it without a session.
                if let Some(cached) = this
                    .keys
                    .get(&track_id)
                    .and_then(|e| this.cache.file_path(e.file_id()))
                    .filter(|p| p.exists())
                {
                    std::fs::copy(&cached, &tmp)?;
                } else {
                    let session = this.session().await?;
                    let entry = this.resolve(&session, &track_id).await?;
                    let mut file =
                        AudioFile::open(&session, entry.file_id(), entry.bytes_per_second())
                            .await
                            .map_err(CoreError::from)?;
                    if let Ok(ctl) = file.get_stream_loader_controller() {
                        ctl.set_stream_mode();
                    }
                    let out = tmp.clone();
                    // Raw (still encrypted) bytes, same format as librespot's cache files.
                    tokio::task::spawn_blocking(move || -> std::io::Result<()> {
                        let mut w = std::io::BufWriter::new(std::fs::File::create(&out)?);
                        std::io::copy(&mut file, &mut w)?;
                        std::io::Write::flush(&mut w)
                    })
                    .await
                    .map_err(CoreError::other)??;
                }
                std::fs::rename(&tmp, &target)?;
                Ok(())
            })
            .await
            .map_err(CoreError::other)?
    }

    pub fn is_track_downloaded(&self, track_id: String) -> bool {
        self.keys.get(&track_id).is_some()
            && self.download_path(&track_id).is_some_and(|p| p.exists())
    }

    /// Ids of all downloaded tracks.
    pub fn downloaded_tracks(&self) -> Vec<String> {
        std::fs::read_dir(&self.downloads)
            .map(|dir| {
                dir.flatten()
                    .filter_map(|e| {
                        let name = e.file_name().into_string().ok()?;
                        name.strip_suffix(".ogg").map(str::to_owned)
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn remove_download(&self, track_id: String) -> Result<(), CoreError> {
        if let Some(p) = self.download_path(&track_id) {
            match std::fs::remove_file(p) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
                _ => {}
            }
        }
        Ok(())
    }

    /// Bytes used by downloads.
    pub fn downloads_size(&self) -> u64 {
        std::fs::read_dir(&self.downloads)
            .map(|dir| {
                dir.flatten()
                    .filter_map(|e| e.metadata().ok())
                    .map(|m| m.len())
                    .sum()
            })
            .unwrap_or(0)
    }

    /// Metadata for track ids (e.g. local play history), in order.
    pub async fn tracks(
        self: Arc<Self>,
        track_ids: Vec<String>,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        self.with_session(|s| async move { library::hydrate_tracks(&s, &track_ids).await })
            .await
    }

    /// True when the track can start without any network (audio file and key on disk).
    pub fn is_track_cached(&self, track_id: String) -> bool {
        if self.is_track_downloaded(track_id.clone()) {
            return true;
        }
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
    /// Run `f` with a live session on the core runtime (UniFFI futures run on a foreign executor).
    /// with_session + the Connect device registered (Jam calls name it and its connection).
    async fn with_jam<T, F, Fut>(self: Arc<Self>, f: F) -> Result<T, CoreError>
    where
        T: Send + 'static,
        F: FnOnce(Session) -> Fut + Send + 'static,
        Fut: std::future::Future<Output = Result<T, CoreError>> + Send + 'static,
    {
        let this = self.clone();
        self.rt
            .spawn(async move {
                let session = this.session().await?;
                this.connect.ensure(&session);
                f(session).await
            })
            .await
            .map_err(CoreError::other)?
    }

    async fn with_session<T, F, Fut>(self: Arc<Self>, f: F) -> Result<T, CoreError>
    where
        T: Send + 'static,
        F: FnOnce(Session) -> Fut + Send + 'static,
        Fut: std::future::Future<Output = Result<T, CoreError>> + Send + 'static,
    {
        let this = self.clone();
        self.rt
            .spawn(async move { f(this.session().await?).await })
            .await
            .map_err(CoreError::other)?
    }

    fn cache_credentials_path(&self) -> Option<PathBuf> {
        // Mirrors librespot's layout: <credentials dir>/credentials.json
        self.keys
            .root()
            .parent()
            .map(|p| p.join("credentials").join("credentials.json"))
    }

    fn download_path(&self, track_id: &str) -> Option<PathBuf> {
        // Base62 ids are alphanumeric; reject anything else to keep paths safe.
        (!track_id.is_empty() && track_id.chars().all(|c| c.is_ascii_alphanumeric()))
            .then(|| self.downloads.join(format!("{track_id}.ogg")))
    }

    fn open_cached(&self, track_id: &str) -> Result<Option<TrackStream>, CoreError> {
        let Some(entry) = self.keys.get(track_id) else {
            return Ok(None);
        };
        let downloaded = self
            .download_path(track_id)
            .and_then(|p| std::fs::File::open(p).ok());
        let Some(file) = downloaded.or_else(|| self.cache.file(entry.file_id())) else {
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
        session
            .connect(creds, true)
            .await
            .map_err(CoreError::from)?;
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

