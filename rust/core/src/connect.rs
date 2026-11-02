//! Spotify Connect over one dealer connection per session:
//! - follows the account's device cluster, to show and control other devices (desktop app), and
//! - once the playback service attaches its player (`set_player`), registers Fastify as a real
//!   Connect device: other devices list it, transfer playback here and control it. Their
//!   commands go to Kotlin (`next_request` / `reply`); the local player state is PUT back on
//!   every change.
//!
//! Reference: librespot-connect's spirc.rs + state.rs (not used directly: it drives
//! librespot-playback, ExoPlayer plays here).

use std::collections::HashMap;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::StreamExt;
use futures_util::stream::BoxStream;
use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::dealer::manager::{Reply, RequestReply};
use librespot_core::dealer::protocol::{Command, Message, PlayCommand, Request};
use librespot_core::{Session, version};
use librespot_protocol::connect::{
    Capabilities, Cluster, ClusterUpdate, Device, DeviceInfo, MemberType, PutStateReason,
    PutStateRequest, SetVolumeCommand,
};
use librespot_protocol::player::{ContextPlayerOptions, PlayOrigin, PlayerState, ProvidedTrack};
use librespot_protocol::transfer_state::TransferState;
use protobuf::{EnumOrUnknown, Message as _, MessageField};
use serde_json::{Value, json};
use tokio::sync::{Notify, mpsc, watch};
use tokio::task::JoinHandle;

use crate::CoreError;
use crate::library::{self, TrackInfo};

