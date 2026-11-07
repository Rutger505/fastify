//! fastify-probe: exercise fastify-core against a real account from a workstation.
//!
//!   fastify-probe <data-dir> login     # OAuth; prints the URL, waits on 127.0.0.1:8898
//!   fastify-probe <data-dir> run       # every library call the app makes
//!   fastify-probe <data-dir> raw <path>  # raw spclient GET
//!   fastify-probe <data-dir> artist|album|lyrics <id>
//!   fastify-probe <data-dir> plcreate <name> | plrename <playlist> <name> | pladd <playlist> <track> | plrm <playlist> <track> <occurrence>
//!   fastify-probe <data-dir> like|unlike <track> | download <track>
//!   fastify-probe <data-dir> target <name> [secs] [track]  # act as a Connect player, print commands
//!     (PROBE_NO_ACTIVATE=1: refuse loads/resumes, never becomes the active device)
//!   fastify-probe <data-dir> jam get|start|preview <invite>|join <invite> [control]|leave <id>|queueonly <id> <bool>|watch [secs]
//!     (start registers a paused Connect device first, like target)
//!   fastify-probe <data-dir> shazam <file.s16>  # raw 16 kHz mono s16le, e.g. ffmpeg -i x.mp3 -ss 60 -t 12 -ac 1 -ar 16000 -f s16le x.s16
//!
//! The OAuth redirect goes to 127.0.0.1:8898 on this machine. When logging in from another
//! device, copy the failed redirect URL and replay it here: `curl '<url>'`.

use std::sync::Arc;
use std::time::Instant;

use fastify_core::FastifyCore;
use librespot_oauth::OAuthClientBuilder;

const CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";
const REDIRECT: &str = "http://127.0.0.1:8898/login";

