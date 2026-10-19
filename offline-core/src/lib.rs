//! Zcash logic for offline shielded payments.
//!
//! This crate never touches the network. Syncing and broadcasting are done by the Android SDK
//! (or zcash-devtool on desktop); `offline-core` reads the synced wallet database and does the
//! work that must happen with no connection.

#[cfg(feature = "desktop")]
pub mod devtool_keys;
pub mod notes;
pub mod pay;
pub mod receive;
pub mod select;
pub mod split;
pub mod wallet;

pub use notes::{NoteEvent, NoteInfo, NoteStatus};
pub use wallet::{AccountStatus, Status, WalletStore};