/// Spotify's clients cap these; more overloads the web player.
const MAX_PREV: usize = 10;
const MAX_NEXT: usize = 80;
/// Kotlin must answer a command within this, else the sender gets a failure.
const REPLY_TIMEOUT: Duration = Duration::from_secs(10);
/// We PUT is_active but another device stays active this long: it took over meanwhile.
const PENDING_GRACE_MS: u64 = 5_000;
/// Contexts up to this size are hydrated before replying; bigger ones hydrate a window around
/// the start track first (the sender waits for the reply) and fill the rest in afterwards.
const HYDRATE_ALL_UP_TO: usize = 300;
const HYDRATE_BEFORE: usize = 10;
const HYDRATE_AFTER: usize = 150;
const FILL_BATCH: usize = 500;
/// Registration (NEW_DEVICE PUT) failed: try again after this.
const REGISTER_RETRY: Duration = Duration::from_secs(5);

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct RemoteDevice {
    pub id: String,
    pub name: String,
    /// Lowercase devices.DeviceType, e.g. "computer", "smartphone", "speaker".
    pub kind: String,
    /// 0..=65535.
    pub volume: u32,
    pub can_play: bool,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct RemoteTrack {
    pub uri: String,
    pub uid: String,
    /// "context", "queue" (hand-queued) or "autoplay".
    pub provider: String,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct RemoteState {
    /// Bumped on every change; pass it back to connect_watch.
    pub version: u64,
    /// The observer is registered and receiving cluster updates.
    pub ready: bool,
    /// Last setup/stream error, shown in the UI.
    pub error: Option<String>,
    /// Other devices on the account (Fastify itself excluded).
    pub devices: Vec<RemoteDevice>,
    /// Device currently owning playback, None when nothing is active (or it's us).
    pub active_device_id: Option<String>,
    pub track: Option<RemoteTrack>,
    pub is_playing: bool,
    /// Position at position_at_ms (local epoch ms). Extrapolate while is_playing.
    pub position_ms: u64,
    pub position_at_ms: u64,
    pub duration_ms: u64,
    pub shuffle: bool,
    pub repeat_context: bool,
    pub repeat_track: bool,
    pub context_uri: Option<String>,
    pub prev_tracks: Vec<RemoteTrack>,
    pub next_tracks: Vec<RemoteTrack>,
    pub queue_revision: String,
}

#[derive(uniffi::Enum, Clone, Debug)]
pub enum RemoteCommand {
    Pause,
    Resume,
    SkipNext,
    SkipPrev,
    SeekTo { position_ms: u64 },
    SetShuffle { on: bool },
    SetRepeat { context: bool, track: bool },
    /// Jump forward to a track in the queue.
    SkipTo { track: RemoteTrack },
    AddToQueue { track_id: String },
    /// Replace upcoming/previous tracks (e.g. remove one). revision from the state.
    SetQueue { next: Vec<RemoteTrack>, prev: Vec<RemoteTrack>, revision: String },
    /// Start track_ids[start_index] at position_ms. With context_uri the device plays that
    /// context (its own order); without, just this list.
    Play {
        context_uri: Option<String>,
        track_ids: Vec<String>,
        start_index: u32,
        position_ms: u64,
        shuffle: Option<bool>,
    },
    /// 0..=65535.
    Volume { volume: u32 },
}


/// The phone's player as other devices should see it. Published by the playback service.
#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct LocalPlayer {
    /// Name in other devices' pickers.
    pub name: String,
    pub track: Option<RemoteTrack>,
    pub is_playing: bool,
    pub is_buffering: bool,
    pub position_ms: u64,
    /// Local epoch ms at which position_ms was read.
    pub position_at_ms: u64,
    pub duration_ms: u64,
    pub shuffle: bool,
    pub repeat_context: bool,
    pub repeat_track: bool,
    pub context_uri: Option<String>,
    /// Play order; uids must identify queue items (SkipNext/SetQueue refer to them).
    pub prev_tracks: Vec<RemoteTrack>,
    pub next_tracks: Vec<RemoteTrack>,
    /// 0..=65535.
    pub volume: u32,
}

/// A command from another device for the local player.
#[derive(uniffi::Enum, Clone, Debug)]
pub enum ConnectRequest {
    /// Replace the queue (play or transfer). start_index -1: from the top (random when
    /// shuffling). queued: hand-queued tracks that go right after the start track.
    Load {
        tracks: Vec<TrackInfo>,
        start_index: i32,
        position_ms: u64,
        paused: bool,
        context_uri: Option<String>,
        shuffle: Option<bool>,
        repeat_context: Option<bool>,
        repeat_track: Option<bool>,
        queued: Vec<TrackInfo>,
    },
    Pause,
    Resume,
    /// Next track, or jump forward to track (match by uid, then uri).
    SkipNext { track: Option<RemoteTrack> },
    SkipPrev,
    SeekTo { position_ms: u64 },
    SetOptions { shuffle: Option<bool>, repeat_context: Option<bool>, repeat_track: Option<bool> },
    AddToQueue { track: TrackInfo },
    /// New upcoming list (order + removals), uids as published. Covers at most the published
    /// window (MAX_NEXT): items past it are kept.
    SetQueue { next: Vec<RemoteTrack> },
    /// Event: metadata for tracks a big Load sent as stubs (id only, empty title). missing:
    /// ids without metadata (unavailable), drop them.
    Metadata { tracks: Vec<TrackInfo>, missing: Vec<String> },
    /// 0..=65535.
    Volume { volume: u32 },
    /// Another device took playback over: pause, keep the queue.
    Deactivated,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct IncomingRequest {
    /// Pass to reply(). Events (Volume, Deactivated, Metadata) need no reply.
    pub id: u64,
    pub from_device_id: String,
    pub command: ConnectRequest,
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Activity {
    Inactive,
    /// We PUT is_active; the server hasn't named us the active device yet.
    Pending { since_ms: u64 },
    /// The server has us as active device: another one in a cluster update took over.
    Confirmed { since_ms: u64 },
}

impl Activity {
    fn since_ms(self) -> Option<u64> {
        match self {
            Activity::Inactive => None,
            Activity::Pending { since_ms } | Activity::Confirmed { since_ms } => Some(since_ms),
        }
    }
}

type PendingReply = (mpsc::UnboundedSender<Reply>, bool);

struct Shared {
    remote: watch::Sender<RemoteState>,
    /// The local player, None = observe only (hidden, can't be picked).
    local: watch::Sender<Option<LocalPlayer>>,
    activity: Mutex<Activity>,
    requests_tx: mpsc::UnboundedSender<IncomingRequest>,
    requests_rx: tokio::sync::Mutex<mpsc::UnboundedReceiver<IncomingRequest>>,
    /// Request id -> dealer reply + whether success makes us the active device.
    pending: Mutex<HashMap<u64, PendingReply>>,
    next_id: AtomicU64,
    /// (message id, sender) of the last command, echoed in every PUT like spirc does.
    last_command: Mutex<(u32, String)>,
    /// The last PUT announced a player (vs. the hidden observer).
    put_as_player: AtomicBool,
    /// The last PUT had is_active.
    put_active: AtomicBool,
    /// PUT the current state again (cluster changed while we're active).
    kick: Notify,
}

impl Shared {
    fn push(self: &Arc<Self>, from: String, command: ConnectRequest, reply: Option<PendingReply>) {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        if let Some(r) = reply {
            self.pending.lock().unwrap().insert(id, r);
            let sh = self.clone();
            tokio::spawn(async move {
                tokio::time::sleep(REPLY_TIMEOUT).await;
                if let Some((tx, _)) = sh.pending.lock().unwrap().remove(&id) {
                    log::warn!("connect request {id}: no answer from the player");
                    let _ = tx.send(Reply::Failure);
                }
            });
        }
        let _ = self.requests_tx.send(IncomingRequest { id, from_device_id: from, command });
    }

    fn activate(&self) {
        {
            let mut a = self.activity.lock().unwrap();
            if self.local.borrow().is_none() {
                return;
            }
            match *a {
                Activity::Confirmed { .. } => return,
                // Not confirmed yet: claim it again (the PUT may have raced another device's).
                Activity::Pending { .. } => {}
                Activity::Inactive => *a = Activity::Pending { since_ms: now_ms() },
            }
        }
        log::info!("connect: becoming the active device");
        self.kick.notify_one();
    }

    fn deactivate(&self) {
        {
            let mut a = self.activity.lock().unwrap();
            if *a == Activity::Inactive {
                return;
            }
            *a = Activity::Inactive;
        }
        log::info!("connect: giving up playback");
        self.kick.notify_one();
    }

    /// Cluster from the server (push or PUT response): confirm our activation, or notice that
    /// another device took over.
    fn on_cluster(self: &Arc<Self>, c: &Cluster, me: &str) {
        let mut a = self.activity.lock().unwrap();
        match *a {
            Activity::Pending { since_ms } if c.active_device_id == me => {
                log::info!("connect: active device confirmed");
                *a = Activity::Confirmed { since_ms };
            }
            // Confirmed, or pending for a while: another device playing now took over (else a
            // pending activation that lost the race would never resolve and both would play).
            Activity::Confirmed { .. } | Activity::Pending { .. }
                if !c.active_device_id.is_empty()
                    && c.active_device_id != me
                    && match *a {
                        Activity::Pending { since_ms } => {
                            now_ms().saturating_sub(since_ms) > PENDING_GRACE_MS
                        }
                        _ => true,
                    } =>
            {
                *a = Activity::Inactive;
                drop(a);
                log::info!("connect: {} took playback over", c.active_device_id);
                self.push(c.active_device_id.clone(), ConnectRequest::Deactivated, None);
                self.kick.notify_one();
            }
            _ => {}
        }
    }
}

/// Owns the dealer task, the latest cluster state and the local player registration.
pub struct Hub {
    sh: Arc<Shared>,
    task: Mutex<Option<(String, JoinHandle<()>)>>,
}

impl Hub {
    pub fn new() -> Self {
        let (requests_tx, requests_rx) = mpsc::unbounded_channel();
        Self {
            sh: Arc::new(Shared {
                remote: watch::channel(RemoteState::default()).0,
                local: watch::channel(None).0,
                activity: Mutex::new(Activity::Inactive),
                requests_tx,
                requests_rx: tokio::sync::Mutex::new(requests_rx),
                pending: Mutex::new(HashMap::new()),
                next_id: AtomicU64::new(0),
                last_command: Mutex::new((0, String::new())),
                put_as_player: AtomicBool::new(false),
                put_active: AtomicBool::new(false),
                kick: Notify::new(),
            }),
            task: Mutex::new(None),
        }
    }

    /// Start the dealer task for session unless it already runs for it. Call inside the runtime.
    pub fn ensure(&self, session: &Session) {
        let mut task = self.task.lock().unwrap();
        let id = session.session_id();
        if let Some((sid, handle)) = task.as_ref() {
            if *sid == id && !handle.is_finished() {
                return;
            }
        }
        if let Some((_, old)) = task.take() {
            old.abort();
        }
        let sh = self.sh.clone();
        let s = session.clone();
        let handle = tokio::spawn(async move {
            if let Err(e) = run(&s, &sh).await {
                log::warn!("connect stopped: {e}");
                sh.remote.send_modify(|st| {
                    st.ready = false;
                    st.error = Some(e.to_string());
                    st.version += 1;
                });
            }
        });
        *task = Some((id, handle));
    }

    /// The remote state once its version is past after (or after timeout, whatever comes first).
    pub async fn wait(&self, after: u64, timeout: Duration) -> RemoteState {
        let mut rx = self.sh.remote.subscribe();
        let _ = tokio::time::timeout(timeout, rx.wait_for(|s| s.version > after)).await;
        rx.borrow().clone()
    }

    /// Attach/update (Some) or detach (None) the local player. Detaching gives up playback.
    pub fn set_player(&self, player: Option<LocalPlayer>) {
        if player.is_none() {
            *self.sh.activity.lock().unwrap() = Activity::Inactive;
        }
        self.sh.local.send_replace(player);
    }

    pub fn activate(&self) {
        self.sh.activate();
    }

    pub fn deactivate(&self) {
        self.sh.deactivate();
    }

    /// Next command for the local player, None after timeout.
    pub async fn next_request(&self, timeout: Duration) -> Option<IncomingRequest> {
        let mut rx = self.sh.requests_rx.lock().await;
        tokio::time::timeout(timeout, rx.recv()).await.ok().flatten()
    }

    /// Answer request id. A successful play/transfer makes this the active device.
    pub fn reply(&self, id: u64, ok: bool) {
        let Some((tx, activates)) = self.sh.pending.lock().unwrap().remove(&id) else {
            return;
        };
        let _ = tx.send(if ok { Reply::Success } else { Reply::Failure });
        if ok && activates {
            self.sh.activate();
        }
    }

    /// Become the active device and PUT that right away (before transferring away from here:
    /// the server builds the transfer from our last PUT).
    pub async fn activate_now(&self, session: &Session) -> Result<(), CoreError> {
        if self.sh.local.borrow().is_none() {
            return Err(CoreError::Network { msg: "no local player attached".into() });
        }
        {
            let mut a = self.sh.activity.lock().unwrap();
            if *a == Activity::Inactive {
                *a = Activity::Pending { since_ms: now_ms() };
            }
        }
        put(session, &self.sh, PutStateReason::PLAYER_STATE_CHANGED).await
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

async fn run(session: &Session, sh: &Arc<Shared>) -> Result<(), CoreError> {
    let dealer = session.dealer();
    let mut connection_ids = dealer.listen_for("hm://pusher/v1/connections/", |m: Message| {
        m.headers
            .get("Spotify-Connection-Id")
            .cloned()
            .ok_or_else(|| librespot_core::Error::invalid_argument("no Spotify-Connection-Id"))
    })?;
    let mut clusters =
        dealer.listen_for("hm://connect-state/v1/cluster", Message::from_raw::<ClusterUpdate>)?;
    let mut volumes = dealer.listen_for(
        "hm://connect-state/v1/connect/volume",
        Message::from_raw::<SetVolumeCommand>,
    )?;
    // One handler per uri per dealer: a restarted task on the same session can't register it
    // again. Carry on without commands then; the next session gets a fresh dealer.
    let mut commands: BoxStream<'static, RequestReply> =
        match dealer.handle_for("hm://connect-state/v1/player/command") {
            Ok(s) => s,
            Err(e) => {
                log::warn!("command handler: {e}");
                futures_util::stream::pending().boxed()
            }
        };
    // One dealer per session: start() consumes the builder, so a second task on the same
    // session just fails here and keeps the running dealer.
    if let Err(e) = dealer.start().await {
        log::debug!("dealer start: {e}");
    }
    let me = session.device_id().to_owned();
    // Bumped per connection id: the putter (re)registers.
    let (reg_tx, reg_rx) = watch::channel(0u64);
    // Commands run one at a time and in order, off this loop (loads resolve contexts).
    let (work_tx, mut work_rx) = mpsc::unbounded_channel::<RequestReply>();
    let _worker = AbortOnDrop({
        let (s, sh) = (session.clone(), sh.clone());
        tokio::spawn(async move {
            while let Some((req, reply)) = work_rx.recv().await {
                handle_command(&s, &sh, req, reply).await;
            }
        })
    });
    // PUTs run in their own task, one at a time: a slow one must not hold up commands and
    // cluster updates here.
    let _putter = AbortOnDrop(tokio::spawn(putter(session.clone(), sh.clone(), reg_rx)));
    let mut check = tokio::time::interval(Duration::from_secs(10));
    loop {
        tokio::select! {
            id = connection_ids.next() => match id {
                Some(Ok(id)) => {
                    session.set_connection_id(&id);
                    reg_tx.send_modify(|g| *g += 1);
                }
                Some(Err(e)) => log::warn!("connection id: {e}"),
                None => return Err(CoreError::Network { msg: "dealer closed".into() }),
            },
            upd = clusters.next() => match upd {
                Some(Ok(upd)) => if let Some(c) = upd.cluster.as_ref() {
                    publish(&sh.remote, c, &me);
                    sh.on_cluster(c, &me);
                    // Like spirc: another device changed the cluster while we're active, PUT our
                    // state again, else their view of our position drifts. Not for our own PUTs.
                    if c.active_device_id == me && upd.devices_that_changed.iter().any(|d| *d != me) {
                        sh.kick.notify_one();
                    }
                },
                Some(Err(e)) => log::warn!("cluster update: {e}"),
                None => return Err(CoreError::Network { msg: "dealer closed".into() }),
            },
            Some(rr) = commands.next() => { let _ = work_tx.send(rr); },
            vol = volumes.next() => match vol {
                Some(Ok(v)) if sh.local.borrow().is_some() => {
                    let volume = v.volume.clamp(0, 65535) as u32;
                    log::info!("connect: volume {volume}");
                    sh.local.send_if_modified(|l| match l {
                        Some(l) if l.volume != volume => { l.volume = volume; true }
                        _ => false,
                    });
                    sh.push(String::new(), ConnectRequest::Volume { volume }, None);
                }
                Some(Err(e)) => log::warn!("volume: {e}"),
                _ => {}
            },
            _ = check.tick() => if session.is_invalid() {
                return Err(CoreError::Network { msg: "session closed".into() });
            },
        }
    }
}

/// Registers on every new connection id, then PUTs the state on every local change or kick.
async fn putter(session: Session, sh: Arc<Shared>, mut reg: watch::Receiver<u64>) {
    let mut local_rx = sh.local.subscribe();
    let mut registered = false;
    let mut retry_register = false;
    loop {
        let register = tokio::select! {
            r = reg.changed() => { if r.is_err() { return; } true }
            r = local_rx.changed() => { if r.is_err() { return; } false }
            () = sh.kick.notified() => false,
            () = tokio::time::sleep(REGISTER_RETRY), if retry_register => true,
        };
        local_rx.borrow_and_update();
        if register {
            reg.borrow_and_update();
            // (Re)connecting while paused: don't claim playback back, another device may have
            // taken over meanwhile.
            if !sh.local.borrow().as_ref().is_some_and(|l| l.is_playing) {
                *sh.activity.lock().unwrap() = Activity::Inactive;
            }
            match put(&session, &sh, PutStateReason::NEW_DEVICE).await {
                Ok(()) => {
                    registered = true;
                    retry_register = false;
                    log::info!("connect registered (player: {})", sh.local.borrow().is_some());
                }
                Err(e) => {
                    retry_register = true;
                    log::warn!("connect register: {e}");
                }
            }
            continue;
        }
        if !registered {
            continue;
        }
        // Attaching/detaching the player changes the device itself.
        let player = sh.local.borrow().is_some();
        let reason = if player != sh.put_as_player.load(Ordering::Relaxed) {
            PutStateReason::NEW_DEVICE
        } else {
            PutStateReason::PLAYER_STATE_CHANGED
        };
        if let Err(e) = put(&session, &sh, reason).await {
            log::warn!("connect put state: {e}");
        }
    }
}

/// PUT our device + player state; the response is the cluster as the server sees it now.
async fn put(session: &Session, sh: &Arc<Shared>, reason: PutStateReason) -> Result<(), CoreError> {
    let req = state_request(session, sh, reason);
    let player = req.device.device_info.capabilities.can_be_player;
    // Giving up playback: tell the server explicitly, like spirc, else other clients can keep
    // showing this device as the one playing.
    if !req.is_active && sh.put_active.load(Ordering::Relaxed) {
        if let Err(e) = session.spclient().put_connect_state_inactive(false).await {
            log::warn!("connect inactive: {e}");
        }
    }
    let bytes = session.spclient().put_connect_state_request(&req).await?;
    sh.put_as_player.store(player, Ordering::Relaxed);
    sh.put_active.store(req.is_active, Ordering::Relaxed);
    let cluster = Cluster::parse_from_bytes(&bytes).map_err(CoreError::other)?;
    let me = session.device_id();
    publish(&sh.remote, &cluster, me);
    sh.on_cluster(&cluster, me);
    Ok(())
}

fn state_request(session: &Session, sh: &Shared, reason: PutStateReason) -> PutStateRequest {
    let local = sh.local.borrow().clone();
    let activity = *sh.activity.lock().unwrap();
    let (last_id, last_from) = sh.last_command.lock().unwrap().clone();
    let active = local.is_some() && activity != Activity::Inactive;
    let player_state = match &local {
        Some(l) if active => player_state(session, l),
        _ => PlayerState {
            session_id: session.session_id(),
            is_system_initiated: true,
            ..Default::default()
        },
    };
    PutStateRequest {
        member_type: EnumOrUnknown::new(MemberType::CONNECT_STATE),
        put_state_reason: EnumOrUnknown::new(reason),
        is_active: active,
        client_side_timestamp: now_ms(),
        started_playing_at: if active { activity.since_ms().unwrap_or_default() } else { 0 },
        last_command_message_id: last_id,
        last_command_sent_by_device_id: last_from,
        device: MessageField::some(Device {
            device_info: MessageField::some(device_info(session, local.as_ref())),
            player_state: MessageField::some(player_state),
            ..Default::default()
        }),
        ..Default::default()
    }
}

/// Player: listed and controllable, like spirc's. No player: hidden observer.
fn device_info(session: &Session, local: Option<&LocalPlayer>) -> DeviceInfo {
    let player = local.is_some();
    DeviceInfo {
        can_play: player,
        volume: local.map_or(65535, |l| l.volume.min(65535)),
        name: local.map_or_else(|| "Fastify".into(), |l| l.name.clone()),
        device_id: session.device_id().to_owned(),
        device_type: EnumOrUnknown::new(librespot_protocol::devices::DeviceType::SMARTPHONE),
        device_software_version: version::SEMVER.into(),
        spirc_version: version::SPOTIFY_SPIRC_VERSION.into(),
        client_id: session.client_id(),
        capabilities: MessageField::some(Capabilities {
            gaia_eq_connect_id: true,
            can_be_player: player,
            is_observable: true,
            is_controllable: player,
            hidden: !player,
            needs_full_player_state: player,
            supports_gzip_pushes: true,
            supports_command_request: true,
            supports_transfer_command: player,
            supports_set_options_command: player,
            supports_playlist_v2: player,
            command_acks: player,
            volume_steps: if player { 16 } else { 0 },
            supported_types: if player { vec!["audio/track".into()] } else { vec![] },
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn provided(t: &RemoteTrack) -> ProvidedTrack {
    let mut p = ProvidedTrack {
        uri: t.uri.clone(),
        uid: t.uid.clone(),
        provider: t.provider.clone(),
        ..Default::default()
    };
    if t.provider == "queue" {
        p.metadata.insert("is_queued".into(), "true".into());
    }
    p
}

fn player_state(session: &Session, l: &LocalPlayer) -> PlayerState {
    // Never leave the context empty: the other clients treat that as "nothing playing".
    let ctx = l
        .context_uri
        .clone()
        .or_else(|| l.track.as_ref().map(|t| t.uri.clone()))
        .unwrap_or_default();
    let prev: Vec<ProvidedTrack> = l.prev_tracks
        [l.prev_tracks.len().saturating_sub(MAX_PREV)..]
        .iter()
        .map(provided)
        .collect();
    let next: Vec<ProvidedTrack> = l.next_tracks.iter().take(MAX_NEXT).map(provided).collect();
    let mut revision = DefaultHasher::new();
    next.iter().for_each(|t| t.uid.hash(&mut revision));
    let playing = l.is_playing;
    PlayerState {
        timestamp: l.position_at_ms as i64,
        context_uri: ctx.clone(),
        context_url: format!("context://{ctx}"),
        play_origin: MessageField::some(PlayOrigin {
            feature_identifier: "fastify".into(),
            ..Default::default()
        }),
        track: l.track.as_ref().map(provided).into(),
        playback_speed: if playing { 1.0 } else { 0.0 },
        position_as_of_timestamp: l.position_ms as i64,
        duration: l.duration_ms as i64,
        // Like spirc: paused = playing + paused + buffering, else the desktop greys out play.
        is_playing: l.track.is_some(),
        is_paused: !playing,
        is_buffering: !playing || l.is_buffering,
        is_system_initiated: true,
        options: MessageField::some(ContextPlayerOptions {
            shuffling_context: l.shuffle,
            repeating_context: l.repeat_context,
            repeating_track: l.repeat_track,
            ..Default::default()
        }),
        prev_tracks: prev,
        next_tracks: next,
        queue_revision: revision.finish().to_string(),
        session_id: session.session_id(),
        ..Default::default()
    }
}

// ---- Incoming commands ----

async fn handle_command(
    session: &Session,
    sh: &Arc<Shared>,
    req: Request,
    reply: mpsc::UnboundedSender<Reply>,
) {
    *sh.last_command.lock().unwrap() = (req.message_id, req.sent_by_device_id.clone());
    let from = req.sent_by_device_id.clone();
    log::info!("connect command {} from {from}", req.command);
    if sh.local.borrow().is_none() {
        log::warn!("connect command without a local player");
        let _ = reply.send(Reply::Failure);
        return;
    }
    match to_request(session, req.command).await {
        Ok(Some((cmd, activates, stubs))) => {
            sh.push(from.clone(), cmd, Some((reply, activates)));
            if !stubs.is_empty() {
                tokio::spawn(fill_stubs(session.clone(), sh.clone(), from, stubs));
            }
        }
        Ok(None) => {
            let _ = reply.send(Reply::Success);
        }
        Err(e) => {
            log::warn!("connect command failed: {e}");
            let _ = reply.send(Reply::Failure);
        }
    }
}

/// Ids a Load sent as stubs: hydrate them in batches and send their metadata after the Load.
async fn fill_stubs(session: Session, sh: Arc<Shared>, from: String, ids: Vec<String>) {
    for chunk in ids.chunks(FILL_BATCH) {
        match library::hydrate_tracks(&session, chunk).await {
            Ok(tracks) => {
                let found: std::collections::HashSet<&str> =
                    tracks.iter().map(|t| t.id.as_str()).collect();
                let missing = chunk.iter().filter(|id| !found.contains(id.as_str())).cloned().collect();
                sh.push(from.clone(), ConnectRequest::Metadata { tracks, missing }, None);
            }
            Err(e) => log::warn!("connect: metadata for {} queued tracks: {e}", chunk.len()),
        }
    }
}

/// Dealer command -> request for the player, whether success makes us the active device, and
/// stub ids to fill in afterwards (fill_stubs). None: nothing for the player to do
/// (acknowledged right away).
async fn to_request(
    session: &Session,
    command: Command,
) -> Result<Option<(ConnectRequest, bool, Vec<String>)>, CoreError> {
    use ConnectRequest as R;
    let none = |shuffle, repeat_context, repeat_track| R::SetOptions { shuffle, repeat_context, repeat_track };
    let r = match command {
        Command::Transfer(t) => {
            let data = t.data.ok_or_else(|| CoreError::NotFound { msg: "transfer without data".into() })?;
            let (load, stubs) = load_from_transfer(session, data).await?;
            return Ok(Some((load, true, stubs)));
        }
        Command::Play(p) => {
            let (load, stubs) = load_from_play(session, *p).await?;
            return Ok(Some((load, true, stubs)));
        }
        Command::Pause(_) => (R::Pause, false),
        Command::Resume(_) => (R::Resume, true),
        Command::SeekTo(s) => (R::SeekTo { position_ms: s.value as u64 }, false),
        Command::SetShufflingContext(v) => (none(Some(v.value), None, None), false),
        Command::SetRepeatingContext(v) => (none(None, Some(v.value), None), false),
        Command::SetRepeatingTrack(v) => (none(None, None, Some(v.value)), false),
        Command::SetOptions(o) => {
            (none(o.shuffling_context, o.repeating_context, o.repeating_track), false)
        }
        Command::AddToQueue(a) => {
            let id = library::id_from_uri(a.track.uri.as_str(), "track")
                .ok_or_else(|| CoreError::NotFound { msg: format!("can't queue {}", a.track.uri) })?
                .to_owned();
            let track = library::hydrate_tracks(session, &[id.clone()])
                .await?
                .pop()
                .ok_or_else(|| CoreError::NotFound { msg: format!("track {id}") })?;
            (R::AddToQueue { track }, false)
        }
        Command::SetQueue(q) => {
            (R::SetQueue { next: q.next_tracks.iter().map(remote_track).collect() }, false)
        }
        Command::SkipNext(s) => (R::SkipNext { track: s.track.as_ref().map(remote_track) }, false),
        Command::SkipPrev(_) => (R::SkipPrev, false),
        Command::UpdateContext(_) => return Ok(None),
        Command::Unknown(v) => {
            return Err(CoreError::NotFound { msg: format!("unsupported command {v}") });
        }
    };
    Ok(Some((r.0, r.1, Vec::new())))
}

fn context_uri_of(ctx: &librespot_protocol::context::Context) -> Option<String> {
    Some(ctx.uri().to_owned()).filter(|u| !u.is_empty() && u != "-")
}

/// Track ids of a context: inline pages when complete, else resolved by uri.
async fn context_ids(session: &Session, ctx: &librespot_protocol::context::Context) -> Vec<String> {
    let mut ids = Vec::new();
    for page in &ctx.pages {
        library::page_track_ids(&page.tracks, &mut ids);
    }
    let partial = ctx
        .pages
        .iter()
        .any(|p| p.has_next_page_url() || (p.tracks.is_empty() && p.has_page_url()));
    if ids.is_empty() || partial {
        if let Some(uri) = context_uri_of(ctx) {
            // Autoplay sessions name a station: resolve the context it's based on.
            let base = uri.replace("station:", "");
            for u in [uri.as_str(), base.as_str()] {
                match library::context_track_ids(session, u).await {
                    Ok(v) if !v.is_empty() => return v,
                    Ok(_) => log::warn!("context {u}: no tracks"),
                    Err(e) => log::warn!("context {u}: {e}"),
                }
            }
        }
    }
    ids
}

fn stub(id: &str) -> TrackInfo {
    TrackInfo {
        id: id.to_owned(),
        title: String::new(),
        artists: String::new(),
        album: String::new(),
        image_url: None,
        duration_ms: 0,
        artist_refs: Vec::new(),
        album_id: None,
    }
}

/// Hydrate ids and find the start: start_id (put in front when not in the list), else -1.
/// Big contexts: only a window around the start is hydrated now (the sender waits for the
/// reply), the rest come back as stubs (returned ids) for fill_stubs.
async fn hydrate_with_start(
    session: &Session,
    mut ids: Vec<String>,
    start_id: Option<String>,
) -> Result<(Vec<TrackInfo>, i32, Vec<String>), CoreError> {
    if let Some(id) = &start_id {
        if !ids.contains(id) {
            ids.insert(0, id.clone());
        }
    }
    let at = start_id.as_ref().and_then(|id| ids.iter().position(|i| i == id)).unwrap_or(0);
    let window = if ids.len() <= HYDRATE_ALL_UP_TO {
        0..ids.len()
    } else {
        at.saturating_sub(HYDRATE_BEFORE)..(at + HYDRATE_AFTER).min(ids.len())
    };
    let hydrated = library::hydrate_tracks(session, &ids[window.clone()]).await?;
    let mut by_id: HashMap<String, TrackInfo> =
        hydrated.into_iter().map(|t| (t.id.clone(), t)).collect();
    let mut stubs = Vec::new();
    let mut tracks = Vec::with_capacity(ids.len());
    for (i, id) in ids.iter().enumerate() {
        if window.contains(&i) {
            // No metadata: unavailable, drop it.
            if let Some(t) = by_id.remove(id) {
                tracks.push(t);
            }
        } else {
            stubs.push(id.clone());
            tracks.push(stub(id));
        }
    }
    if tracks.is_empty() {
        return Err(CoreError::NotFound { msg: "nothing playable in this context".into() });
    }
    let start = start_id.and_then(|id| tracks.iter().position(|t| t.id == id));
    Ok((tracks, start.map_or(-1, |i| i as i32), stubs))
}

fn track_id(uri: &str) -> Option<String> {
    library::id_from_uri(uri.split('?').next().unwrap_or(""), "track").map(str::to_owned)
}

async fn load_from_play(
    session: &Session,
    p: PlayCommand,
) -> Result<(ConnectRequest, Vec<String>), CoreError> {
    let ids = context_ids(session, &p.context).await;
    let skip = p.options.skip_to.clone().unwrap_or_default();
    // skip_to names the start by uri, uid (of a context page track) or index; resolve it to an
    // id before hydrating, which drops unavailable tracks and so shifts indices.
    let start_id = skip
        .track_uri
        .as_deref()
        .and_then(track_id)
        .or_else(|| {
            let uid = skip.track_uid.as_deref().filter(|u| !u.is_empty())?;
            p.context.pages.iter().flat_map(|pg| pg.tracks.iter()).find(|t| t.uid() == uid).and_then(|t| track_id(t.uri()))
        })
        .or_else(|| skip.track_index.and_then(|i| ids.get(i as usize).cloned()));
    let (tracks, start_index, stubs) = hydrate_with_start(session, ids, start_id).await?;
    let over = p.options.player_options_override.as_ref();
    Ok((ConnectRequest::Load {
        tracks,
        start_index,
        position_ms: p.options.seek_to.unwrap_or_default() as u64,
        paused: p.options.initially_paused.unwrap_or_default(),
        context_uri: context_uri_of(&p.context),
        shuffle: over.and_then(|o| o.shuffling_context),
        repeat_context: over.and_then(|o| o.repeating_context),
        repeat_track: over.and_then(|o| o.repeating_track),
        queued: Vec::new(),
    }, stubs))
}

async fn load_from_transfer(
    session: &Session,
    t: TransferState,
) -> Result<(ConnectRequest, Vec<String>), CoreError> {
    let ctx = t.current_session.context.clone().unwrap_or_default();
    let playing_queue = t.queue.is_playing_queue();
    let current = if playing_queue { t.queue.tracks.first() } else { t.playback.current_track.as_ref() };
    let start_id = current.and_then(|c| track_id(c.uri()));
    log::info!(
        "transfer: context {:?}, track {:?}, {} queued, paused {}",
        ctx.uri, start_id, t.queue.tracks.len(), t.playback.is_paused()
    );
    let ids = context_ids(session, &ctx).await;
    let (tracks, start_index, stubs) = hydrate_with_start(session, ids, start_id).await?;
    let queued_ids: Vec<String> = t
        .queue
        .tracks
        .iter()
        .skip(usize::from(playing_queue))
        .filter_map(|q| track_id(q.uri()))
        .collect();
    let queued = if queued_ids.is_empty() {
        Vec::new()
    } else {
        library::hydrate_tracks(session, &queued_ids).await.unwrap_or_default()
    };
    let paused = t.playback.is_paused();
    let mut position = t.playback.position_as_of_timestamp().max(0) as u64;
    if !paused && position > 0 && t.playback.timestamp() > 0 {
        position += now_ms().saturating_sub(t.playback.timestamp() as u64);
    }
    let opts = t.options.as_ref();
    Ok((ConnectRequest::Load {
        tracks,
        start_index,
        position_ms: position,
        paused,
        context_uri: context_uri_of(&ctx),
        shuffle: Some(opts.is_some_and(|o| o.shuffling_context())),
        repeat_context: Some(opts.is_some_and(|o| o.repeating_context())),
        repeat_track: Some(opts.is_some_and(|o| o.repeating_track())),
        queued,
    }, stubs))
}

// ---- Cluster -> RemoteState, remote control ----

fn remote_track(t: &ProvidedTrack) -> RemoteTrack {
    RemoteTrack { uri: t.uri.clone(), uid: t.uid.clone(), provider: t.provider.clone() }
}

fn publish(tx: &watch::Sender<RemoteState>, c: &Cluster, me: &str) {
    let mut devices: Vec<RemoteDevice> = c
        .device
        .iter()
        .filter(|(id, d)| id.as_str() != me && !d.capabilities.hidden)
        .map(|(id, d)| RemoteDevice {
            id: id.clone(),
            name: d.name.clone(),
            kind: format!("{:?}", d.device_type.enum_value_or_default()).to_lowercase(),
            volume: d.volume,
            can_play: d.can_play,
        })
        .collect();
    devices.sort_by(|a, b| a.name.cmp(&b.name));
    let active = Some(c.active_device_id.clone()).filter(|a| !a.is_empty() && a != me);
    let mut next = RemoteState {
        ready: true,
        devices,
        active_device_id: active.clone(),
        ..Default::default()
    };
    if let (Some(_), Some(p)) = (active, c.player_state.as_ref()) {
        let playing = p.is_playing && !p.is_paused;
        // Position is reported at p.timestamp; the server stamps the push with its own clock.
        let mut pos = p.position_as_of_timestamp.max(0) as u64;
        if playing && p.timestamp > 0 && c.server_timestamp_ms > p.timestamp {
            pos += ((c.server_timestamp_ms - p.timestamp) as f64 * p.playback_speed.max(0.0)) as u64;
        }
        let duration = p.duration.max(0) as u64;
        let opts = p.options.as_ref();
        next.track = p.track.as_ref().map(remote_track);
        next.is_playing = playing;
        next.position_ms = if duration > 0 { pos.min(duration) } else { pos };
        next.position_at_ms = now_ms();
        next.duration_ms = duration;
        next.shuffle = opts.is_some_and(|o| o.shuffling_context);
        next.repeat_context = opts.is_some_and(|o| o.repeating_context);
        next.repeat_track = opts.is_some_and(|o| o.repeating_track);
        next.context_uri = Some(p.context_uri.clone()).filter(|u| !u.is_empty());
        // Unfiltered (delimiters, episodes too): set_queue sends them back as they were.
        next.prev_tracks = p.prev_tracks.iter().map(remote_track).collect();
        next.next_tracks = p.next_tracks.iter().map(remote_track).collect();
        next.queue_revision = p.queue_revision.clone();
    }
    tx.send_modify(|st| {
        next.version = st.version + 1;
        *st = next;
    });
}

fn track_json(t: &RemoteTrack) -> Value {
    let mut v = json!({ "uri": t.uri, "uid": t.uid, "provider": t.provider });
    if t.provider == "queue" {
        v["metadata"] = json!({ "is_queued": "true" });
    }
    v
}

fn command_json(cmd: &RemoteCommand) -> Value {
    let mut c = match cmd {
        RemoteCommand::Pause => json!({ "endpoint": "pause" }),
        RemoteCommand::Resume => json!({ "endpoint": "resume" }),
        RemoteCommand::SkipNext => json!({ "endpoint": "skip_next" }),
        RemoteCommand::SkipPrev => json!({ "endpoint": "skip_prev" }),
        RemoteCommand::SeekTo { position_ms } => {
            json!({ "endpoint": "seek_to", "value": position_ms, "position": 0 })
        }
        RemoteCommand::SetShuffle { on } => {
            json!({ "endpoint": "set_shuffling_context", "value": on })
        }
        RemoteCommand::SetRepeat { context, track } => json!({
            "endpoint": "set_options", "repeating_context": context, "repeating_track": track,
        }),
        RemoteCommand::SkipTo { track } => {
            json!({ "endpoint": "skip_next", "track": track_json(track) })
        }
        RemoteCommand::AddToQueue { track_id } => json!({
            "endpoint": "add_to_queue",
            "track": {
                "uri": format!("spotify:track:{track_id}"),
                "metadata": { "is_queued": "true" },
                "provider": "queue",
            },
        }),
        RemoteCommand::SetQueue { next, prev, revision } => json!({
            "endpoint": "set_queue",
            "next_tracks": next.iter().map(track_json).collect::<Vec<_>>(),
            "prev_tracks": prev.iter().map(track_json).collect::<Vec<_>>(),
            "queue_revision": revision,
        }),
        RemoteCommand::Play { context_uri, track_ids, start_index, position_ms, shuffle } => {
            let uris: Vec<String> =
                track_ids.iter().map(|id| format!("spotify:track:{id}")).collect();
            let start = uris.get(*start_index as usize).cloned();
            let context = match context_uri {
                Some(uri) => json!({ "uri": uri, "url": format!("context://{uri}") }),
                // No context: hand the list over as one page, named after the start track.
                None => json!({
                    "uri": start.clone().unwrap_or_default(),
                    "pages": [{ "tracks": uris.iter().map(|u| json!({ "uri": u })).collect::<Vec<_>>() }],
                }),
            };
            let mut options = json!({});
            if let Some(uri) = start {
                options["skip_to"] = json!({ "track_uri": uri, "track_index": start_index });
            }
            if *position_ms > 0 {
                options["seek_to"] = json!(position_ms);
            }
            if let Some(on) = shuffle {
                options["player_options_override"] = json!({ "shuffling_context": on });
            }
            json!({
                "endpoint": "play",
                "context": context,
                "play_origin": { "feature_identifier": "fastify" },
                "options": options,
            })
        }
        RemoteCommand::Volume { .. } => unreachable!("volume has its own endpoint"),
    };
    c["logging_params"] = json!({
        "command_id": uuid::Uuid::new_v4().simple().to_string(),
        "command_initiated_time": now_ms(),
    });
    json!({ "command": c })
}

fn json_headers() -> HeaderMap {
    let mut h = HeaderMap::new();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json"));
    h
}

/// Send cmd to device to.
pub async fn command(session: &Session, to: &str, cmd: &RemoteCommand) -> Result<(), CoreError> {
    let from = session.device_id().to_owned();
    let (method, path, body) = match cmd {
        RemoteCommand::Volume { volume } => (
            Method::PUT,
            format!("/connect-state/v1/connect/volume/from/{from}/to/{to}"),
            json!({ "volume": (*volume).min(65535) }),
        ),
        _ => (
            Method::POST,
            format!("/connect-state/v1/player/command/from/{from}/to/{to}"),
            command_json(cmd),
        ),
    };
    let body = serde_json::to_vec(&body).map_err(CoreError::other)?;
    log::info!("connect {path}: {}", String::from_utf8_lossy(&body));
    session.spclient().request(&method, &path, Some(json_headers()), Some(&body)).await?;
    Ok(())
}

/// Move playback from device from to device to.
pub async fn transfer(session: &Session, from: &str, to: &str) -> Result<(), CoreError> {
    let body = serde_json::to_vec(&json!({
        "transfer_options": { "restore_paused": "restore" },
        "command_id": uuid::Uuid::new_v4().simple().to_string(),
    }))
    .map_err(CoreError::other)?;
    let path = format!("/connect-state/v1/connect/transfer/from/{from}/to/{to}");
    log::info!("connect {path}");
    session.spclient().request(&Method::POST, &path, Some(json_headers()), Some(&body)).await?;
    Ok(())
}
