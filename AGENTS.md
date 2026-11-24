# Fastify: agent notes

Cache-first Spotify client for Android: Kotlin/Compose app (`android/`) + librespot Rust core (`rust/core`) via UniFFI. Architecture, Connect, Jam, Pathfinder, logging, updates, build: see README. Rutger is the only user.

## Rules

- **Main only**: commit + push to main. No branches, PRs or worktrees.
- **License GPL-3.0** (LICENSE); `rust/core/src/shazam/` is ported from SongRec (GPL-3.0).
- **Errors**: show real errors in the UI (status, message), no polished copy.
- **Logging**: follow README "Logging" (INFO one line per external event incl. play/skip source, loops `repeated = true`, periodic/per-attempt = DEBUG, no silent drops).
- **Animations** max 150 ms: `fast()` / `ANIM_MS` from `ui/Motion.kt` for every animationSpec, never Compose/Material defaults. Transitions slide (also predictive back), no scaling. README "UI rules".
- **No USB/adb to the phone.** Logs come from in-app Share logs (Home → bug icon → Diagnostics).
- **Connect/Jam tests**: never start/stop Rutger's PC playback or a real Jam on his account without asking; use two probe dirs (README "Spotify Connect").

## Layout

- `rust/core`: librespot 0.8 session, login, key store, CDN fetch/decrypt, 2 GB cache, prefetch, spclient, Connect, Jam. `rust/build-android.sh` builds the .so + Kotlin bindings (Gradle task `cargoBuild<Variant>`).
- `rust/probe`: `fastify-probe <data-dir> …`, exercises the core against a real account without an emulator (`run` = every library call the app makes; usage at top of `src/main.rs`).
- `android/`: app `com.rutger.fastify`, minSdk 35, target/compile 37.
- `rust/vendor/librespot-core`: **FASTIFY PATCH**es (grep): Android reports as Linux (else login5/client-token fail, empty home), dealer reconnect, plain-JSON dealer pushes. Keep them.
- `vergen` pinned to 9.0.6 in `Cargo.lock` (9.1 breaks librespot build.rs). Don't `cargo update` it.

## Release

- `versionCode` = git commit count; version shown as `v<versionCode>`.
- Commit + push first, then `android/scripts/publish`: uploads `fastify-<versionCode>.apk`, `fastify.apk`, then `version.json`, verifies each by sha256. Refuses dirty builds and non-newer versions. App auto-updates from `version.json` (README "Updates").
- **Never copy APKs to the CDN by hand**: the app wouldn't see the new version.
- **Never upload debug APKs** (~85 MB) to the CDN: large uploads OOM the SeaweedFS VM. Test the updater locally: debug build with `FASTIFY_UPDATE_URL=http://10.0.2.2:<port>/` + `python3 -m http.server` on 127.0.0.1.
- Signing key `~/.local/share/android/debug.keystore`: losing it breaks updates (reinstall needed).

## Spotify pitfalls

- Desktop client id `65b708073fc0480ea92a077233ca87bd` (`KEYMASTER_CLIENT_ID`). **Web API (api.spotify.com) always 429s: never use it.**
- Library/search in `rust/core/src/library.rs` over the librespot session: playlists = rootlist, tracks = `get_playlist`, liked + albums = POST `/collection/v2/paging` (fallback context-resolve `spotify:user:<u>:collection`), metadata = extended-metadata `TRACK_V4`/`ALBUM_V4`, search = context-resolve `spotify:search:<q+words>`. Covers: `https://i.scdn.co/image/<hex id>`.
- Recently played is Fastify's own history (`filesDir/history.json`).