#[tokio::main]
async fn main() {
    env_logger::Builder::new().parse_filters("info").init();
    let args: Vec<String> = std::env::args().collect();
    let (Some(dir), Some(cmd)) = (args.get(1), args.get(2)) else {
        eprintln!("usage: fastify-probe <data-dir> login|run|raw <path>");
        std::process::exit(2);
    };
    let core =
        FastifyCore::new(dir.clone(), format!("{dir}/cache"), 512 * 1024 * 1024).expect("core");
    match cmd.as_str() {
        "login" => login(core).await,
        "connect" => {
            // Print the Connect cluster for <secs> seconds (default 20).
            let secs: u64 = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(20);
            let end = Instant::now() + std::time::Duration::from_secs(secs);
            let mut v = 0;
            while Instant::now() < end {
                match core.clone().connect_watch(v).await {
                    Ok(s) => {
                        v = s.version;
                        println!("v{} ready={} err={:?} active={:?} playing={} pos={}/{} shuffle={} repeat={}/{} ctx={:?} rev={}",
                            s.version, s.ready, s.error, s.active_device_id, s.is_playing, s.position_ms, s.duration_ms,
                            s.shuffle, s.repeat_context, s.repeat_track, s.context_uri, s.queue_revision);
                        for d in &s.devices { println!("  device {} {:?} {} vol={} can_play={}", d.id, d.name, d.kind, d.volume, d.can_play); }
                        println!("  track {:?}", s.track);
                        println!("  prev {} next {}: {:?}", s.prev_tracks.len(), s.next_tracks.len(), s.next_tracks.iter().take(4).collect::<Vec<_>>());
                    }
                    Err(e) => { println!("ERR {e}"); break; }
                }
            }
        }
        "cmd" => {
            // cmd <device> pause|resume|next|prev|seek <ms>|shuffle <bool>|queue <track>|play <ctx|-> <track,...> <i>|volume <0..65535>|transfer <to>
            use fastify_core::RemoteCommand as C;
            let dev = args[3].clone();
            let a = |i: usize| args.get(i).cloned().unwrap_or_default();
            let res = if args[4] == "transfer" {
                core.clone().connect_transfer(dev, a(5)).await
            } else {
                let c = match args[4].as_str() {
                    "pause" => C::Pause,
                    "resume" => C::Resume,
                    "next" => C::SkipNext,
                    "prev" => C::SkipPrev,
                    "seek" => C::SeekTo { position_ms: a(5).parse().unwrap() },
                    "shuffle" => C::SetShuffle { on: a(5) == "true" },
                    "repeat" => C::SetRepeat { context: a(5) == "true", track: a(6) == "true" },
                    "queue" => C::AddToQueue { track_id: a(5) },
                    "play" => C::Play {
                        context_uri: Some(a(5)).filter(|s| s != "-"),
                        track_ids: a(6).split(',').map(String::from).collect(),
                        start_index: a(7).parse().unwrap_or(0),
                        position_ms: a(8).parse().unwrap_or(0),
                        shuffle: None,
                    },
                    "volume" => C::Volume { volume: a(5).parse().unwrap() },
                    other => panic!("unknown cmd {other}"),
                };
                // Commands need a registered connection: start the observer first.
                let _ = core.clone().connect_watch(0).await;
                core.clone().connect_command(dev, c).await
            };
            println!("{:?}", res.map_err(|e| e.to_string()));
        }
        "target" => {
            let name = args.get(3).cloned().unwrap_or_else(|| "Fastify probe".into());
            let secs: u64 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(60);
            target(core, name, secs, args.get(5).cloned()).await
        }
        "run" => run(core).await,
        "jam" => jam(core, &args[3..]).await,
        "raw" => {
            let path = args.get(3).expect("path");
            match core.clone().spclient_get(path.clone()).await {
                Ok(b) => println!("{} bytes\n{}", b.len(), String::from_utf8_lossy(&b)),
                Err(e) => println!("ERR {e}"),
            }
        }
        "artist" => {
            let id = args.get(3).expect("artist id").clone();
            match core.clone().artist(id).await {
                Ok(a) => {
                    println!("{} img={:?}", a.name, a.image_url);
                    println!("bio: {:?}", a.biography.map(|b| b.chars().take(120).collect::<String>()));
                    println!("top: {}", tracks(&a.top_tracks));
                    println!("top ids: {}", a.top_tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>().join(" "));
                    for (name, list) in [("albums", &a.albums), ("singles", &a.singles), ("compilations", &a.compilations), ("appears_on", &a.appears_on)] {
                        println!("{name} ({}): {:?}", list.len(), list.iter().take(5).map(|x| format!("{} [{} {:?}]", x.name, x.album_type, x.year)).collect::<Vec<_>>());
                    }
                }
                Err(e) => println!("ERR {e}"),
            }
        }
        "album" => match core.clone().album(args.get(3).expect("album id").clone()).await {
            Ok(a) => println!("{} by {:?} {:?} {}: {}", a.album.name, a.album.artist_refs.iter().map(|r| (&r.name, &r.id)).collect::<Vec<_>>(), a.album.year, a.album.album_type, tracks(&a.tracks)),
            Err(e) => println!("ERR {e}"),
        },
        "lyrics" => match core.clone().lyrics(args.get(3).expect("track id").clone()).await {
            Ok(Some(l)) => println!("synced={} provider={} {} lines: {:?}", l.synced, l.provider, l.lines.len(), l.lines.iter().take(4).map(|x| (x.start_ms, x.words.clone())).collect::<Vec<_>>()),
            Ok(None) => println!("no lyrics"),
            Err(e) => println!("ERR {e}"),
        },
        "plcreate" => println!("{:?}", core.clone().playlist_create(args[3].clone()).await.map_err(|e| e.to_string())),
        "plrename" => println!("{:?}", core.clone().playlist_rename(args[3].clone(), args[4].clone()).await.map_err(|e| e.to_string())),
        "pladd" => println!("{:?}", core.clone().playlist_add(args[3].clone(), vec![args[4].clone()]).await.map_err(|e| e.to_string())),
        "plrm" => println!("{:?}", core.clone().playlist_remove(args[3].clone(), args[4].clone(), args[5].parse().unwrap()).await.map_err(|e| e.to_string())),
        "like" | "unlike" => println!("{:?}", core.clone().set_liked(vec![args[3].clone()], cmd == "like").await.map_err(|e| e.to_string())),
        "download" => {
            let t = Instant::now();
            let r = core.clone().download(args[3].clone()).await.map_err(|e| e.to_string());
            println!("{r:?} in {} ms; downloaded={} all={:?} size={}", t.elapsed().as_millis(), core.is_track_downloaded(args[3].clone()), core.downloaded_tracks(), core.downloads_size());
        }
        "new" => {
            let t = Instant::now();
            match core.clone().new_releases(60).await {
                Ok(a) => { println!("{} releases in {} ms", a.len(), t.elapsed().as_millis()); for x in a.iter().take(15) { println!("{:?} {} - {} [{}]", x.release_date, x.name, x.artists, x.album_type) } }
                Err(e) => println!("ERR {e}"),
            }
        }
        "isliked" => match core.clone().liked_tracks().await {
            Ok(t) => println!("liked={} (total {})", t.iter().any(|x| x.id == args[3]), t.len()),
            Err(e) => println!("ERR {e}"),
        },
        "playlists" => match core.clone().playlists().await {
            Ok(p) => for x in p { println!("{} {} owner={} len={}", x.id, x.name, x.owner, x.length) },
            Err(e) => println!("ERR {e}"),
        },
        "pltracks" => match core.clone().playlist_tracks(args[3].clone()).await {
            Ok(t) => for x in t { println!("{} {} - {} album={:?} artists={:?}", x.id, x.title, x.artists, x.album_id, x.artist_refs.iter().map(|r| &r.id).collect::<Vec<_>>()) },
            Err(e) => println!("ERR {e}"),
        },
        "autoplay" => {
            let recent: Vec<String> = args[4..].to_vec();
            match core.clone().autoplay(args[3].clone(), recent).await {
                Ok(t) => {
                    println!("{} tracks", t.len());
                    for x in t { println!("{} {} - {}", x.id, x.title, x.artists) }
                }
                Err(e) => println!("ERR {e}"),
            }
        }
        "shazam" => {
            let raw = std::fs::read(&args[3]).expect("pcm file");
            let pcm: Vec<i16> = raw.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
            match core.clone().shazam(pcm).await {
                Ok(m) => println!("{m:?}"),
                Err(e) => println!("ERR {e}"),
            }
        }
        _ => eprintln!("unknown command {cmd}"),
    }
}

