//! Offline receive check: given raw transaction bytes, verify everything that can be verified
//! without the network, and find what it pays this wallet.
//!
//! - Structure: a v5/v6 transaction with only Orchard / Ironwood parts (no transparent, no
//!   Sapling spends), so nothing about it depends on chain lookups.
//! - Proofs and signatures: every Orchard-style proof, spend authorization and the binding
//!   signature, checked against the transaction's own sighash (the same check the PCZT
//!   extractor runs). Tampering with any byte the signatures cover fails here.
//! - Anchor: the tree root the spends prove against must be one this wallet has synced.
//!   Otherwise the result says `anchor_known: false`: the payment may still be fine (the sender
//!   synced later than us), but we could not check it, so it must not be shown as verified.
//! - Payment: trial-decrypt with the wallet's viewing keys; only *incoming* outputs to the chosen
//!   account count.
//! - Nullifiers: returned for the double-spend gossip.
//!
//! What it cannot check offline: whether a nullifier is already spent on chain.

use std::panic::{AssertUnwindSafe, catch_unwind};

use anyhow::{Context, anyhow, bail, ensure};
use orchard::{Anchor, bundle::Authorized, tree::MerkleHashOrchard};
use rand::{rand_core::UnwrapErr, rngs::SysRng};
use serde::Serialize;
use shardtree::{ShardTree, store::ShardStore};
use zcash_client_backend::{
    TransferType,
    data_api::{WalletCommitmentTrees, WalletRead},
    decrypt_transaction,
};
use zcash_primitives::transaction::Transaction;
use zcash_protocol::{
    consensus::{BlockHeight, BranchId},
    memo::Memo,
    value::ZatBalance,
};

use crate::WalletStore;

#[derive(Debug, Clone, Serialize)]
pub struct IncomingOutput {
    pub account_uuid: String,
    pub pool: &'static str,
    pub output_index: usize,
    pub value: u64,
    pub memo: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ReceivedPayment {
    pub txid: String,
    pub expiry_height: u32,
    /// All proofs and signatures verified.
    pub proofs_and_signatures_ok: bool,
    /// The spends' anchor is a tree root this wallet has synced.
    pub anchor_known: bool,
    /// Incoming outputs to the receiving account (empty: not for this wallet/account).
    pub incoming: Vec<IncomingOutput>,
    pub amount: u64,
    /// Spend nullifiers (hex), for double-spend gossip.
    pub nullifiers: Vec<String>,
}

impl ReceivedPayment {
    pub fn is_for_us(&self) -> bool {
        !self.incoming.is_empty()
    }

