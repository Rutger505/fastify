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
