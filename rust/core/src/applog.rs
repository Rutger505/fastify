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

/// Level a core line gets in the app log: librespot's per-attempt and follow-up lines are
/// debug (logcat only, not in the file). Info and up go to the file, where Kotlin's AppLog
/// aggregates repeats (README "Logging").
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn file_level(target: &str, message: &str, level: log::Level) -> log::Level {
    const DEBUG: &[(&str, &str)] = &[
        // Every AP attempt; "Authenticated as" / "Tried too many access points" say how it ended.
        ("librespot_core::session", "Connecting to AP"),
        ("librespot_core::session", "Try another access point"),
        ("librespot_core::session", "Received unexpected Ping"),
        ("librespot_core::session", "Country:"),
        ("librespot_core::spclient", "Resolved "),
        // Always right after "Resolve access points error".
        (
            "librespot_core::apresolve",
            "Failed to resolve all access points",
        ),
        // Follows every broken websocket ("Websocket connection failed" says why).
        ("librespot_core::dealer", "Error while closing websocket"),
    ];
    if DEBUG
        .iter()
        .any(|(t, m)| target == *t && message.starts_with(m))
    {
        log::Level::Debug
    } else {
        level
    }
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
            metadata.level() <= max_level(metadata.target())
        }

        fn log(&self, record: &Record) {
            if !self.enabled(record.metadata()) {
                return;
            }
            let message = record.args().to_string();
            let level = super::file_level(record.target(), &message, record.level());
            if level == record.level() {
                self.logcat.log(record);
            } else {
                self.logcat.log(
                    &Record::builder()
                        .level(level)
                        .target(record.target())
                        .args(format_args!("{message}"))
                        .build(),
                );
            }
            if level > Level::Info {
                return;
            }
            let Some(sink) = self.sink.read().unwrap().clone() else {
                return;
            };
            let level = match level {
                Level::Error => "E",
                Level::Warn => "W",
                _ => "I",
            };
            sink.log(level.into(), record.target().into(), message);
        }

        fn flush(&self) {}
    }

    /// Our own debug lines reach logcat; librespot's only from info up.
    fn max_level(target: &str) -> Level {
        if target.starts_with("fastify_core") {
            Level::Debug
        } else {
            Level::Info
        }
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
                        .with_max_level(LevelFilter::Debug)
                        .with_tag("fastify-core"),
                ),
            }
        });
        if fresh && log::set_logger(logger).is_ok() {
            log::set_max_level(LevelFilter::Debug);
        }
        logger
    }
}

#[cfg(test)]
mod tests {
    use log::Level;

    use super::file_level;

    #[test]
    fn demotes_ap_attempts_only() {
        let ap = "Connecting to AP \"ap.spotify.com:443\"";
        assert_eq!(
            file_level("librespot_core::session", ap, Level::Info),
            Level::Debug
        );
        assert_eq!(
            file_level(
                "librespot_core::session",
                "Authenticated as 'x' !",
                Level::Info
            ),
            Level::Info
        );
        assert_eq!(
            file_level(
                "librespot_core::dealer",
                "Websocket connection failed: x",
                Level::Warn
            ),
            Level::Warn
        );
        assert_eq!(
            file_level("fastify_core::connect", "Connecting to AP", Level::Info),
            Level::Info
        );
    }
}
