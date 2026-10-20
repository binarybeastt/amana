//! Access to a synced `zcash_client_sqlite` wallet database.

use std::path::{Path, PathBuf};

use anyhow::{Context, anyhow, bail};
use serde::Serialize;
use zcash_client_backend::data_api::{Account as _, WalletRead, wallet::ConfirmationsPolicy};
use zcash_client_sqlite::{WalletDb, util::SystemClock};
use rand::{rand_core::UnwrapErr, rngs::SysRng};
use zcash_protocol::consensus::Network;

/// File name of the wallet database inside a wallet directory (same as zcash-devtool).
pub const DATA_DB: &str = "data.sqlite";

/// How long to wait for another connection's lock before giving up.
pub const BUSY_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15);

/// Parses `test` / `main` into consensus parameters.
pub fn parse_network(s: &str) -> anyhow::Result<Network> {
    match s {
        "test" => Ok(Network::TestNetwork),
        "main" => Ok(Network::MainNetwork),
        other => bail!("unknown network {other:?}: expected \"test\" or \"main\""),
    }
}

/// Balances for one account, in zatoshis. "Spendable" uses the default confirmations policy,
/// so these numbers match `zcash-devtool wallet balance`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct AccountStatus {
    pub account_uuid: String,
    pub name: Option<String>,
    /// Height the account's scanning starts from (as stored in the wallet).
    pub birthday_height: u32,
    pub total: u64,
    pub sapling_spendable: u64,
    pub orchard_spendable: u64,
    pub ironwood_spendable: u64,
    pub transparent_spendable: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Status {
    /// Chain tip the wallet last synced to; `None` if it has never synced.
    pub chain_tip_height: Option<u32>,
    pub accounts: Vec<AccountStatus>,
}

pub(crate) type Db = WalletDb<rusqlite::Connection, Network, SystemClock, UnwrapErr<SysRng>>;

/// An open wallet database.
pub struct WalletStore {
    pub(crate) db: Db,
    path: PathBuf,
}

impl WalletStore {
    /// Opens `<wallet_dir>/data.sqlite`. Fails if the file does not exist: this never creates
    /// or migrates a wallet.
    pub fn open(wallet_dir: &Path, network: Network) -> anyhow::Result<Self> {
        Self::open_file(&wallet_dir.join(DATA_DB), network)
    }

    /// Opens a wallet database file directly (the Android SDK names it `data.sqlite3`). Fails if
    /// it does not exist: this never creates or migrates a wallet. Waits up to [`BUSY_TIMEOUT`]
    /// for a lock held by another process (the SDK's sync) instead of failing at once.
    pub fn open_file(path: &Path, network: Network) -> anyhow::Result<Self> {
        if !path.is_file() {
            bail!("no wallet database at {}", path.display());
        }
        let conn = rusqlite::Connection::open(path)
            .with_context(|| format!("opening wallet database {}", path.display()))?;
        conn.busy_timeout(BUSY_TIMEOUT)?;
        // Same setup as `WalletDb::for_path`: its queries use the `rarray` virtual table.
        rusqlite::vtab::array::load_module(&conn)?;
        let db = WalletDb::from_connection(conn, network, SystemClock, UnwrapErr(SysRng));
        Ok(Self { db, path: path.to_owned() })
    }

    /// A separate read-only connection for queries the wallet API does not offer.
    pub(crate) fn read_conn(&self) -> anyhow::Result<rusqlite::Connection> {
        let conn = rusqlite::Connection::open_with_flags(
            &self.path,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        conn.busy_timeout(BUSY_TIMEOUT)?;
        Ok(conn)
    }

    pub(crate) fn rng(&self) -> UnwrapErr<SysRng> {
        UnwrapErr(SysRng)
    }

    /// Balances for every account, ordered by account UUID.
    pub fn status(&self) -> anyhow::Result<Status> {
        let summary = self.db.get_wallet_summary(ConfirmationsPolicy::default())?;
        let mut accounts = Vec::new();
        for id in self.db.get_account_ids()? {
            let account = self
                .db
                .get_account(id)?
                .ok_or_else(|| anyhow!("account {id:?} listed but not found"))?;
            let balance = summary.as_ref().and_then(|s| s.account_balances().get(&id));
            let (total, sapling, orchard, ironwood, transparent) = match balance {
                Some(b) => (
                    b.total().into_u64(),
                    b.sapling_balance().spendable_value().into_u64(),
                    b.orchard_balance().spendable_value().into_u64(),
                    b.ironwood_balance().spendable_value().into_u64(),
                    b.unshielded_balance().spendable_value().into_u64(),
                ),
                None => (0, 0, 0, 0, 0),
            };
            accounts.push(AccountStatus {
                account_uuid: id.expose_uuid().to_string(),
                name: account.name().map(str::to_owned),
                birthday_height: u32::from(self.db.get_account_birthday(id)?),
                total,
                sapling_spendable: sapling,
                orchard_spendable: orchard,
                ironwood_spendable: ironwood,
                transparent_spendable: transparent,
            });
        }
        accounts.sort_by(|a, b| a.account_uuid.cmp(&b.account_uuid));
        Ok(Status {
            chain_tip_height: summary.map(|s| u32::from(s.chain_tip_height())),
            accounts,
        })
    }
}
