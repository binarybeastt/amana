//! Note status for offline spending.
//!
//! Status is derived from the wallet database instead of being kept in a second list that could
//! drift from it:
//! - `pending`: received in a transaction that is not mined yet (e.g. change from an offline
//!   payment); it has no witness, so it cannot be spent until that transaction is mined;
//! - `available`: mined, and no transaction spends it;
//! - `spent-offline`: a stored transaction that is not mined (and cannot expire) spends it;
//! - `spent`: a mined transaction spends it;
//! - `dust`: unspent but worth at most the ZIP 317 marginal fee (5,000 zatoshis), so spending it
//!   costs at least what it is worth. The wallet leaves such "uneconomic" notes out of its
//!   balance, and this crate never selects them.
//!
//! Signing a payment stores it in the wallet before any bytes leave the phone
//! ([`WalletStore::store_signed`]). That one SQLite transaction moves its notes to
//! `spent-offline`, and zcash_client_sqlite's note selection skips them from then on, so neither
//! this crate nor the SDK can pick them again. Expiry height 0 means the stored transaction never
//! expires, so a `spent-offline` note never silently becomes `available` again.

use std::fmt;

use anyhow::{Context, bail};
use rusqlite::{OptionalExtension, named_params};
use serde::Serialize;
use zcash_client_backend::data_api::{WalletRead, wallet::extract_and_store_transaction_from_pczt};
use zcash_client_sqlite::util::SystemClock;
use zcash_protocol::TxId;

use crate::wallet::WalletStore;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum NoteStatus {
    Pending,
    Available,
    SpentOffline,
    Spent,
    Dust,
}

/// Notes worth this much or less are dust (ZIP 317 marginal fee, the wallet's "uneconomic" line).
pub const DUST_LIMIT: u64 = 5_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NoteEvent {
    /// The transaction that created the note was mined.
    Confirmed,
    /// A payment spending the note was signed and stored (write-before-send).
    SignedOffline,
    /// A mined transaction spending the note was seen on chain: ours, or a conflicting one.
    SeenMined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IllegalTransition {
    pub from: NoteStatus,
    pub event: NoteEvent,
}

impl fmt::Display for IllegalTransition {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "illegal note transition: {:?} on a {:?} note", self.event, self.from)
    }
}

impl std::error::Error for IllegalTransition {}

