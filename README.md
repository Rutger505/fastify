# Fastify ⚡

A fast, cache-first Spotify client for Android, built with Kotlin + Jetpack Compose on top of
[librespot](https://github.com/librespot-org/librespot) (Rust).

The official app takes ~8 s from launch to music. Fastify aims for **< 1 s** when the track is cached.

Requires Spotify **Premium**. No Spotify developer app is needed. Fastify logs in with Spotify's own
desktop client id, the same way librespot does.

## Architecture

- **Rust** (`rust/core`) owns the Spotify session, audio keys, CDN downloads, decryption and the
  audio file cache (2 GB LRU). It exposes a tiny API through UniFFI: `login`, `connect`,
  `open_track`, `prefetch`, `is_track_cached`, `access_token`.
  Its key store maps track → (file id, audio key), so cached tracks need no network at all.
- **Kotlin** (`android/`) owns the UI (Compose), the Web API (library, search) and playback
  (Media3 ExoPlayer + MediaSessionService). ExoPlayer plays the decrypted Ogg Vorbis stream
  (320 kbps) that Rust hands it through `SpotifyDataSource`.

## Cache-first startup

1. **No login on launch.** librespot stores reusable credentials after the first OAuth login.
2. **UI from disk.** Home and collection screens are JSON snapshots, read synchronously for the
   first frame, then refreshed in the background. Covers come from Coil's 256 MB disk cache.
3. **Hot resume.** The last queue and position are restored *paused and prepared* when the
   playback service starts. When the track's audio file and key are on disk, ExoPlayer is ready
   before you tap play, and no session is needed.
4. **Prefetch.** The next 3 tracks in the queue are downloaded fully (file + key) in the
   background.
5. **Warm session.** The Spotify session connects in parallel with the first frame, not after it.
6. **Low start threshold.** Playback starts with 250 ms buffered.

## Spotify Connect

Fastify is a real Connect device ("Fastify (<model>)") while the playback service runs (app open
or playing): the desktop app lists it, can transfer playback to it and control it, and the phone
can move playback anywhere, both ways.

- `rust/core/src/connect.rs`: one dealer connection per session (`dealer().start()` consumes the
  builder: never add a second one). It follows the device cluster (remote control) and, once
  `ConnectTarget` attaches the local player, registers as a player (`can_be_player`,
  controllable, not hidden). Without a player it's a hidden observer.
- Incoming: `hm://connect-state/v1/player/command` (+ volume pushes) -> `ConnectRequest` for Kotlin
  (`connect_next_request` / `connect_reply`); every request gets a reply (10 s timeout = failure).
  Play/transfer contexts are resolved in Rust (playlist/album/collection like the library
  screens, else context-resolve with paging) and hydrated before Kotlin gets them.
- Outgoing: `ConnectTarget` publishes the ExoPlayer state (track, position + timestamp, prev/next in
  play order, options, volume) on every player event; the core PUTs it from its own task (serialized, never blocks
  commands). Queue uids are stable per media item (`uid` extra, `MediaItems.kt`), not indices.
  State goes to the core before a reply/activation (ordered `ops` channel), else others see the
  old track.
- Big contexts (> 300 tracks): only a window around the start is hydrated before replying (the
  sender times out otherwise); the rest arrive as stubs, filled by `Metadata` events.
- Active device: playing locally (or a successful play/transfer) makes the phone active (the
  other device stops). The server has to confirm it (cluster names us); after that (or 5 s
  pending) another active device in a cluster means it took over -> `Deactivated` -> phone
  pauses. Giving up uses `put_connect_state_inactive` like spirc. While active, cluster changes
  by other devices re-PUT our state (spirc's position-drift workaround).
- Dealer: FASTIFY PATCH in vendor `dealer/mod.rs` retries a failed url/token fetch; upstream
  ends the dealer for good (Connect silently dead while the session lives).
- Transfers: `connect_transfer(from, to)`; from this phone the core PUTs the state as active
  first, the server builds the transfer from it.
- `ConnectRemote` attaches the player UI to another device when it plays and the phone doesn't;
  `PlayerConnection` then routes mini player, player and queue there. Device picker = transfers.
  Re-decided on cluster and local changes; after a transfer, stale clusters naming the old
  device are ignored for 8 s.
- Pitfalls: server 403s commands the target rejects (pause while paused). Paused state must be
  `is_playing + is_paused + is_buffering` (like spirc), else the desktop greys out play. Never
  leave `context_uri` empty (falls back to the current track).
- Probe: `fastify-probe <dir> connect` prints the cluster, `cmd <device> pause|next|transfer <to>|...`
  sends one, `target <name> [secs] [track]` acts as a simulated player (with a track it becomes
  active; `PROBE_NO_ACTIVATE=1` refuses loads so it never takes playback from a device in use). Test with two probe dirs (copy `credentials/`, own `keys/device_id`), not the PC:
  PC playback is Rutger's, don't start/stop it without asking.
- Not reachable when the service is gone (app closed and not playing).
- Device switch: `ConnectRemote.switching` is set on tap (picker spinner, "Switching to …" in the
  player) and cleared once the cluster names the target, on failure, or after 8 s.
- Shuffle is per context (`ShuffleStore`): playing a playlist/album/artist starts in the mode it
  was last left in; page shuffle buttons only change that context.

## Jam

Spotify Jam over private `social-connect` endpoints (`rust/core/src/jam.rs`, top comment lists
them), UI in `JamSheet` (player bottom bar, Group icon), state in `JamController`.

- A Jam is bound to our Connect device: every call sends `local_device_id` + the dealer
  connection id, so the Connect hub is started first. POST/PUT without a body gets 411: send `{}`.
- Host (Premium): `current_or_new?activate=true&type=REMOTE`. Guests' skips/queue adds reach
  `ConnectTarget` as normal Connect commands. Invite = url-dispenser spotify.link, QR in the sheet.
- Guest: `POST v3/sessions/join`, "Listen on this phone" (`LISTEN_AND_CONTROL`, falls back to
  `CONTROL` on 403) or "Play on the host's speaker" (`CONTROL`: `ConnectRemote` attaches to the
  Jam's device). Listening here, Spotify transfers the Jam to us; our own skip/seek/play/queue add
  must go to the Jam (`jam_command`, `PlayerConnection` + `JamAwarePlayer` for notification and
  Bluetooth), else the phone drifts out of sync. Pause stays local.
- Pushes: `hm://social-connect/v2/session_update` {reason, session} on the Connect dealer bump a
  revision (`jam_watch`); the app re-reads the session.
- Invites: open.spotify.com/socialsession links open Fastify (unverified domain: enable under App
  info > Open by default), any Jam link can be shared to Fastify, or pasted in the sheet.
- Probe: `fastify-probe <dir> jam get|start|preview|join|leave|watch`. Testing with Rutger's
  account starts/ends a real Jam: ask first.

## UI rules

- Animations last at most 150 ms (`ANIM_MS` in `ui/Motion.kt`), everywhere in the app. Use `fast()` for every animationSpec instead of Compose/Material defaults. Exception until material3 1.5 is stable: Material's internal motion (bottom sheets), because `MaterialTheme(motionScheme = …)` is internal in 1.4.0.
- Page/back transitions slide (pages horizontally, player/lyrics vertically), also for predictive back. No scaling.

## Build

Requirements: Rust (stable), `cargo-ndk`, Android SDK 37, NDK r30, JDK 17.

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

Gradle runs `rust/build-android.sh`, which builds `libfastify_core.so` with cargo-ndk and generates
the Kotlin bindings with UniFFI. Set `FASTIFY_ABIS="arm64-v8a x86_64"` to also build for the
emulator.

On the OpenClaw VM, build and test in the headless emulator with `android/scripts/emu`
(`EMU_OWNER=<id> scripts/emu up` … `scripts/emu down`). It holds a per-owner lease so
parallel agent threads don't fight over the emulator, and it never touches other emulators.
See the header of the script.

## Updates

The app updates itself from the CDN (`update/Updater.kt`), the way app stores do it:

- **Check** `version.json`: at launch, on foreground (at most every 30 min) and every 6 h from a
  WorkManager job (unmetered network, battery not low), also while the app is closed.
- **Download** the APK and verify its sha256.
- **Install** with `PackageInstaller.commitSessionAfterInstallConstraintsAreMet`: Android installs it
  without UI once Fastify is not in the foreground (not visible, not playing) and not in a call. No
  `setAppNotInteractingRequired`: it counts network traffic, and the librespot keep-alive never stops.
- Silent only when Fastify is the installer of record. The first update after a manual install needs
  a tap (Home card + notification), and so does every update Play Protect wants to check.

- `versionCode` is the commit count (`git rev-list --count HEAD`), so every commit is newer.
- Publish: `scripts/publish` uploads `fastify-<versionCode>.apk`, `fastify.apk` and then `version.json`,
  and verifies each one. It refuses dirty builds and versionCodes that aren't newer.
- Updates only install over the same signing key (`~/.local/share/android/debug.keystore` on the VM).
- Debug builds only update when built with `FASTIFY_UPDATE_URL=<base url>`.

`vergen` is pinned to 9.0.6 in `Cargo.lock`, because 9.1 breaks librespot-core 0.8's build script.

## Caveats

librespot is an unofficial client. Spotify protocol changes can break it, and using it is
against Spotify's terms of service.

## License

GPL-3.0 (see LICENSE). Song recognition (`rust/core/src/shazam/`) is ported from [SongRec](https://github.com/marin-m/SongRec) (GPL-3.0).
