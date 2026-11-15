//! Core logging: logcat (tag fastify-core) plus, once Kotlin set a [LogSink], the app's own log
//! file. Logcat is a small shared ring buffer that's gone after an hour or a restart; the file
//! keeps Connect events next to the player's, in order, for Share logs.

use std::sync::Arc;

/// Kotlin's AppLog: appends a core line to the log file (not to logcat, the core does that).
#[uniffi::export(with_foreign)]
pub trait LogSink: Send + Sync {
    fn log(&self, level: String, target: String, message: String);
}

/// Core log lines also go to [sink] from now on. Call before creating FastifyCore, so its
/// startup lines land in the file too. No-op off Android (the probe has its own env_logger).
#[uniffi::export]
pub fn set_log_sink(sink: Arc<dyn LogSink>) {
    #[cfg(target_os = "android")]
    {
        *android::init().sink.write().unwrap() = Some(sink);
    }
    #[cfg(not(target_os = "android"))]
    drop(sink);
}

/// Installs the core logger once.
pub(crate) fn init() {
    #[cfg(target_os = "android")]
    android::init();
}

#[cfg(target_os = "android")]
mod android {
    use std::sync::{Arc, OnceLock, RwLock};

    use log::{Level, LevelFilter, Log, Metadata, Record};

    use super::LogSink;

    pub(super) struct CoreLogger {
        pub(super) sink: RwLock<Option<Arc<dyn LogSink>>>,
        logcat: android_logger::AndroidLogger,
    }

    impl Log for CoreLogger {
        fn enabled(&self, metadata: &Metadata) -> bool {
            metadata.level() <= Level::Info
        }

        fn log(&self, record: &Record) {
            if !self.enabled(record.metadata()) {
                return;
            }
            self.logcat.log(record);
            let Some(sink) = self.sink.read().unwrap().clone() else { return };
            let level = match record.level() {
                Level::Error => "E",
                Level::Warn => "W",
                _ => "I",
            };
            sink.log(level.into(), record.target().into(), record.args().to_string());
        }

        fn flush(&self) {}
    }

    static LOGGER: OnceLock<CoreLogger> = OnceLock::new();

    pub(super) fn init() -> &'static CoreLogger {
        let mut fresh = false;
        let logger = LOGGER.get_or_init(|| {
            fresh = true;
            CoreLogger {
                sink: RwLock::new(None),
                logcat: android_logger::AndroidLogger::new(
                    android_logger::Config::default()
                        .with_max_level(LevelFilter::Info)
                        .with_tag("fastify-core"),
                ),
            }
        });
        if fresh && log::set_logger(logger).is_ok() {
            log::set_max_level(LevelFilter::Info);
        }
        logger
    }
}
