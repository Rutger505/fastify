//! fastify-probe: exercise fastify-core against a real account from a workstation.
//!
//!   fastify-probe <data-dir> login     # OAuth; prints the URL, waits on 127.0.0.1:8898
//!   fastify-probe <data-dir> run       # every library call the app makes
//!   fastify-probe <data-dir> raw <path>  # raw spclient GET
//!   fastify-probe <data-dir> artist|album|lyrics <id>
//!   fastify-probe <data-dir> pladd <playlist> <track> | plrm <playlist> <track> <occurrence>
//!   fastify-probe <data-dir> like|unlike <track> | download <track>
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
        "run" => run(core).await,
        "raw" => {
            let path = args.get(3).expect("path");
            match core.spclient_get(path.clone()).await {
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
