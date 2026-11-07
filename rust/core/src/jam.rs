//! Spotify Jam (social-connect) over the authenticated librespot session.
//! Private endpoints (desktop/mobile clients, captured 2026-10/11); keep requests isolated here.
//!
//! - A Jam is bound to a Connect device: every call names ours (local_device_id) and its dealer
//!   connection (x-spotify-connection-id). Host: our device plays, guests' commands reach it as
//!   normal Connect commands. Guest listening here: social-connect transfers the Jam to us and
//!   relays every change; our own skips/queue edits go to the Jam ([command]), not the player.
//! - Changes arrive as dealer pushes hm://social-connect/v2/session_update {reason, session}.
//! - POST/PUT without a body gets 411 from the frontend: always send `{}`.

use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::{Session, error::ErrorKind};
use serde_json::{Value, json};

use crate::CoreError;
use crate::connect::{RemoteCommand, command_json};

const SHARE_BASE: &str = "https://open.spotify.com/socialsession/";

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct JamMember {
    pub id: String,
    pub name: String,
    pub image_url: String,
    pub is_host: bool,
    pub is_current_user: bool,
    pub is_listening: bool,
    pub is_controlling: bool,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct JamInfo {
    pub session_id: String,
    /// Shareable link (spotify.link short link when the dispenser answers).
    pub invite_url: String,
    pub is_host: bool,
    pub host_name: String,
    pub queue_only_mode: bool,
    pub max_member_count: u32,
    /// This account plays the Jam on its own device (LISTEN_AND_CONTROL), else it controls
    /// the host's speaker (CONTROL).
    pub listening_here: bool,
    pub host_device_id: String,
    pub host_device_name: String,
    pub members: Vec<JamMember>,
}

/// Session info before joining (GET sessions/info/<token>).
#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct JamPreview {
    pub token: String,
    pub host_name: String,
    pub members: Vec<JamMember>,
}

fn valid_segment(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
}

fn segment<'a>(value: &'a str, what: &str) -> Result<&'a str, CoreError> {
    valid_segment(value)
        .then_some(value)
        .ok_or_else(|| CoreError::other(format!("invalid Jam {what}")))
}

/// Join token from an open.spotify.com invite (any locale path), spotify:socialsession:<t>,
/// hm://social-connect/v2/sessions/join/<t> or the bare token. Short links (spotify.link) are
/// resolved by the app first.
pub fn parse_join_token(input: &str) -> Result<String, CoreError> {
    let bad = || CoreError::other("invalid Jam invite link or token");
    let input = input.trim();
    if input.starts_with("https://spotify.link/") || input.starts_with("https://spotify.app.link/") {
        return Err(CoreError::other("short link not resolved"));
    }
    let token = if let Some(t) = input.strip_prefix("spotify:socialsession:") {
        t
    } else if let Some(t) = input.strip_prefix("hm://social-connect/v2/sessions/join/") {
        t
    } else if input.starts_with("https://open.spotify.com/") {
        input
            .split_once("/socialsession/")
            .map(|(_, tail)| tail.split(['?', '#', '/']).next().unwrap_or_default())
            .ok_or_else(bad)?
    } else if input.contains("://") || input.contains('/') {
        return Err(bad());
    } else {
        input
    };
    if !valid_segment(token) {
        return Err(bad());
    }
    Ok(token.to_owned())
}

fn json_body(body: &[u8]) -> Result<Value, CoreError> {
    if body.is_empty() {
        return Ok(Value::Null);
    }
    if body.len() > 1_000_000 {
        return Err(CoreError::other("Jam response too large"));
    }
    serde_json::from_slice(body).map_err(|e| CoreError::other(format!("Jam response: {e}")))
}

