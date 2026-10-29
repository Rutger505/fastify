#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("not logged in")]
    NotLoggedIn,
    #[error("authentication failed: {msg}")]
    Auth { msg: String },
    #[error("network: {msg}")]
    Network { msg: String },
    #[error("not found: {msg}")]
    NotFound { msg: String },
    #[error("{msg}")]
    Other { msg: String },
}

impl CoreError {
    pub fn other(e: impl std::fmt::Display) -> Self {
        CoreError::Other { msg: e.to_string() }
    }
}

impl From<librespot_core::Error> for CoreError {
    fn from(e: librespot_core::Error) -> Self {
        use librespot_core::error::ErrorKind::*;
        let msg = e.to_string();
        match e.kind {
            Unauthenticated | PermissionDenied => CoreError::Auth { msg },
            NotFound => CoreError::NotFound { msg },
            Unavailable | DeadlineExceeded | Aborted | Cancelled => CoreError::Network { msg },
            _ => CoreError::Other { msg },
        }
    }
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::other(e)
    }
}
