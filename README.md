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

`vergen` is pinned to 9.0.6 in `Cargo.lock`, because 9.1 breaks librespot-core 0.8's build script.

## MVP scope

- OAuth login (PKCE, Custom Tab)
- Home: recently played, Liked Songs, playlists, albums
- Search: songs, albums, playlists
- Player: queue, shuffle, repeat, seek, gapless next-track preloading
- Media notification, lock screen, Bluetooth controls, playback resumption

Next: Spotify Connect, Android Auto, lyrics, full offline downloads, widget.

## Caveats

librespot is an unofficial client. Spotify protocol changes can break it, and using it is
against Spotify's terms of service.