/// None: 404 on a GET (no session).
async fn request(
    session: &Session,
    method: Method,
    path: &str,
    body: Option<Value>,
) -> Result<Option<Value>, CoreError> {
    let mut headers = HeaderMap::new();
    headers.insert(header::ACCEPT, HeaderValue::from_static("application/json"));
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json"));
    if let Ok(id) = HeaderValue::from_str(&session.connection_id()) {
        if !id.is_empty() {
            headers.insert("x-spotify-connection-id", id);
        }
    }
    let body = match (body, &method) {
        (Some(v), _) => Some(serde_json::to_vec(&v).map_err(CoreError::other)?),
        (None, &Method::GET) => None,
        (None, _) => Some(b"{}".to_vec()),
    };
    log::info!("jam {method} {}", path.split('?').next().unwrap_or(path));
    match tokio::time::timeout(
        std::time::Duration::from_secs(15),
        session.spclient().request(&method, path, Some(headers), body.as_deref()),
    )
    .await
    {
        Err(_) => Err(CoreError::Network { msg: format!("Jam {method} timed out") }),
        Ok(Ok(bytes)) => Ok(Some(json_body(&bytes)?)),
        Ok(Err(e)) if e.kind == ErrorKind::NotFound && method == Method::GET => Ok(None),
        Ok(Err(e)) => {
            log::warn!("jam {method} {path}: {e}");
            Err(CoreError::from(e))
        }
    }
}

fn str_of<'a>(v: &'a Value, keys: &[&str]) -> Option<&'a str> {
    keys.iter().find_map(|k| v.get(*k).and_then(Value::as_str).filter(|s| !s.trim().is_empty()))
}

fn bool_of(v: &Value, keys: &[&str]) -> Option<bool> {
    keys.iter().find_map(|k| v.get(*k)).and_then(Value::as_bool)
}

/// Spotify sends some avatars over http.
fn image(member: &Value) -> String {
    str_of(member, &["image_url", "imageUrl", "large_image_url", "largeImageUrl"])
        .map(|u| u.replacen("http://", "https://", 1))
        .filter(|u| u.starts_with("https://i.scdn.co/") || u.contains(".fbcdn.net/") || u.contains(".fbsbx.com/"))
        .unwrap_or_default()
}

/// REST answers are snake_case, dealer pushes camelCase; read both.
fn members(value: &Value, owner_id: &str) -> Vec<JamMember> {
    ["session_members", "sessionMembers"]
        .iter()
        .find_map(|k| value.get(*k))
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .take(100)
        .map(|member| {
            let id = str_of(member, &["id"]).unwrap_or_default();
            let name = str_of(member, &["display_name", "displayName", "username"]).unwrap_or("Spotify user");
            JamMember {
                id: id.to_owned(),
                name: name.chars().take(200).collect(),
                image_url: image(member),
                is_host: !owner_id.is_empty() && id == owner_id,
                is_current_user: bool_of(member, &["is_current_user", "isCurrentUser"]).unwrap_or(false),
                is_listening: bool_of(member, &["is_listening", "isListening"]).unwrap_or(true),
                is_controlling: bool_of(member, &["is_controlling", "isControlling"]).unwrap_or(false),
            }
        })
        .collect()
}

pub(crate) fn parse(value: &Value) -> Option<JamInfo> {
    if value.is_null() || bool_of(value, &["active"]) == Some(false) {
        return None;
    }
    // Some regions answer {} instead of 404 when there's no Jam.
    let id = str_of(value, &["session_id", "sessionId"]).filter(|id| valid_segment(id))?;
    let owner_id = str_of(value, &["session_owner_id", "sessionOwnerId"]).unwrap_or_default();
    let token = str_of(value, &["join_session_token", "joinSessionToken"])
        .or_else(|| str_of(value, &["join_session_uri", "joinSessionUri"]).and_then(|u| u.strip_prefix("spotify:socialsession:")))
        .filter(|t| valid_segment(t));
    let members = members(value, owner_id);
    let me = members.iter().find(|m| m.is_current_user);
    let is_host = bool_of(value, &["is_session_owner", "isSessionOwner"]).unwrap_or(false);
    // Our own playbackControl: LISTEN_AND_CONTROL = plays here. Host always plays here.
    let control = ["session_members", "sessionMembers"]
        .iter()
        .find_map(|k| value.get(*k))
        .and_then(Value::as_array)
        .and_then(|a| a.iter().find(|m| bool_of(m, &["is_current_user", "isCurrentUser"]) == Some(true)))
        .and_then(|m| str_of(m, &["playbackControl", "playback_control"]));
    let listening_here = is_host
        || match control {
            Some(c) => c.eq_ignore_ascii_case("LISTEN_AND_CONTROL"),
            None => me.is_some_and(|m| m.is_listening),
        };
    let host_info = value.get("host_device_info").or_else(|| value.get("hostDeviceInfo"));
    Some(JamInfo {
        session_id: id.to_owned(),
        invite_url: token.map(|t| format!("{SHARE_BASE}{t}")).unwrap_or_default(),
        is_host,
        host_name: members.iter().find(|m| m.is_host).map(|m| m.name.clone()).unwrap_or_default(),
        queue_only_mode: bool_of(value, &["queue_only_mode", "queueOnlyMode"]).unwrap_or(false),
        max_member_count: value.get("maxMemberCount").and_then(Value::as_u64).unwrap_or(0).min(u32::MAX as u64) as u32,
        listening_here,
        host_device_id: str_of(value, &["hostActiveDeviceId", "host_active_device_id"]).unwrap_or_default().to_owned(),
        host_device_name: host_info
            .and_then(|h| str_of(h, &["device_name", "deviceName"]))
            .unwrap_or_default()
            .to_owned(),
        members,
    })
}