    /// Safe to show as "received, not yet settled".
    pub fn is_verified(&self) -> bool {
        self.proofs_and_signatures_ok && self.anchor_known && self.is_for_us()
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Verifying keys are expensive to build (about a second), so keep one per circuit version.
fn verifying_key(version: orchard::circuit::OrchardCircuitVersion) -> &'static orchard::circuit::VerifyingKey {
    static KEYS: std::sync::Mutex<Vec<(String, &'static orchard::circuit::VerifyingKey)>> =
        std::sync::Mutex::new(Vec::new());
    let tag = format!("{version:?}");
    let mut keys = KEYS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((_, vk)) = keys.iter().find(|(t, _)| *t == tag) {
        return vk;
    }
    let vk: &'static _ = Box::leak(Box::new(orchard::circuit::VerifyingKey::build(version)));
    keys.push((tag, vk));
    vk
}

/// Builds the verifying keys for the current (NU6.3) Orchard and Ironwood circuits now, so the
/// first payment a receiver checks does not wait for them (~1 s on desktop, longer on phones).
pub fn prewarm_verifier() {
    for pool in [orchard::ValuePool::Orchard, orchard::ValuePool::Ironwood] {
        if let Some(v) =
            zcash_primitives::transaction::components::orchard::bundle_version_for_branch(BranchId::Nu6_3, pool)
        {
            verifying_key(v.circuit_version());
        }
    }
}

fn verify_bundle(bundle: &orchard::Bundle<Authorized, ZatBalance>, sighash: [u8; 32]) -> bool {
    let vk = verifying_key(bundle.bundle_version().circuit_version());
    let mut validator = orchard::bundle::BatchValidator::new(vk);
    validator.add_bundle(bundle, sighash).is_ok() && validator.validate(UnwrapErr(SysRng))
}

impl WalletStore {
    /// Verifies a payment received as raw bytes. `account_uuid` picks the receiving account;
    /// `None` counts incoming outputs to any account of this wallet.
    ///
    /// Returns `Err` for anything that is not a well-formed, fully shielded transaction with
    /// valid proofs and signatures. Never panics on malformed input.
    pub fn verify_payment(&mut self, raw: &[u8], account_uuid: Option<&str>) -> anyhow::Result<ReceivedPayment> {
        match catch_unwind(AssertUnwindSafe(|| self.verify_payment_inner(raw, account_uuid))) {
            Ok(result) => result,
            Err(_) => Err(anyhow!("malformed transaction (parser panicked)")),
        }
    }

    fn verify_payment_inner(&mut self, raw: &[u8], account_uuid: Option<&str>) -> anyhow::Result<ReceivedPayment> {
        let tx = Transaction::read(raw, BranchId::Nu6_3).map_err(|e| anyhow!("not a transaction: {e}"))?;
        let mut reencoded = Vec::new();
        tx.write(&mut reencoded)?;
        ensure!(reencoded == raw, "transaction has trailing or non-canonical bytes");
        ensure!(tx.transparent_bundle().is_none(), "transparent inputs/outputs are not accepted offline");
        ensure!(
            tx.sapling_bundle().is_none_or(|b| b.shielded_spends().is_empty()),
            "Sapling spends are not accepted offline"
        );
        let bundles: Vec<(&'static str, &orchard::Bundle<Authorized, ZatBalance>)> = [
            ("orchard", tx.orchard_bundle()),
            ("ironwood", tx.ironwood_bundle()),
        ]
        .into_iter()
        .filter_map(|(p, b)| b.map(|b| (p, b)))
        .collect();
        ensure!(!bundles.is_empty(), "no shielded spends");

        // Proofs and signatures against the transaction's shielded sighash. With no transparent
        // inputs (checked above), ZIP 244 S.2 makes the signature digest identical to the txid
        // digest, so the sighash is the txid itself.
        let sighash: [u8; 32] = *tx.txid().as_ref();
        let ok = bundles.iter().all(|(_, b)| verify_bundle(b, sighash));
        if !ok {
            bail!("invalid proof or signature");
        }

        // Anchors against roots this wallet has synced.
        let mut anchor_known = true;
        for (pool, b) in &bundles {
            anchor_known &= self.anchor_is_known(pool, b.anchor())?;
        }

        // What it pays us.
        let params = *self.db.params();
        let ufvks = self.db.get_unified_full_viewing_keys()?;
        let tip = self.db.chain_height()?;
        let decrypted = decrypt_transaction(&params, None, tip, &tx, &ufvks);
        let mut incoming = Vec::new();
        for (pool, outputs) in [("orchard", decrypted.orchard_outputs()), ("ironwood", decrypted.ironwood_outputs())] {
            for o in outputs {
                let acct = o.account().expose_uuid().to_string();
                if o.transfer_type() != TransferType::Incoming || account_uuid.is_some_and(|a| a != acct) {
                    continue;
                }
                let memo = match Memo::try_from(o.memo()) {
                    Ok(Memo::Text(t)) => Some(t.to_string()),
                    _ => None,
                };
                incoming.push(IncomingOutput {
                    account_uuid: acct,
                    pool,
                    output_index: o.index(),
                    value: o.note().0.value().inner(),
                    memo,
                });
            }
        }
        let nullifiers = bundles
            .iter()
            .flat_map(|(_, b)| b.actions().iter().map(|a| hex(&a.nullifier().to_bytes())))
            .collect();
        Ok(ReceivedPayment {
            txid: tx.txid().to_string(),
            expiry_height: u32::from(tx.expiry_height()),
            proofs_and_signatures_ok: true,
            anchor_known,
            amount: incoming.iter().map(|o| o.value).sum(),
            incoming,
            nullifiers,
        })
    }

    /// Whether `anchor` is the tree root at one of the checkpoints this wallet has retained.
    fn anchor_is_known(&mut self, pool: &str, anchor: &Anchor) -> anyhow::Result<bool> {
        let table = match pool {
            "ironwood" => "ironwood_tree_checkpoints",
            _ => "orchard_tree_checkpoints",
        };
        let ids: Vec<u32> = {
            let conn = self.read_conn()?;
            let mut stmt = conn.prepare(&format!("SELECT checkpoint_id FROM {table} ORDER BY checkpoint_id DESC"))?;
            stmt.query_map([], |r| r.get(0))?.collect::<Result<_, _>>()?
        };
        let found = match pool {
            "ironwood" => self
                .db
                .with_ironwood_tree_mut::<_, _, anyhow::Error>(|t| root_matches(t, &ids, anchor))?
                .context("wallet has no Ironwood tree")?,
            _ => self
                .db
                .with_orchard_tree_mut::<_, _, anyhow::Error>(|t| root_matches(t, &ids, anchor))?,
        };
        Ok(found)
    }
}

fn root_matches<S>(tree: &mut ShardTree<S, 32, 16>, ids: &[u32], anchor: &Anchor) -> anyhow::Result<bool>
where
    S: ShardStore<H = MerkleHashOrchard, CheckpointId = BlockHeight>,
    S::Error: std::fmt::Debug,
{
    for id in ids {
        let root = match tree.root_at_checkpoint_id(&BlockHeight::from(*id)) {
            Ok(root) => root,
            // The wallet does not hold enough of the tree to compute this root (common for
            // checkpoints below what it has fully scanned): it cannot vouch for it, so it is
            // simply not a match.
            Err(shardtree::error::ShardTreeError::Query(_)) => None,
            Err(e) => return Err(anyhow!("reading tree root: {e:?}")),
        };
        if root.is_some_and(|r| Anchor::from(r) == *anchor) {
            return Ok(true);
        }
    }
    Ok(false)
}
