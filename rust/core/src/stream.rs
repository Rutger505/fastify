use std::io::{Read, Seek, SeekFrom};
use std::sync::Mutex;

use librespot_audio::{AudioDecrypt, AudioFile};
use librespot_core::audio_key::AudioKey;

use crate::CoreError;

/// Spotify prefixes Ogg files with a 0xa7-byte header; the real Ogg stream starts after it.
const SPOTIFY_OGG_HEADER_END: u64 = 0xa7;

/// Decrypted, seekable view of one audio file. Reads block until the bytes are downloaded.
#[derive(uniffi::Object)]
pub struct TrackStream {
    inner: Mutex<AudioDecrypt<AudioFile>>,
    length: u64,
    cached: bool,
}

impl TrackStream {
    pub fn new(file: AudioFile, key: AudioKey, cached: bool) -> Result<Self, CoreError> {
        let total = file
            .get_stream_loader_controller()
            .map_err(CoreError::from)?
            .len() as u64;
        if let Ok(ctl) = file.get_stream_loader_controller() {
            ctl.set_stream_mode();
        }
        Ok(Self {
            inner: Mutex::new(AudioDecrypt::new(Some(key), file)),
            length: total.saturating_sub(SPOTIFY_OGG_HEADER_END),
            cached,
        })
    }
}

#[uniffi::export]
impl TrackStream {
    /// Length of the Ogg stream in bytes.
    pub fn length(&self) -> u64 {
        self.length
    }

    /// Whether this stream is served entirely from disk.
    pub fn is_cached(&self) -> bool {
        self.cached
    }

    /// Read up to `max` bytes at `offset` (relative to the Ogg stream). Empty result = end.
    pub fn read(&self, offset: u64, max: u32) -> Result<Vec<u8>, CoreError> {
        if offset >= self.length {
            return Ok(Vec::new());
        }
        let want = (max as u64).min(self.length - offset) as usize;
        let mut buf = vec![0u8; want];
        let mut inner = self.inner.lock().map_err(CoreError::other)?;
        inner.seek(SeekFrom::Start(SPOTIFY_OGG_HEADER_END + offset))?;
        let mut filled = 0;
        while filled < want {
            match inner.read(&mut buf[filled..])? {
                0 => break,
                n => filled += n,
            }
        }
        buf.truncate(filled);
        Ok(buf)
    }
}
