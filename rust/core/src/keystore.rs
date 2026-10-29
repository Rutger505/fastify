//! Persistent index: track id -> (file id, audio key, format).
//! librespot caches audio files but not keys; with both on disk a track plays with zero network.

use std::fs;
use std::path::{Path, PathBuf};

use librespot_core::FileId;
use librespot_core::audio_key::AudioKey;
use librespot_metadata::audio::AudioFileFormat;
use serde::{Deserialize, Serialize};

use crate::CoreError;

#[derive(Clone, Serialize, Deserialize)]
pub struct KeyEntry {
    file_id: String,
    key: String,
    kbps: u32,
}

impl KeyEntry {
    pub fn new(file_id: FileId, key: AudioKey, format: AudioFileFormat) -> Self {
        let kbps = match format {
            AudioFileFormat::OGG_VORBIS_320 => 320,
            AudioFileFormat::OGG_VORBIS_160 => 160,
            _ => 96,
        };
        Self {
            file_id: hex::encode(file_id.0),
            key: hex::encode(key.0),
            kbps,
        }
    }

    pub fn file_id(&self) -> FileId {
        crate::file_id_from_hex(&self.file_id).unwrap_or(FileId([0; 20]))
    }

    pub fn key(&self) -> AudioKey {
        let mut k = [0u8; 16];
        if let Ok(raw) = hex::decode(&self.key) {
            if raw.len() == 16 {
                k.copy_from_slice(&raw);
            }
        }
        AudioKey(k)
    }

    pub fn bytes_per_second(&self) -> usize {
        self.kbps as usize * 1000 / 8
    }

    fn valid(&self) -> bool {
        crate::file_id_from_hex(&self.file_id).is_some()
            && hex::decode(&self.key).is_ok_and(|k| k.len() == 16)
    }
}

pub struct KeyStore {
    root: PathBuf,
}

impl KeyStore {
    pub fn open(root: PathBuf) -> Result<Self, CoreError> {
        fs::create_dir_all(&root)?;
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    fn path(&self, track_id: &str) -> Option<PathBuf> {
        // Base62 ids are alphanumeric; reject anything else to keep paths safe.
        (!track_id.is_empty() && track_id.chars().all(|c| c.is_ascii_alphanumeric()))
            .then(|| self.root.join(format!("{track_id}.json")))
    }

    pub fn get(&self, track_id: &str) -> Option<KeyEntry> {
        let data = fs::read(self.path(track_id)?).ok()?;
        serde_json::from_slice::<KeyEntry>(&data).ok().filter(KeyEntry::valid)
    }

    pub fn put(&self, track_id: &str, entry: &KeyEntry) -> Result<(), CoreError> {
        let path = self.path(track_id).ok_or_else(|| CoreError::other("bad track id"))?;
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, serde_json::to_vec(entry).map_err(CoreError::other)?)?;
        fs::rename(tmp, path)?;
        Ok(())
    }

    pub fn clear(&self) -> Result<(), CoreError> {
        for e in fs::read_dir(&self.root)?.flatten() {
            if e.path().extension().is_some_and(|x| x == "json") {
                let _ = fs::remove_file(e.path());
            }
        }
        Ok(())
    }

    /// Stable per-install device id, so Spotify sees one device instead of a new one per launch.
    pub fn device_id(&self) -> Result<String, CoreError> {
        let path = self.root.join("device_id");
        if let Ok(id) = fs::read_to_string(&path) {
            let id = id.trim().to_owned();
            if !id.is_empty() {
                return Ok(id);
            }
        }
        let mut raw = [0u8; 20];
        getrandom_fill(&mut raw);
        let id = hex::encode(raw);
        fs::write(&path, &id)?;
        Ok(id)
    }
}

fn getrandom_fill(buf: &mut [u8]) {
    // Good enough for a device id: hash time + address entropy through std's RandomState.
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    for chunk in buf.chunks_mut(8) {
        let mut h = RandomState::new().build_hasher();
        h.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos(),
        );
        let v = h.finish().to_le_bytes();
        chunk.copy_from_slice(&v[..chunk.len()]);
    }
}