async fn login(core: Arc<FastifyCore>) {
    let client = OAuthClientBuilder::new(CLIENT_ID, REDIRECT, vec!["streaming"])
        .with_custom_message("<h1>Fastify probe logged in</h1>")
        .build()
        .expect("oauth client");
    let token = client.get_access_token_async().await.expect("oauth");
    let user = core
        .login(token.access_token)
        .await
        .expect("librespot login");
    println!("LOGGED IN as {user}");
}

async fn run(core: Arc<FastifyCore>) {
    macro_rules! step {
        ($name:expr, $e:expr, $fmt:expr) => {{
            let t = Instant::now();
            let r = $e.await;
            let ms = t.elapsed().as_millis();
            match &r {
                Ok(v) => println!("OK   {} ({ms} ms): {}", $name, $fmt(v)),
                Err(e) => println!("FAIL {} ({ms} ms): {e}", $name),
            }
            r.ok()
        }};
    }
    step!("session", core.clone().username(), |u: &String| u.clone());
    let lists = step!("playlists", core.clone().playlists(), |p: &Vec<
        fastify_core::PlaylistInfo,
    >| format!(
        "{} e.g. {:?}",
        p.len(),
        p.iter()
            .take(5)
            .map(|x| (&x.name, x.length, x.image_url.is_some()))
            .collect::<Vec<_>>()
    ));
    if let Some(pl) = lists.and_then(|l| l.into_iter().find(|p| p.length > 0)) {
        step!(
            format!("playlist '{}'", pl.name),
            core.clone().playlist_tracks(pl.id.clone()),
            tracks
        );
    }
    step!("liked", core.clone().liked_tracks(), tracks);
    let albums = step!("albums", core.clone().saved_albums(), |a: &Vec<
        fastify_core::AlbumInfo,
    >| format!(
        "{} e.g. {:?}",
        a.len(),
        a.iter().take(3).map(|x| &x.name).collect::<Vec<_>>()
    ));
    if let Some(al) = albums.and_then(|a| a.into_iter().next()) {
        step!(
            format!("album '{}'", al.name),
            core.clone().album_tracks(al.id.clone()),
            tracks
        );
    }
    step!(
        "search",
        core.clone().search_tracks("daft punk".into()),
        tracks
    );
}

fn tracks(t: &Vec<fastify_core::TrackInfo>) -> String {
    format!(
        "{} e.g. {:?}",
        t.len(),
        t.iter()
            .take(3)
            .map(|x| format!(
                "{} - {} ({}ms, img={})",
                x.title,
                x.artists,
                x.duration_ms,
                x.image_url.is_some()
            ))
            .collect::<Vec<_>>()
    )
}