/// Reason of a session_update push (YOU_WERE_KICKED, SESSION_DELETED, USER_JOINED, ...).
pub(crate) fn push_reason(value: &Value) -> String {
    str_of(value, &["reason"]).unwrap_or_default().chars().take(64).collect()
}

fn device(session: &Session) -> Result<String, CoreError> {
    let id = session.device_id();
    segment(&id, "device id").map(str::to_owned)
}

async fn current(session: &Session) -> Result<Option<JamInfo>, CoreError> {
    let device = device(session)?;
    let path = format!("/social-connect/v2/sessions/current?local_device_id={device}");
    Ok(request(session, Method::GET, &path, None).await?.as_ref().and_then(parse))
}

/// Short spotify.link invite; keeps open.spotify.com/socialsession/<token> when it fails.
async fn with_share_link(session: &Session, mut jam: JamInfo) -> JamInfo {
    let Some(token) = jam.invite_url.strip_prefix(SHARE_BASE).map(str::to_owned) else { return jam };
    let body = json!({
        "spotify_uri": format!("spotify:socialsession:{token}"),
        "custom_data": [{"key":"ssp","value":"1"},{"key":"app_destination","value":"socialsession"}],
        "link_preview": {"image_url": format!("https://shareables.scdn.co/publish/socialsession/{token}")},
        "utm_parameters": {"utm_medium":"share-link","utm_source":"share-options-sheet"}
    });
    match request(session, Method::POST, "/url-dispenser/v1/generate-url", Some(body)).await {
        Ok(Some(result)) => {
            if let Some(url) = str_of(&result, &["shareable_url"]) {
                if let Ok(parsed) = http::Uri::try_from(url) {
                    let host = parsed.host().unwrap_or_default();
                    if parsed.scheme_str() == Some("https") && matches!(host, "spotify.link" | "open.spotify.com") {
                        jam.invite_url = url.to_owned();
                    }
                }
            }
        }
        Ok(None) => {}
        Err(e) => log::warn!("jam share link: {e}"),
    }
    jam
}

pub async fn get(session: &Session) -> Result<Option<JamInfo>, CoreError> {
    Ok(match current(session).await? {
        Some(jam) => Some(with_share_link(session, jam).await),
        None => None,
    })
}

pub async fn start(session: &Session) -> Result<JamInfo, CoreError> {
    let device = device(session)?;
    let path = format!("/social-connect/v2/sessions/current_or_new?activate=true&local_device_id={device}&type=REMOTE");
    let response = request(session, Method::GET, &path, None).await.map_err(|e| match e {
        CoreError::Auth { msg } => CoreError::Auth { msg: format!("Spotify refused to start a Jam (Premium needed?): {msg}") },
        e => e,
    })?;
    let mut jam = response.as_ref().and_then(parse).ok_or_else(|| CoreError::other("Spotify returned no Jam"))?;
    // The create answer can be the short form (no members): read it again in full.
    if jam.members.is_empty() {
        if let Ok(Some(full)) = current(session).await {
            jam = full;
        }
    }
    jam.is_host = true;
    jam.listening_here = true;
    Ok(with_share_link(session, jam).await)
}

