//! Library writes: playlist add/remove (playlist4 ListChanges) and Liked Songs (collection2v2).

use std::time::{SystemTime, UNIX_EPOCH};

use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::Session;
use librespot_protocol::playlist4_external::{
    Add, ChangeInfo, Delta, Item, ListChanges, Op, Rem, SelectedListContent, op,
};
use protobuf::{EnumOrUnknown, Message, MessageField};

use crate::CoreError;
use crate::library::{parse_id, put_str, put_varint};

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

async fn fetch_list(session: &Session, playlist_id: &str) -> Result<SelectedListContent, CoreError> {
    let bytes = session
        .spclient()
        .get_playlist(&parse_id(playlist_id)?)
        .await?;
    SelectedListContent::parse_from_bytes(&bytes).map_err(CoreError::other)
}

async fn send_changes(
    session: &Session,
    playlist_id: &str,
    base_revision: Option<Vec<u8>>,
    ops: Vec<Op>,
) -> Result<(), CoreError> {
    let changes = ListChanges {
        base_revision: base_revision.clone(),
        deltas: vec![Delta {
            base_version: base_revision,
            ops,
            info: MessageField::some(ChangeInfo {
                user: Some(session.username()),
                timestamp: Some(now_secs() as i64 * 1000),
                ..Default::default()
            }),
            ..Default::default()
        }],
        want_resulting_revisions: Some(true),
        ..Default::default()
    };
    let path = format!("/playlist/v2/playlist/{playlist_id}/changes");
    session
        .spclient()
        .request_with_protobuf(&Method::POST, &path, None, &changes)
        .await?;
    Ok(())
}

/// Append tracks to the end of a playlist.
pub async fn playlist_add(
    session: &Session,
    playlist_id: &str,
    track_ids: &[String],
) -> Result<(), CoreError> {
    let list = fetch_list(session, playlist_id).await?;
    let items = track_ids
        .iter()
        .map(|id| Item {
            uri: Some(format!("spotify:track:{id}")),
            ..Default::default()
        })
        .collect();
    let op = Op {
        kind: Some(EnumOrUnknown::new(op::Kind::ADD)),
        add: MessageField::some(Add {
            items,
            add_last: Some(true),
            ..Default::default()
        }),
        ..Default::default()
    };
    send_changes(session, playlist_id, list.revision, vec![op]).await?;
    log::info!("added {} tracks to {playlist_id}", track_ids.len());
    Ok(())
}

/// Remove the occurrence-th (0-based) copy of a track from a playlist, like removing one row.
pub async fn playlist_remove(
    session: &Session,
    playlist_id: &str,
    track_id: &str,
    occurrence: u32,
) -> Result<(), CoreError> {
    let list = fetch_list(session, playlist_id).await?;
    let uri = format!("spotify:track:{track_id}");
    let (index, item) = list
        .contents
        .items
        .iter()
        .enumerate()
        .filter(|(_, i)| i.uri() == uri)
        .nth(occurrence as usize)
        .ok_or_else(|| CoreError::NotFound {
            msg: format!("{uri} #{occurrence} not in playlist {playlist_id}"),
        })?;
    let op = Op {
        kind: Some(EnumOrUnknown::new(op::Kind::REM)),
        rem: MessageField::some(Rem {
            from_index: Some(index as i32),
            length: Some(1),
            items: vec![item.clone()],
            ..Default::default()
        }),
        ..Default::default()
    };
    send_changes(session, playlist_id, list.revision.clone(), vec![op]).await?;
    log::info!("removed {uri} (index {index}) from {playlist_id}");
    Ok(())
}

/// WriteRequest { username = 1; set = 2; repeated CollectionItem items = 3 },
/// CollectionItem { uri = 1; added_at = 2; is_removed = 3 }
fn encode_write_request(username: &str, uris: &[String], removed: bool) -> Vec<u8> {
    let mut b = Vec::new();
    put_str(&mut b, 1, username);
    put_str(&mut b, 2, "collection");
    let now = now_secs();
    for uri in uris {
        let mut item = Vec::new();
        put_str(&mut item, 1, uri);
        put_varint(&mut item, 2 << 3);
        put_varint(&mut item, now);
        if removed {
            put_varint(&mut item, 3 << 3);
            put_varint(&mut item, 1);
        }
        put_varint(&mut b, (3 << 3) | 2);
        put_varint(&mut b, item.len() as u64);
        b.extend_from_slice(&item);
    }
    b
}

/// Add tracks to (or remove them from) Liked Songs.
pub async fn set_liked(session: &Session, track_ids: &[String], liked: bool) -> Result<(), CoreError> {
    let uris: Vec<String> = track_ids
        .iter()
        .map(|id| format!("spotify:track:{id}"))
        .collect();
    let body = encode_write_request(&session.username(), &uris, !liked);
    let mut headers = HeaderMap::new();
    let ct = HeaderValue::from_static("application/vnd.collection-v2.spotify.proto");
    headers.insert(header::CONTENT_TYPE, ct.clone());
    headers.insert(header::ACCEPT, ct);
    session
        .spclient()
        .request(&Method::POST, "/collection/v2/write", Some(headers), Some(&body))
        .await?;
    log::info!("liked={liked} for {} tracks", uris.len());
    Ok(())
}
