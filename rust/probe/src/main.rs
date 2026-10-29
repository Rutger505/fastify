//! fastify-probe: exercise fastify-core against a real account from a workstation.
//!
//!   fastify-probe <data-dir> login     # OAuth; prints the URL, waits on 127.0.0.1:8898
//!   fastify-probe <data-dir> run       # every library call the app makes
//!   fastify-probe <data-dir> raw <path>  # raw spclient GET
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