pub async fn preview(session: &Session, invite: &str) -> Result<JamPreview, CoreError> {
    let token = parse_join_token(invite)?;
    let path = format!("/social-connect/v2/sessions/info/{token}");
    let value = request(session, Method::GET, &path, None)
        .await?
        .ok_or_else(|| CoreError::NotFound { msg: "Jam has ended or the invite is invalid".into() })?;
    if bool_of(&value, &["active"]) == Some(false) {
        return Err(CoreError::NotFound { msg: "Jam has ended".into() });
    }
    let owner = str_of(&value, &["session_owner_id", "sessionOwnerId"]).unwrap_or_default();
    let members = members(&value, owner);
    Ok(JamPreview {
        token,
        host_name: members.iter().find(|m| m.is_host).map(|m| m.name.clone()).unwrap_or_default(),
        members,
    })
}

/// listen_here: play the Jam on this device (LISTEN_AND_CONTROL), else control the host's
/// speaker (CONTROL). Spotify refuses the first to free accounts (403): falls back to CONTROL.
pub async fn join(session: &Session, invite: &str, listen_here: bool) -> Result<JamInfo, CoreError> {
    let token = parse_join_token(invite)?;
    let device = device(session)?;
    let modes: &[&str] = if listen_here { &["LISTEN_AND_CONTROL", "CONTROL"] } else { &["CONTROL"] };
    let mut last = CoreError::other("join not tried");
    for mode in modes {
        let body = json!({
            "join_token": token,
            "join_type": "deeplinking",
            "playback_control": mode,
            "local_device_id": device,
            "join_attempt_id": uuid::Uuid::new_v4().to_string(),
            "permission_mode": "FULL",
        });
        match request(session, Method::POST, "/social-connect/v3/sessions/join", Some(body)).await {
            Ok(v) => {
                let mut jam = v.as_ref().and_then(parse).or(current(session).await?)
                    .ok_or_else(|| CoreError::other("Spotify returned no Jam after joining"))?;
                jam.listening_here = *mode == "LISTEN_AND_CONTROL";
                return Ok(with_share_link(session, jam).await);
            }
            Err(CoreError::NotFound { .. }) => {
                return Err(CoreError::NotFound { msg: "Jam has ended or the invite is invalid".into() });
            }
            Err(e) => {
                log::warn!("jam join as {mode}: {e}");
                last = e;
            }
        }
    }
    Err(last)
}

async fn expect(session: &Session, expected_id: &str) -> Result<JamInfo, CoreError> {
    let id = segment(expected_id, "session id")?;
    let jam = current(session).await?.ok_or_else(|| CoreError::NotFound { msg: "not in a Jam".into() })?;
    if jam.session_id != id {
        return Err(CoreError::other("Jam changed; refresh and try again"));
    }
    Ok(jam)
}

/// Leave as a guest, end for everyone as the host. 404: already gone.
pub async fn leave(session: &Session, expected_id: &str) -> Result<(), CoreError> {
    let jam = match expect(session, expected_id).await {
        Err(CoreError::NotFound { .. }) => return Ok(()),
        r => r?,
    };
    let id = &jam.session_id;
    let r = if jam.is_host {
        request(session, Method::DELETE, &format!("/social-connect/v3/sessions/{id}"), None).await
    } else {
        request(session, Method::POST, &format!("/social-connect/v3/sessions/{id}/leave"), None).await
    };
    match r {
        Ok(_) | Err(CoreError::NotFound { .. }) => Ok(()),
        Err(e) => Err(e),
    }
}

pub async fn set_queue_only(session: &Session, expected_id: &str, queue_only: bool) -> Result<(), CoreError> {
    let jam = expect(session, expected_id).await?;
    if !jam.is_host {
        return Err(CoreError::other("only the Jam host can change permissions"));
    }
    let mode = if queue_only { "enabled" } else { "disabled" };
    request(session, Method::PUT, &format!("/social-connect/v2/sessions/current/queue_only_mode/{mode}"), None).await?;
    Ok(())
}