/// Simulated Connect player: registers as a device, applies commands to a fake queue, publishes
/// the state back. With a track it loads it (paused) and becomes the active device right away.
async fn target(core: Arc<FastifyCore>, name: String, secs: u64, track: Option<String>) {
    use fastify_core::{ConnectRequest as R, LocalPlayer, RemoteTrack};
    let now = || std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
    let rt = |id: &str, i: usize, provider: &str| RemoteTrack { uri: format!("spotify:track:{id}"), uid: format!("fy{i}"), provider: provider.into() };
    // queue (id, provider), index, playing, position at time
    let mut queue: Vec<(String, String)> = Vec::new();
    let mut index = 0usize;
    let mut playing = false;
    let (mut pos, mut at) = (0u64, now());
    let mut shuffle = false;
    let mut repeat = (false, false);
    let mut ctx: Option<String> = None;
    let mut volume = 40000u32;
    let state = |queue: &Vec<(String, String)>, index: usize, playing: bool, pos: u64, at: u64, shuffle: bool, repeat: (bool, bool), ctx: &Option<String>, volume: u32| LocalPlayer {
        name: name.clone(),
        track: queue.get(index).map(|(id, p)| rt(id, index, p)),
        is_playing: playing,
        is_buffering: false,
        position_ms: pos,
        position_at_ms: at,
        duration_ms: 200_000,
        shuffle,
        repeat_context: repeat.0,
        repeat_track: repeat.1,
        context_uri: ctx.clone(),
        prev_tracks: queue.iter().enumerate().take(index).map(|(i, (id, p))| rt(id, i, p)).collect(),
        next_tracks: queue.iter().enumerate().skip(index + 1).map(|(i, (id, p))| rt(id, i, p)).collect(),
        volume,
    };
    if let Some(t) = &track {
        queue.push((t.clone(), "context".into()));
    }
    core.connect_set_player(Some(state(&queue, index, playing, pos, at, shuffle, repeat, &ctx, volume)));
    println!("device id {}", core.connect_device_id());
    let watcher = {
        let core = core.clone();
        tokio::spawn(async move {
            let mut v = 0;
            loop {
                let Ok(s) = core.clone().connect_watch(v).await else { break };
                if s.version != v {
                    v = s.version;
                    println!("  cluster: active={:?} devices={:?}", s.active_device_id, s.devices.iter().map(|d| (&d.name, d.can_play)).collect::<Vec<_>>());
                }
            }
        })
    };
    if track.is_some() {
        // After registering: a paused player gives up activity when (re)registering.
        let core = core.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_secs(4)).await;
            core.connect_activate();
        });
    }
    let end = Instant::now() + std::time::Duration::from_secs(secs);
    while Instant::now() < end {
        let req = match core.clone().connect_next_request().await {
            Ok(Some(r)) => r,
            Ok(None) => continue,
            Err(e) => { println!("ERR {e}"); break; }
        };
        let cur = |pos: u64, at: u64, playing: bool| if playing { pos + (now() - at) } else { pos };
        match &req.command {
            R::Load { tracks, start_index, position_ms, paused, context_uri, shuffle: sh, repeat_context, repeat_track, queued } => {
                println!("LOAD {} tracks ({} stubs) start={start_index} pos={position_ms} paused={paused} ctx={context_uri:?} shuffle={sh:?} repeat={repeat_context:?}/{repeat_track:?} queued={}", tracks.len(), tracks.iter().filter(|t| t.title.is_empty()).count(), queued.len());
                for t in tracks.iter().skip((*start_index).max(0) as usize).take(3) { println!("    {} - {} ({})", t.title, t.artists, t.id) }
                index = (*start_index).max(0) as usize;
                queue = tracks.iter().map(|t| (t.id.clone(), "context".to_string())).collect();
                for (i, q) in queued.iter().enumerate() { queue.insert(index + 1 + i, (q.id.clone(), "queue".into())) }
                playing = !paused; pos = *position_ms; at = now();
                shuffle = sh.unwrap_or(shuffle);
                repeat = (repeat_context.unwrap_or(repeat.0), repeat_track.unwrap_or(repeat.1));
                ctx = context_uri.clone();
            }
            R::Pause => { pos = cur(pos, at, playing); at = now(); playing = false; println!("PAUSE at {pos}") }
            R::Resume => { at = now(); playing = true; println!("RESUME") }
            R::SeekTo { position_ms } => { pos = *position_ms; at = now(); println!("SEEK {position_ms}") }
            R::SkipNext { track } => {
                let to = track.as_ref().and_then(|t| (index + 1..queue.len()).find(|&i| format!("fy{i}") == t.uid || queue[i].0 == t.uri.trim_start_matches("spotify:track:")));
                index = to.unwrap_or(index + 1).min(queue.len().saturating_sub(1)); pos = 0; at = now();
                println!("NEXT -> {index} {:?}", queue.get(index));
            }
            R::SkipPrev => { index = index.saturating_sub(1); pos = 0; at = now(); println!("PREV -> {index}") }
            R::SetOptions { shuffle: s, repeat_context, repeat_track } => {
                shuffle = s.unwrap_or(shuffle);
                repeat = (repeat_context.unwrap_or(repeat.0), repeat_track.unwrap_or(repeat.1));
                println!("OPTIONS shuffle={shuffle} repeat={repeat:?}");
            }
            R::AddToQueue { track } => {
                let mut at_i = index + 1;
                while at_i < queue.len() && queue[at_i].1 == "queue" { at_i += 1 }
                queue.insert(at_i, (track.id.clone(), "queue".into()));
                println!("QUEUE {} - {}", track.title, track.artists);
            }
            R::SetQueue { next } => {
                let keep: Vec<&str> = next.iter().map(|t| t.uid.as_str()).collect();
                let before = queue.len();
                queue = queue.iter().enumerate().filter(|(i, _)| *i <= index || keep.contains(&format!("fy{i}").as_str())).map(|(_, q)| q.clone()).collect();
                println!("SET_QUEUE {} -> {} items", before, queue.len());
            }
            R::Volume { volume: v } => { volume = *v; println!("VOLUME {v}") }
            R::Deactivated => { pos = cur(pos, at, playing); at = now(); playing = false; println!("DEACTIVATED by {}", req.from_device_id) }
            R::Metadata { tracks, missing } => {
                queue.retain(|(id, _)| !missing.contains(id));
                println!("METADATA {} tracks, {} missing", tracks.len(), missing.len());
            }
        }
        // State first: the reply can make us active, which PUTs the state right away.
        core.connect_set_player(Some(state(&queue, index, playing, pos, at, shuffle, repeat, &ctx, volume)));
        // PROBE_NO_ACTIVATE=1: fail loads/resumes so the probe never takes playback over (test
        // while another device plays without stopping it).
        let refuse = std::env::var("PROBE_NO_ACTIVATE").is_ok_and(|v| v == "1")
            && matches!(req.command, R::Load { .. } | R::Resume);
        core.connect_reply(req.id, !refuse);
    }
    watcher.abort();
    core.connect_set_player(None);
    tokio::time::sleep(std::time::Duration::from_secs(2)).await;
}

