//! When to (re)connect the session. One coordinator for every caller: the session lock keeps a
//! single attempt in flight, this decides whether one may start. Background callers (Connect
//! and Jam watchers) wait here: for the network (Kotlin reports ConnectivityManager's default
//! network) and, after a failed attempt, an exponential backoff (1 s .. 60 s). The network coming
//! back (validated) ends the backoff right away.

use std::sync::Mutex;
use std::time::{Duration, Instant};

use tokio::sync::watch;

const BACKOFF_MIN: Duration = Duration::from_secs(1);
const BACKOFF_MAX: Duration = Duration::from_secs(60);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Net {
    /// Kotlin didn't report yet (probe, early start): assume online.
    Unknown,
    Down,
    Up {
        validated: bool,
    },
}

impl Net {
    /// A background attempt can succeed: validated internet (or nothing known).
    fn usable(self) -> bool {
        matches!(self, Net::Unknown | Net::Up { validated: true })
    }
}

struct State {
    net: Net,
    /// Failed attempts since the last success or network return.
    failures: u32,
    retry_at: Option<Instant>,
    /// Bumped per failed attempt: callers that waited for the lock share its outcome.
    failure_gen: u64,
    last_error: String,
}

pub(crate) struct Reconnect {
    state: Mutex<State>,
    /// Bumped on every change (network, attempt outcome): wakes waiters to re-check.
    tick: watch::Sender<u64>,
}

/// What a background caller should do now.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Gate {
    Attempt,
    WaitNetwork,
    Wait(Duration),
}

impl Reconnect {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(State {
                net: Net::Unknown,
                failures: 0,
                retry_at: None,
                failure_gen: 0,
                last_error: String::new(),
            }),
            tick: watch::channel(0).0,
        }
    }

    pub fn set_network(&self, available: bool, validated: bool) {
        let net = if available {
            Net::Up { validated }
        } else {
            Net::Down
        };
        let mut s = self.state.lock().unwrap();
        if s.net == net {
            return;
        }
        let came_back = !s.net.usable() && net.usable();
        log::debug!("network {:?} -> {:?}", s.net, net);
        s.net = net;
        if came_back {
            // Retry right away, not after whatever backoff piled up offline.
            s.failures = 0;
            s.retry_at = None;
        }
        drop(s);
        self.bump();
    }

    /// No network at all: an attempt would only fail DNS.
    pub fn offline(&self) -> bool {
        self.state.lock().unwrap().net == Net::Down
    }

    pub fn failure_gen(&self) -> u64 {
        self.state.lock().unwrap().failure_gen
    }

    pub fn last_error(&self) -> String {
        self.state.lock().unwrap().last_error.clone()
    }

    pub fn gate(&self) -> Gate {
        let s = self.state.lock().unwrap();
        gate(s.net, s.retry_at, Instant::now())
    }

    /// Records a failed attempt; returns the backoff before the next background one.
    pub fn failed(&self, error: &str) -> Duration {
        let mut s = self.state.lock().unwrap();
        s.failures += 1;
        let wait = backoff(s.failures);
        s.retry_at = Some(Instant::now() + wait);
        s.failure_gen += 1;
        s.last_error = error.to_owned();
        let first = s.failures == 1;
        let online = s.net.usable();
        drop(s);
        if first {
            log::warn!(
                "session connect failed: {error}; retrying {}",
                if online {
                    "with backoff"
                } else {
                    "when the network is back"
                }
            );
        } else {
            log::debug!(
                "session connect failed again: {error}; next in {} s",
                wait.as_secs()
            );
        }
        self.bump();
        wait
    }

    pub fn succeeded(&self) {
        let mut s = self.state.lock().unwrap();
        let failures = std::mem::take(&mut s.failures);
        s.retry_at = None;
        drop(s);
        if failures > 0 {
            log::info!("session reconnected after {failures} failed attempts");
        }
        self.bump();
    }

    /// Until [gate] may have changed: network change, an attempt's outcome, or the backoff
    /// running out.
    pub async fn changed(&self) {
        let mut rx = self.tick.subscribe();
        match self.gate() {
            Gate::Attempt => {}
            Gate::WaitNetwork => {
                let _ = rx.changed().await;
            }
            Gate::Wait(d) => {
                let _ = tokio::time::timeout(d, rx.changed()).await;
            }
        }
    }

    fn bump(&self) {
        self.tick.send_modify(|v| *v = v.wrapping_add(1));
    }
}

fn gate(net: Net, retry_at: Option<Instant>, now: Instant) -> Gate {
    if !net.usable() {
        return Gate::WaitNetwork;
    }
    match retry_at {
        Some(at) if at > now => Gate::Wait(at - now),
        _ => Gate::Attempt,
    }
}

fn backoff(failures: u32) -> Duration {
    let exp = failures.saturating_sub(1).min(16);
    (BACKOFF_MIN * 2u32.pow(exp)).min(BACKOFF_MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_doubles_up_to_the_cap() {
        let secs: Vec<u64> = (1..=9).map(|n| backoff(n).as_secs()).collect();
        assert_eq!(secs, vec![1, 2, 4, 8, 16, 32, 60, 60, 60]);
        assert_eq!(backoff(u32::MAX), BACKOFF_MAX);
    }

    #[test]
    fn waits_for_validated_network() {
        let now = Instant::now();
        assert_eq!(gate(Net::Down, None, now), Gate::WaitNetwork);
        assert_eq!(
            gate(Net::Up { validated: false }, None, now),
            Gate::WaitNetwork
        );
        assert_eq!(gate(Net::Up { validated: true }, None, now), Gate::Attempt);
        assert_eq!(gate(Net::Unknown, None, now), Gate::Attempt);
        let later = now + Duration::from_secs(5);
        assert_eq!(
            gate(Net::Unknown, Some(later), now),
            Gate::Wait(Duration::from_secs(5))
        );
        assert_eq!(gate(Net::Unknown, Some(now), later), Gate::Attempt);
    }

    #[test]
    fn network_return_ends_backoff() {
        let r = Reconnect::new();
        r.set_network(true, true);
        r.failed("dns");
        r.failed("dns");
        assert!(matches!(r.gate(), Gate::Wait(_)));
        r.set_network(false, false);
        assert_eq!(r.gate(), Gate::WaitNetwork);
        assert!(r.offline());
        r.set_network(true, false);
        assert_eq!(r.gate(), Gate::WaitNetwork);
        r.set_network(true, true);
        assert_eq!(r.gate(), Gate::Attempt);
        assert_eq!(r.failure_gen(), 2);
        assert_eq!(r.last_error(), "dns");
    }

    #[test]
    fn changed_wakes_on_network_return() {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let r = std::sync::Arc::new(Reconnect::new());
            r.set_network(false, false);
            let waiter = {
                let r = r.clone();
                tokio::spawn(async move { r.changed().await })
            };
            tokio::time::sleep(Duration::from_millis(20)).await;
            r.set_network(true, true);
            tokio::time::timeout(Duration::from_secs(1), waiter)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(r.gate(), Gate::Attempt);
        });
    }
}