pub async fn kick(session: &Session, expected_id: &str, member_id: &str) -> Result<(), CoreError> {
    let member = segment(member_id, "member id")?;
    let jam = expect(session, expected_id).await?;
    if !jam.is_host {
        return Err(CoreError::other("only the Jam host can remove people"));
    }
    if !jam.members.iter().any(|m| m.id == member && !m.is_current_user) {
        return Err(CoreError::other("that person already left the Jam"));
    }
    let id = &jam.session_id;
    request(session, Method::POST, &format!("/social-connect/v3/sessions/{id}/member/{member}/kick"), None).await?;
    Ok(())
}

/// A guest's player command, sent to the Jam (which relays it to every device) instead of
/// the local player. Pause/resume stay local; volume isn't a Jam command.
pub async fn command(session: &Session, session_id: &str, cmd: &RemoteCommand) -> Result<(), CoreError> {
    let id = segment(session_id, "session id")?;
    if matches!(cmd, RemoteCommand::Volume { .. }) {
        return Err(CoreError::other("volume is not a Jam command"));
    }
    let device = device(session)?;
    let mut body = command_json(cmd);
    body["command"]["logging_params"]["device_identifier"] = json!(device);
    request(session, Method::POST, &format!("/social-connect/v2/sessions/{id}/commands/from/{device}"), Some(body)).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn join_tokens() {
        for link in [
            "spotify:socialsession:AbC123",
            "https://open.spotify.com/socialsession/AbC123?si=x",
            "https://open.spotify.com/intl-nl/socialsession/AbC123",
            "hm://social-connect/v2/sessions/join/AbC123",
            " AbC123 ",
        ] {
            assert_eq!(parse_join_token(link).unwrap(), "AbC123", "{link}");
        }
        assert!(parse_join_token("../bad").is_err());
        assert!(parse_join_token("https://evil.example/socialsession/abc").is_err());
        assert!(parse_join_token("https://spotify.link/x").is_err());
    }

    /// Trimmed from a live GET sessions/current (other project's capture, 2026-10), ids invented.
    #[test]
    fn parses_host_session() {
        let v = json!({"session_id":"f663581b","join_session_token":"tok123",
            "join_session_url":"hm://social-connect/v2/sessions/join/tok123",
            "session_owner_id":"u1","session_members":[
              {"id":"u1","username":"me","display_name":"Me","image_url":"","is_listening":true,"is_controlling":true,
               "playbackControl":"LISTEN_AND_CONTROL","is_current_user":true},
              {"id":"u2","username":"guest","display_name":"","image_url":"http://i.scdn.co/image/abc","is_listening":true}],
            "is_session_owner":true,"hostActiveDeviceId":"dev","maxMemberCount":32,"active":true,"queue_only_mode":true});
        let jam = parse(&v).unwrap();
        assert!(jam.is_host && jam.listening_here && jam.queue_only_mode);
        assert_eq!(jam.invite_url, "https://open.spotify.com/socialsession/tok123");
        assert_eq!(jam.host_name, "Me");
        assert_eq!(jam.members[1].name, "guest", "no display name: username");
        assert_eq!(jam.members[1].image_url, "https://i.scdn.co/image/abc");
        assert_eq!(jam.max_member_count, 32);
        assert_eq!(jam.host_device_id, "dev");
    }

    #[test]
    fn parses_guest_push() {
        let v = json!({"sessionId":"s1","joinSessionToken":"t","sessionOwnerId":"h","isSessionOwner":false,
            "sessionMembers":[{"id":"h","displayName":"Host"},
              {"id":"me","displayName":"Me","isCurrentUser":true,"playbackControl":"CONTROL"}]});
        let jam = parse(&v).unwrap();
        assert!(!jam.is_host && !jam.listening_here);
        assert_eq!(jam.host_name, "Host");
    }

    #[test]
    fn empty_and_inactive_sessions_are_absent() {
        assert!(parse(&Value::Null).is_none());
        assert!(parse(&json!({"session_id":"s", "active":false})).is_none());
        assert!(parse(&json!({})).is_none());
    }

    #[test]
    fn guest_command_names_the_device() {
        let mut body = command_json(&RemoteCommand::SkipNext);
        body["command"]["logging_params"]["device_identifier"] = json!("d");
        assert_eq!(body["command"]["endpoint"], "skip_next");
        assert_eq!(body["command"]["logging_params"]["device_identifier"], "d");
    }
}
