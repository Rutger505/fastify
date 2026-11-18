//! Library writes: playlist add/remove (playlist4 ListChanges) and Liked Songs (collection2v2).

use std::time::{SystemTime, UNIX_EPOCH};

use http::{HeaderMap, HeaderValue, Method, header};
use librespot_core::Session;
use librespot_protocol::playlist4_external::{
    Add, ChangeInfo, CreateListReply, Delta, Item, ItemAttributes, ListAttributes,
    ListAttributesPartialState, ListChanges, Op, Rem, SelectedListContent, UpdateListAttributes, op,
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

async fn fetch_list(
    session: &Session,
    playlist_id: &str,
) -> Result<SelectedListContent, CoreError> {
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
    let path = format!("/playlist/v2/playlist/{playlist_id}/changes");
    post_changes(session, &path, base_revision, ops).await
}

async fn post_changes(
    session: &Session,
    path: &str,
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
    session
        .spclient()
        .request_with_protobuf(&Method::POST, path, None, &changes)
        .await?;
    Ok(())
}

/// Create an empty private playlist and put it at the top of the user's library (rootlist).
/// Returns the base62 playlist id.
pub async fn playlist_create(session: &Session, name: &str) -> Result<String, CoreError> {
    // Spotify ignores attributes in the create call itself, so the name is set afterwards.
    let delta = Delta {
        info: MessageField::some(ChangeInfo {
            user: Some(session.username()),
            timestamp: Some(now_secs() as i64 * 1000),
            ..Default::default()
        }),
        ..Default::default()
    };
    let bytes = session
        .spclient()
        .request_with_protobuf(&Method::POST, "/playlist/v2/playlist", None, &delta)
        .await?;
    let reply = CreateListReply::parse_from_bytes(&bytes).map_err(CoreError::other)?;
    let uri = reply.uri().to_owned();
    let id = uri
        .strip_prefix("spotify:playlist:")
        .ok_or_else(|| CoreError::other(format!("unexpected playlist uri {uri}")))?
        .to_owned();

    let rootlist = session.spclient().get_rootlist(0, Some(1)).await?;
    let rootlist = SelectedListContent::parse_from_bytes(&rootlist).map_err(CoreError::other)?;
    let op = Op {
        kind: Some(EnumOrUnknown::new(op::Kind::ADD)),
        add: MessageField::some(Add {
            items: vec![Item {
                uri: Some(uri),
                attributes: MessageField::some(ItemAttributes {
                    timestamp: Some(now_secs() as i64 * 1000),
                    ..Default::default()
                }),
                ..Default::default()
            }],
            add_first: Some(true),
            ..Default::default()
        }),
        ..Default::default()
    };
    let path = format!("/playlist/v2/user/{}/rootlist/changes", session.username());
    post_changes(session, &path, rootlist.revision, vec![op]).await?;
    playlist_rename(session, &id, name).await?;
    log::info!("created playlist {id} ({name:?})");
    Ok(id)
}

pub async fn playlist_rename(session: &Session, playlist_id: &str, name: &str) -> Result<(), CoreError> {
    let list = fetch_list(session, playlist_id).await?;
    let op = Op {
        kind: Some(EnumOrUnknown::new(op::Kind::UPDATE_LIST_ATTRIBUTES)),
        update_list_attributes: MessageField::some(UpdateListAttributes {
            new_attributes: MessageField::some(ListAttributesPartialState {
                values: MessageField::some(ListAttributes {
                    name: Some(name.to_owned()),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        }),
        ..Default::default()
    };
    send_changes(session, playlist_id, list.revision, vec![op]).await
}

/// Append tracks to the end of a playlist, skipping ones already in it (no duplicates).
/// Returns how many were added.
pub async fn playlist_add(
    session: &Session,
    playlist_id: &str,
    track_ids: &[String],
) -> Result<u32, CoreError> {
    let list = fetch_list(session, playlist_id).await?;
    let present: std::collections::HashSet<&str> =
        list.contents.items.iter().map(|i| i.uri()).collect();
    let mut seen = std::collections::HashSet::new();
    let items: Vec<Item> = track_ids
        .iter()
        .map(|id| format!("spotify:track:{id}"))
        .filter(|uri| !present.contains(uri.as_str()) && seen.insert(uri.clone()))
        .map(|uri| Item {
            uri: Some(uri),
            ..Default::default()
        })
        .collect();
    if items.is_empty() {
        log::info!("add to {playlist_id}: all {} already in it", track_ids.len());
        return Ok(0);
    }
    let added = items.len() as u32;
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
    log::info!("added {added} tracks to {playlist_id}");
    Ok(added)
}

/// Remove every copy of a track from a playlist. Returns how many rows were removed.
pub async fn playlist_remove_all(
    session: &Session,
    playlist_id: &str,
    track_id: &str,
) -> Result<u32, CoreError> {
    let list = fetch_list(session, playlist_id).await?;
    let uri = format!("spotify:track:{track_id}");
    // Highest index first, so earlier removals don't shift later ones.
    let ops: Vec<Op> = list
        .contents
        .items
        .iter()
        .enumerate()
        .filter(|(_, i)| i.uri() == uri)
        .rev()
        .map(|(index, item)| Op {
            kind: Some(EnumOrUnknown::new(op::Kind::REM)),
            rem: MessageField::some(Rem {
                from_index: Some(index as i32),
                length: Some(1),
                items: vec![item.clone()],
                ..Default::default()
            }),
            ..Default::default()
        })
        .collect();
    let removed = ops.len() as u32;
    if removed > 0 {
        send_changes(session, playlist_id, list.revision.clone(), ops).await?;
    }
    log::info!("removed {removed} copies of {uri} from {playlist_id}");
    Ok(removed)
}

/// Which of `playlist_ids` contain the track (fresh from the server, fetched in parallel).
/// Playlists that fail to load are left out and logged.
pub async fn playlists_containing(
    session: &Session,
    playlist_ids: &[String],
    track_id: &str,
) -> Result<Vec<String>, CoreError> {
    let uri = format!("spotify:track:{track_id}");
    let mut set = tokio::task::JoinSet::new();
    for id in playlist_ids {
        let (session, id, uri) = (session.clone(), id.clone(), uri.clone());
        set.spawn(async move {
            let r = fetch_list(&session, &id).await;
            (id, r.map(|l| l.contents.items.iter().any(|i| i.uri() == uri)))
        });
    }
    let mut out = Vec::new();
    while let Some(joined) = set.join_next().await {
        match joined.map_err(CoreError::other)? {
            (id, Ok(true)) => out.push(id),
            (_, Ok(false)) => {}
            (id, Err(e)) => log::warn!("membership of {id}: {e}"),
        }
    }
    Ok(out)
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
pub async fn set_liked(
    session: &Session,
    track_ids: &[String],
    liked: bool,
) -> Result<(), CoreError> {
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
        .request(
            &Method::POST,
            "/collection/v2/write",
            Some(headers),
            Some(&body),
        )
        .await?;
    log::info!("liked={liked} for {} tracks", uris.len());
    Ok(())
}