async fn jam(core: Arc<FastifyCore>, args: &[String]) {
    let a = |i: usize| args.get(i).cloned().unwrap_or_default();
    match a(0).as_str() {
        "get" => println!("{:#?}", core.clone().jam_get().await),
        "start" => {
            core.connect_set_player(Some(fastify_core::LocalPlayer {
                name: "Fastify probe".into(), track: None, is_playing: false, is_buffering: false,
                position_ms: 0, position_at_ms: 0, duration_ms: 0, shuffle: false, repeat_context: false,
                repeat_track: false, context_uri: None, prev_tracks: vec![], next_tracks: vec![], volume: 40000,
            }));
            println!("device id {}", core.connect_device_id());
            tokio::time::sleep(std::time::Duration::from_secs(4)).await;
            println!("{:#?}", core.clone().jam_start().await);
        }
        "preview" => println!("{:#?}", core.clone().jam_preview(a(1)).await),
        "join" => println!("{:#?}", core.clone().jam_join(a(1), a(2) != "control").await),
        "leave" => println!("{:?}", core.clone().jam_leave(a(1)).await),
        "queueonly" => println!("{:?}", core.clone().jam_set_queue_only(a(1), a(2) == "true").await),
        "watch" => {
            let end = Instant::now() + std::time::Duration::from_secs(a(1).parse().unwrap_or(60));
            let mut v = 0;
            while Instant::now() < end {
                match core.clone().jam_watch(v).await {
                    Ok(e) if e.version != v => { v = e.version; println!("update {v} {}: {:#?}", e.reason, core.clone().jam_get().await) }
                    Ok(_) => {}
                    Err(e) => { println!("ERR {e}"); break }
                }
            }
        }
        _ => eprintln!("jam get|start|preview <invite>|join <invite> [control]|leave <id>|queueonly <id> <bool>|watch [secs]"),
    }
}
