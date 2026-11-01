//! Spotify Connect remote control: watch the account's device cluster over the dealer websocket
//! and send player commands to another device (e.g. the desktop app).
//!
//! Fastify registers as a hidden, observe-only Connect member (can_be_player false), so it shows
//! up nowhere and can't be picked as a playback target yet.

use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::StreamExt;
use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::dealer::protocol::Message;
use librespot_core::{Session, version};
use librespot_protocol::connect::{
    Capabilities, Cluster, ClusterUpdate, Device, DeviceInfo, MemberType, PutStateReason,
    PutStateRequest,
};
use librespot_protocol::player::{PlayerState, ProvidedTrack};
use protobuf::{EnumOrUnknown, Message as _, MessageField};
use serde_json::{Value, json};
use tokio::sync::watch;
use tokio::task::JoinHandle;

use crate::CoreError;

const DEVICE_NAME: &str = "Fastify";

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

/// Owns the observer task and the latest cluster state.
pub struct Hub {
    tx: watch::Sender<RemoteState>,
    task: Mutex<Option<(String, JoinHandle<()>)>>,
}

impl Hub {
    pub fn new() -> Self {
        Self { tx: watch::channel(RemoteState::default()).0, task: Mutex::new(None) }
    }

    /// Start the observer for session unless it already runs for it. Call inside the runtime.
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
        let tx = self.tx.clone();
        let s = session.clone();
        let handle = tokio::spawn(async move {
            if let Err(e) = observe(&s, &tx).await {
                log::warn!("connect observer stopped: {e}");
                tx.send_modify(|st| {
                    st.ready = false;
                    st.error = Some(e.to_string());
                    st.version += 1;
                });
            }
        });
        *task = Some((id, handle));
    }

    /// The state once its version is past after (or after timeout, whatever comes first).
    pub async fn wait(&self, after: u64, timeout: Duration) -> RemoteState {
        let mut rx = self.tx.subscribe();
        let _ = tokio::time::timeout(timeout, rx.wait_for(|s| s.version > after)).await;
        rx.borrow().clone()
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

async fn observe(session: &Session, tx: &watch::Sender<RemoteState>) -> Result<(), CoreError> {
    let dealer = session.dealer();
    let mut connection_ids = dealer.listen_for("hm://pusher/v1/connections/", |m: Message| {
        m.headers
            .get("Spotify-Connection-Id")
            .cloned()
            .ok_or_else(|| librespot_core::Error::invalid_argument("no Spotify-Connection-Id"))
    })?;
    let mut clusters =
        dealer.listen_for("hm://connect-state/v1/cluster", Message::from_raw::<ClusterUpdate>)?;
    // One dealer per session: start() consumes the builder, so a second observer on the same
    // session (after an abort) just fails here and keeps the running dealer.
    if let Err(e) = dealer.start().await {
        log::debug!("dealer start: {e}");
    }
    let me = session.device_id().to_owned();
    let mut check = tokio::time::interval(Duration::from_secs(10));
    loop {
        tokio::select! {
            id = connection_ids.next() => match id {
                Some(Ok(id)) => {
                    session.set_connection_id(&id);
                    let bytes = session.spclient().put_connect_state_request(&observer_state(session)).await?;
                    let cluster = Cluster::parse_from_bytes(&bytes).map_err(CoreError::other)?;
                    log::info!("connect observer registered, {} devices", cluster.device.len());
                    publish(tx, &cluster, &me);
                }
                Some(Err(e)) => log::warn!("connection id: {e}"),
                None => return Err(CoreError::Network { msg: "dealer closed".into() }),
            },
            upd = clusters.next() => match upd {
                Some(Ok(upd)) => if let Some(c) = upd.cluster.as_ref() { publish(tx, c, &me) },
                Some(Err(e)) => log::warn!("cluster update: {e}"),
                None => return Err(CoreError::Network { msg: "dealer closed".into() }),
            },
            _ = check.tick() => if session.is_invalid() {
                return Err(CoreError::Network { msg: "session closed".into() });
            },
        }
    }
}

fn observer_state(session: &Session) -> PutStateRequest {
    PutStateRequest {
        member_type: EnumOrUnknown::new(MemberType::CONNECT_STATE),
        put_state_reason: EnumOrUnknown::new(PutStateReason::NEW_DEVICE),
        is_active: false,
        client_side_timestamp: now_ms(),
        device: MessageField::some(Device {
            device_info: MessageField::some(DeviceInfo {
                can_play: false,
                volume: 65535,
                name: DEVICE_NAME.into(),
                device_id: session.device_id().to_owned(),
                device_type: EnumOrUnknown::new(librespot_protocol::devices::DeviceType::SMARTPHONE),
                device_software_version: version::SEMVER.into(),
                spirc_version: version::SPOTIFY_SPIRC_VERSION.into(),
                client_id: session.client_id(),
                capabilities: MessageField::some(Capabilities {
                    gaia_eq_connect_id: true,
                    can_be_player: false,
                    is_observable: true,
                    is_controllable: false,
                    hidden: true,
                    supports_gzip_pushes: true,
                    supports_command_request: true,
                    ..Default::default()
                }),
                ..Default::default()
            }),
            player_state: MessageField::some(PlayerState {
                session_id: session.session_id(),
                ..Default::default()
            }),
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn remote_track(t: &ProvidedTrack) -> RemoteTrack {
    RemoteTrack { uri: t.uri.clone(), uid: t.uid.clone(), provider: t.provider.clone() }
}

fn playable(t: &&ProvidedTrack) -> bool {
    t.uri.starts_with("spotify:track:")
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
        next.prev_tracks = p.prev_tracks.iter().filter(playable).map(remote_track).collect();
        next.next_tracks = p.next_tracks.iter().filter(playable).map(remote_track).collect();
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
    session.spclient().request(&Method::POST, &path, Some(json_headers()), Some(&body)).await?;
    Ok(())
}