impl NoteStatus {
    /// The only legal moves are `pending → available` (confirmed), `available → spent-offline`
    /// (signed), and `→ spent` (seen mined) from `available`, `spent-offline` or `spent`.
    /// Nothing leads back to `available` once it is left, and only an `available` note can be
    /// signed: never one that is pending or already signed. A dust note stays dust when
    /// confirmed and can never be signed; another wallet (the SDK) may still spend it.
    pub fn apply(self, event: NoteEvent) -> Result<Self, IllegalTransition> {
        use NoteEvent::*;
        use NoteStatus::*;
        match (self, event) {
            (Pending, Confirmed) => Ok(Available),
            (Dust, Confirmed) => Ok(Dust),
            (Available, SignedOffline) => Ok(SpentOffline),
            (Available | SpentOffline | Spent | Dust, SeenMined) => Ok(Spent),
            _ => Err(IllegalTransition { from: self, event }),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NoteInfo {
    pub account_uuid: String,
    pub pool: &'static str,
    /// Transaction that created the note (display byte order).
    pub txid: String,
    pub output_index: u32,
    pub value: u64,
    /// Not known until the note's transaction is mined.
    pub nullifier: Option<String>,
    pub mined_height: Option<u32>,
    /// The note has a position in the commitment tree, so a witness can be built for it.
    pub has_tree_position: bool,
    pub status: NoteStatus,
    /// Transaction that spends the note, if any (display byte order).
    pub spent_by: Option<String>,
}

const POOLS: [&str; 2] = ["ironwood", "orchard"];

/// Status query for one pool's notes. Zero-value notes (bundle padding) are left out. A note
/// or a pending spend only counts while its transaction can still be mined: mined already,
/// never-expiring (0 or unknown), or expiring at or after the next block.
fn notes_query(pool: &str) -> String {
    format!(
        "SELECT a.uuid, t.txid, n.action_index, n.value, n.nf, t.mined_height,
                n.commitment_tree_position IS NOT NULL,
                (SELECT st.txid FROM {pool}_received_note_spends s
                   JOIN transactions st ON st.id_tx = s.transaction_id
                  WHERE s.{pool}_received_note_id = n.id AND st.mined_height IS NOT NULL
                  LIMIT 1),
                (SELECT st.txid FROM {pool}_received_note_spends s
                   JOIN transactions st ON st.id_tx = s.transaction_id
                  WHERE s.{pool}_received_note_id = n.id AND st.mined_height IS NULL
                    AND (st.expiry_height IS NULL OR st.expiry_height = 0
                         OR st.expiry_height >= :target_height)
                  LIMIT 1)
           FROM {pool}_received_notes n
           JOIN transactions t ON t.id_tx = n.transaction_id
           JOIN accounts a ON a.id = n.account_id
          WHERE n.value > 0
            AND (t.mined_height IS NOT NULL
             OR t.expiry_height IS NULL OR t.expiry_height = 0
             OR t.expiry_height >= :target_height)"
    )
}

fn display_hex(internal: &[u8]) -> String {
    internal.iter().rev().map(|b| format!("{b:02x}")).collect()
}

fn plain_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn uuid_string(b: &[u8]) -> String {
    let h = plain_hex(b);
    if h.len() != 32 {
        return h;
    }
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

impl WalletStore {
    /// Every shielded note the wallet can spend from, with its derived status.
    pub fn notes(&self) -> anyhow::Result<Vec<NoteInfo>> {
        let target_height = self.db.chain_height()?.map_or(0, |h| u32::from(h) + 1);
        let conn = self.read_conn()?;
        let mut out = Vec::new();
        for pool in POOLS {
            let mut stmt = conn.prepare(&notes_query(pool))?;
            let rows = stmt.query_map(named_params! { ":target_height": target_height }, |r| {
                let mined_spend: Option<Vec<u8>> = r.get(7)?;
                let pending_spend: Option<Vec<u8>> = r.get(8)?;
                let mined_height: Option<u32> = r.get(5)?;
                let value = r.get::<_, i64>(3)? as u64;
                let (status, spent_by) = match (&mined_spend, &pending_spend) {
                    (Some(t), _) => (NoteStatus::Spent, Some(display_hex(t))),
                    (None, Some(t)) => (NoteStatus::SpentOffline, Some(display_hex(t))),
                    (None, None) if value <= DUST_LIMIT => (NoteStatus::Dust, None),
                    (None, None) if mined_height.is_none() => (NoteStatus::Pending, None),
                    (None, None) => (NoteStatus::Available, None),
                };
                Ok(NoteInfo {
                    account_uuid: uuid_string(&r.get::<_, Vec<u8>>(0)?),
                    pool,
                    txid: display_hex(&r.get::<_, Vec<u8>>(1)?),
                    output_index: r.get(2)?,
                    value,
                    nullifier: r.get::<_, Option<Vec<u8>>>(4)?.map(|nf| plain_hex(&nf)),
                    mined_height,
                    has_tree_position: r.get(6)?,
                    status,
                    spent_by,
                })
            })?;
            for row in rows {
                out.push(row?);
            }
        }
        out.sort_by(|a, b| (&a.account_uuid, a.mined_height, &a.txid, a.output_index)
            .cmp(&(&b.account_uuid, b.mined_height, &b.txid, b.output_index)));
        Ok(out)
    }

    /// Status of the wallet's note with this nullifier, or `None` if it is not one of ours.
    pub fn status_of(&self, nullifier: &[u8; 32]) -> anyhow::Result<Option<NoteInfo>> {
        let hex = plain_hex(nullifier);
        Ok(self.notes()?.into_iter().find(|n| n.nullifier.as_deref() == Some(hex.as_str())))
    }

    /// Refuses unless every one of our notes among `nullifiers` is `available`. Nullifiers that
    /// are not ours (dummy spends) are ignored: we could not have signed for them anyway.
    pub fn check_can_sign(&self, nullifiers: &[[u8; 32]]) -> anyhow::Result<()> {
        let notes = self.notes()?;
        for nf in nullifiers {
            let hex = plain_hex(nf);
            if let Some(note) = notes.iter().find(|n| n.nullifier.as_deref() == Some(hex.as_str())) {
                note.status.apply(NoteEvent::SignedOffline).with_context(|| {
                    format!(
                        "refusing to sign twice: note {}:{} ({} zats) is {:?}, spent by {}",
                        note.txid,
                        note.output_index,
                        note.value,
                        note.status,
                        note.spent_by.as_deref().unwrap_or("?"),
                    )
                })?;
            }
        }
        Ok(())
    }

    /// Write-before-send: checks the signed PCZT's spends, then verifies and stores the
    /// finished transaction in the wallet so its notes become `spent-offline`. Returns the txid
    /// and the raw transaction bytes to hand to the receiver.
    pub fn store_signed(&mut self, pczt_bytes: &[u8]) -> anyhow::Result<(TxId, Vec<u8>)> {
        let pczt = pczt::Pczt::parse(pczt_bytes).map_err(|e| anyhow::anyhow!("bad PCZT: {e:?}"))?;
        let effects = pczt
            .clone()
            .into_effects()
            .map_err(|e| anyhow::anyhow!("cannot read PCZT effects: {e:?}"))?;
        let mut nullifiers = Vec::new();
        for bundle in [effects.ironwood_bundle(), effects.orchard_bundle()].into_iter().flatten() {
            nullifiers.extend(bundle.actions().iter().map(|a| a.nullifier().to_bytes()));
        }
        if effects.sapling_bundle().is_some_and(|b| !b.shielded_spends().is_empty()) {
            bail!("Sapling spends are not supported");
        }
        self.check_can_sign(&nullifiers)?;

        let mut rng = self.rng();
        let txid = extract_and_store_transaction_from_pczt::<_, ()>(
            &mut self.db,
            &SystemClock,
            &mut rng,
            pczt,
            None,
            None,
        )
        .map_err(|e| anyhow::anyhow!("failed to verify and store transaction: {e:?}"))?;

        // Read the stored bytes directly: zcash_client_sqlite 0.22's `get_transaction` cannot
        // parse an unmined expiry-0 transaction ("Consensus branch ID not known"; fixed upstream
        // in librustzcash 90c1cc86, after the SDK's version).
        let raw = self.raw_transaction(&txid)?.context("stored transaction not found")?;
        Ok((txid, raw))
    }

    /// Our stored transactions (they spend our notes) that are not mined yet but still can be
    /// (never-expiring or not yet expired): payments to rebroadcast. Incoming transactions the
    /// wallet saw in the mempool are not listed. Returns (txid in display order, expiry height,
    /// raw bytes).
    pub fn pending_payments(&self) -> anyhow::Result<Vec<(String, u32, Vec<u8>)>> {
        let target_height = self.db.chain_height()?.map_or(0, |h| u32::from(h) + 1);
        let conn = self.read_conn()?;
        let mut stmt = conn.prepare(
            "SELECT txid, IFNULL(expiry_height, 0), raw FROM transactions
              WHERE mined_height IS NULL AND raw IS NOT NULL
                AND (expiry_height IS NULL OR expiry_height = 0 OR expiry_height >= ?1)
                AND id_tx IN (SELECT transaction_id FROM ironwood_received_note_spends
                              UNION SELECT transaction_id FROM orchard_received_note_spends
                              UNION SELECT transaction_id FROM sapling_received_note_spends)
              ORDER BY id_tx",
        )?;
        let rows = stmt.query_map([target_height], |r| {
            Ok((display_hex(&r.get::<_, Vec<u8>>(0)?), r.get(1)?, r.get(2)?))
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Online transactions (expiry > 0) that spend our notes, are not mined, and can still be
    /// mined: e.g. a "prepare for offline" split waiting to be mined. Txids in display order.
    /// The app must not start a second split while one is listed here.
    pub fn in_flight_online_txs(&self) -> anyhow::Result<Vec<String>> {
        let target_height = self.db.chain_height()?.map_or(0, |h| u32::from(h) + 1);
        let conn = self.read_conn()?;
        let mut out = Vec::new();
        for pool in POOLS {
            let mut stmt = conn.prepare(&format!(
                "SELECT DISTINCT t.txid FROM {pool}_received_note_spends s
                   JOIN transactions t ON t.id_tx = s.transaction_id
                  WHERE t.mined_height IS NULL AND t.expiry_height > 0 AND t.expiry_height >= ?1"
            ))?;
            for row in stmt.query_map([target_height], |r| r.get::<_, Vec<u8>>(0))? {
                let txid = display_hex(&row?);
                if !out.contains(&txid) {
                    out.push(txid);
                }
            }
        }
        Ok(out)
    }

    /// Height the wallet saw this transaction mined at (txid in display order), or `None` if it
    /// is unknown or not mined yet.
    pub fn mined_height(&self, txid_display: &str) -> anyhow::Result<Option<u32>> {
        let mut internal = Vec::with_capacity(32);
        for i in (0..txid_display.len()).step_by(2).rev() {
            internal.push(u8::from_str_radix(txid_display.get(i..i + 2).context("bad txid")?, 16)?);
        }
        anyhow::ensure!(internal.len() == 32, "bad txid {txid_display}");
        let conn = self.read_conn()?;
        Ok(conn
            .query_row("SELECT mined_height FROM transactions WHERE txid = ?1", [internal], |r| r.get(0))
            .optional()?
            .flatten())
    }

    /// Raw bytes of a stored transaction, if the wallet has it.
    pub fn raw_transaction(&self, txid: &TxId) -> anyhow::Result<Option<Vec<u8>>> {
        let conn = self.read_conn()?;
        Ok(conn
            .query_row(
                "SELECT raw FROM transactions WHERE txid = ?1 AND raw IS NOT NULL",
                [txid.as_ref().as_slice()],
                |r| r.get(0),
            )
            .optional()?)
    }
}
