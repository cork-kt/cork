use std::fmt::{Display, Formatter};
use std::io;

#[derive(Debug)]
pub enum CorkError {
    Io(io::Error),
    InvalidFormat(String),
    Unsupported(String),
    Archive(String),
    Codec(String),
}

impl Display for CorkError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(err) => write!(f, "I/O error: {err}"),
            Self::InvalidFormat(msg) => write!(f, "Invalid format: {msg}"),
            Self::Unsupported(msg) => write!(f, "Unsupported operation: {msg}"),
            Self::Archive(msg) => write!(f, "Archive error: {msg}"),
            Self::Codec(msg) => write!(f, "Codec error: {msg}"),
        }
    }
}

impl std::error::Error for CorkError {}

impl From<io::Error> for CorkError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<zip::result::ZipError> for CorkError {
    fn from(value: zip::result::ZipError) -> Self {
        Self::Archive(value.to_string())
    }
}

impl From<sevenz_rust2::Error> for CorkError {
    fn from(value: sevenz_rust2::Error) -> Self {
        Self::Archive(value.to_string())
    }
}
