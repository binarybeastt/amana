//! The offline payment builder: amount and address in, signed raw transaction bytes out, with no
//! network.
//!
//! 1. Pick notes with [`crate::select`] (exact payment first, fee-sized notes included).
//! 2. Hand exactly those notes to librustzcash's proposal code: every other available note in
//!    the account is locked for the moment, so its input selector can only see ours. Our pick is
//!    minimal (no smaller set pays), so the library must use all of it; we check that it did.
//! 3. Create the PCZT with expiry height 0 (never expires), prove, sign.
//! 4. Store it in the wallet (write-before-send, [`WalletStore::store_signed`]) and return the
//!    stored bytes for the receiver.

use std::{collections::BTreeSet, str::FromStr};

use anyhow::{Context, anyhow, bail, ensure};
use pczt::roles::{prover::Prover, signer::Signer, verifier::Verifier};
use rand::{rand_core::UnwrapErr, rngs::SysRng};
use serde::Serialize;
use zcash_address::ZcashAddress;
use zcash_client_backend::{
    data_api::{
        Account as _, OutputLockStore, WalletRead,
        locking::LockOwner,
        wallet::{
            ConfirmationsPolicy, create_pczt_from_proposal,
            input_selection::{GreedyInputSelector, SpendPolicy},
            propose_transfer,
        },
    },
    fees::{DustOutputPolicy, SplitPolicy, StandardFeeRule, standard::MultiOutputChangeStrategy},
    wallet::{OutputRef, OvkPolicy},
};
use zcash_client_sqlite::util::SystemClock;
use zcash_keys::keys::UnifiedSpendingKey;
use zcash_primitives::transaction::builder::BundlePadding;
use zcash_protocol::{
    PoolType, ShieldedPool, TxId,
    consensus::{self, BlockHeight, BranchId, NetworkConstants as _, Parameters},
    memo::{Memo, MemoBytes},
    value::Zatoshis,
};
use zip321::{Payment, TransactionRequest};

use crate::{NoteInfo, NoteStatus, WalletStore, select::select_notes};

/// Owner token for the short-lived locks that steer input selection. Fixed, so locks left by a
/// crash can be found and released when the wallet is next opened.
pub const SELECTION_LOCK_OWNER: LockOwner = LockOwner::new(*b"offline-core:selection-lock:v1\0\0");

/// Notes need this many confirmations to be picked: the wallet's rule for received notes, and
/// stricter than its 3 for its own change, so the library never rejects our pick.
pub const MIN_CONFIRMATIONS: u32 = 10;

#[derive(Debug, Clone, Serialize)]
pub struct BuiltPayment {
    pub txid: String,
    #[serde(skip)]
    pub raw: Vec<u8>,
    pub raw_len: usize,
    pub amount: u64,
    pub fee: u64,
    pub change: u64,
    /// Notes spent, as `txid:index` (display order).
    pub spent_notes: Vec<String>,
}

fn txid_from_display(hex: &str) -> anyhow::Result<TxId> {
    let mut bytes = [0u8; 32];
    ensure!(hex.len() == 64, "bad txid {hex}");
    for (i, b) in bytes.iter_mut().enumerate() {
        *b = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16)?;
    }
    bytes.reverse();
    Ok(TxId::from_bytes(bytes))
}

fn output_ref(n: &NoteInfo) -> anyhow::Result<OutputRef> {
    let pool = match n.pool {
        "ironwood" => ShieldedPool::Ironwood,
        "orchard" => ShieldedPool::Orchard,
        other => bail!("unsupported pool {other}"),
    };
    Ok(OutputRef::new(txid_from_display(&n.txid)?, PoolType::Shielded(pool), n.output_index))
}

impl WalletStore {
    /// Releases selection locks left behind by an interrupted [`Self::pay_offline`].
    pub fn release_selection_locks(&mut self) -> anyhow::Result<usize> {
        let mut released = 0;
        for n in self.notes()? {
            if self.db.unlock_output(&output_ref(&n)?, SELECTION_LOCK_OWNER)? {
                released += 1;
            }
        }
        Ok(released)
    }

    /// Builds, proves, signs and stores a payment of `amount` zatoshis from `account_uuid` to
    /// `to`, entirely offline. `seed` is the wallet's BIP 39 seed.
    pub fn pay_offline(
        &mut self,
        seed: &[u8],
        account_uuid: &str,
        to: &str,
        amount: u64,
        memo: Option<&str>,
    ) -> anyhow::Result<BuiltPayment> {
        // Crash recovery: an interrupted earlier run may have left selection locks behind.
        self.release_selection_locks()?;
        let params = *self.db.params();
        let account_id = self
            .db
            .get_account_ids()?
            .into_iter()
            .find(|id| id.expose_uuid().to_string() == account_uuid)
            .with_context(|| format!("no account {account_uuid}"))?;
        let account = self.db.get_account(account_id)?.context("account vanished")?;
        let account_index = account
            .source()
            .key_derivation()
            .context("account has no ZIP 32 derivation, so it cannot sign")?
            .account_index();

        // 1. Our pick, from this account's well-confirmed available Ironwood notes.
        let tip = self.db.chain_height()?.context("wallet has never synced")?;
        let target = u32::from(tip) + 1;
        let notes: Vec<NoteInfo> = self.notes()?.into_iter().filter(|n| n.account_uuid == account_uuid).collect();
        let candidates: Vec<NoteInfo> = notes
            .iter()
            .filter(|n| {
                n.pool == "ironwood"
                    && n.status == NoteStatus::Available
                    && n.mined_height.is_some_and(|h| h + MIN_CONFIRMATIONS <= target)
            })
            .cloned()
            .collect();
        let selection = select_notes(&candidates, amount)?;
        let chosen: Vec<&NoteInfo> = selection.notes.iter().map(|&i| &candidates[i]).collect();
        let chosen_keys: BTreeSet<(String, u32)> =
            chosen.iter().map(|n| (n.txid.clone(), n.output_index)).collect();

        // 2. Lock every other available note so the library's selector can only see ours.
        let others: Vec<OutputRef> = notes
            .iter()
            .filter(|n| n.status == NoteStatus::Available && !chosen_keys.contains(&(n.txid.clone(), n.output_index)))
            .map(output_ref)
            .collect::<Result<_, _>>()?;
        let lock_until = BlockHeight::from(target + 1_000_000);
        self.db
            .lock_outputs(&others, SELECTION_LOCK_OWNER, lock_until)
            .map_err(|e| anyhow!("locking other notes: {e:?}"))?;

        let request = TransactionRequest::new(vec![
            Payment::new(
                ZcashAddress::from_str(to).map_err(|e| anyhow!("bad address: {e:?}"))?,
                Some(Zatoshis::from_u64(amount)?),
                memo.map(Memo::from_str).transpose()?.map(MemoBytes::from),
                None,
                None,
                vec![],
            )
            .map_err(|e| anyhow!("bad payment: {e:?}"))?,
        ])
        .map_err(|e| anyhow!("bad request: {e:?}"))?;
        let change_strategy = MultiOutputChangeStrategy::new(
            StandardFeeRule::Zip317,
            None,
            ShieldedPool::Ironwood,
            DustOutputPolicy::default(),
            SplitPolicy::single_output(),
        );
        let proposal = propose_transfer::<_, _, _, _, std::convert::Infallible>(
            &mut self.db,
            &params,
            account_id,
            &GreedyInputSelector::new(),
            &change_strategy,
            request,
            ConfirmationsPolicy::default(),
            &SpendPolicy::default(),
            None,
            None,
        );
        // Always release, whatever the proposal did.
        for r in &others {
            self.db.unlock_output(r, SELECTION_LOCK_OWNER)?;
        }
        let proposal = proposal.map_err(|e| anyhow!("proposal failed: {e:?}"))?;

        // The library must have used exactly our notes and agree on the fee.
        let step = proposal.steps().first();
        let used: BTreeSet<(String, u32)> = step
            .shielded_inputs()
            .map(|si| {
                si.notes()
                    .iter()
                    .map(|n| (n.txid().to_string(), u32::from(n.output_index())))
                    .collect()
            })
            .unwrap_or_default();
        ensure!(used == chosen_keys, "library chose different notes {used:?} than ours {chosen_keys:?}");
        let fee = step.balance().fee_required().into_u64();
        ensure!(fee == selection.fee, "library fee {fee} != selected fee {}", selection.fee);
        let change: u64 = step.balance().proposed_change().iter().map(|c| c.value().into_u64()).sum();

        // 3. Create with expiry 0, prove, sign.
        let mut rng = UnwrapErr(SysRng);
        let pczt = create_pczt_from_proposal::<_, _, std::convert::Infallible, _, std::convert::Infallible, _>(
            &mut self.db,
            &params,
            &SystemClock,
            &mut rng,
            account_id,
            OvkPolicy::Sender,
            &proposal,
            Some(consensus::H0),
            BundlePadding::DEFAULT,
        )
        .map_err(|e| anyhow!("creating PCZT: {e:?}"))?;
        let pczt = prove(pczt)?;
        let usk = UnifiedSpendingKey::from_seed(&params, seed, account_index)
            .map_err(|e| anyhow!("deriving spending key: {e:?}"))?;
        let pczt = sign(pczt, &params, seed, account_index, &usk)?;
        let bytes = pczt.serialize().map_err(|e| anyhow!("serializing PCZT: {e:?}"))?;

        // 4. Write-before-send.
        let (txid, raw) = self.store_signed(&bytes)?;
        Ok(BuiltPayment {
            txid: txid.to_string(),
            raw_len: raw.len(),
            raw,
            amount,
            fee,
            change,
            spent_notes: chosen.iter().map(|n| format!("{}:{}", n.txid, n.output_index)).collect(),
        })
    }
}

/// Creates the Orchard/Ironwood proofs (they share one circuit after NU6.3).
fn prove(pczt: pczt::Pczt) -> anyhow::Result<pczt::Pczt> {
    let branch_id = BranchId::try_from(*pczt.global().consensus_branch_id())
        .map_err(|_| anyhow!("PCZT has an unknown consensus branch"))?;
    let mut prover = Prover::new(pczt);
    let needs_orchard = prover.requires_orchard_proof();
    let needs_ironwood = prover.requires_ironwood_proof();
    if needs_orchard || needs_ironwood {
        let version = zcash_primitives::transaction::components::orchard::bundle_version_for_branch(
            branch_id,
            orchard::ValuePool::Orchard,
        )
        .ok_or_else(|| anyhow!("branch {branch_id:?} has no Orchard circuit"))?
        .circuit_version();
        let pk = orchard::circuit::ProvingKey::build(version);
        if needs_orchard {
            prover = prover
                .create_orchard_proof(UnwrapErr(SysRng), &pk)
                .map_err(|e| anyhow!("Orchard proof: {e:?}"))?;
        }
        if needs_ironwood {
            prover = prover
                .create_ironwood_proof(UnwrapErr(SysRng), &pk)
                .map_err(|e| anyhow!("Ironwood proof: {e:?}"))?;
        }
    }
    Ok(prover.finish())
}

/// Signs every Orchard/Ironwood spend derived from `seed` at `account_index`.
fn sign<P: Parameters>(
    pczt: pczt::Pczt,
    params: &P,
    seed: &[u8],
    account_index: zip32::AccountId,
    usk: &UnifiedSpendingKey,
) -> anyhow::Result<pczt::Pczt> {
    let seed_fp = zip32::fingerprint::SeedFingerprint::from_seed(seed).context("bad seed length")?;
    let coin_type = zip32::ChildIndex::hardened(params.network_type().coin_type());
    let ours = |spend: &orchard::pczt::Spend| {
        spend
            .zip32_derivation()
            .as_ref()
            .and_then(|d| d.extract_account_index(&seed_fp, coin_type))
            == Some(account_index)
    };
    let mut orchard_idx = Vec::new();
    let mut ironwood_idx = Vec::new();
    let pczt = Verifier::new(pczt)
        .with_orchard::<std::convert::Infallible, _>(|b| {
            orchard_idx.extend(b.actions().iter().enumerate().filter(|(_, a)| ours(a.spend())).map(|(i, _)| i));
            Ok(())
        })
        .map_err(|e| anyhow!("invalid PCZT: {e:?}"))?
        .with_ironwood::<std::convert::Infallible, _>(|b| {
            ironwood_idx.extend(b.actions().iter().enumerate().filter(|(_, a)| ours(a.spend())).map(|(i, _)| i));
            Ok(())
        })
        .map_err(|e| anyhow!("invalid PCZT: {e:?}"))?
        .finish();
    ensure!(!(orchard_idx.is_empty() && ironwood_idx.is_empty()), "no spends of ours to sign");

    let ask = orchard::keys::SpendAuthorizingKey::from(usk.orchard());
    let mut signer = Signer::new(pczt).map_err(|e| anyhow!("signer: {e:?}"))?;
    for i in orchard_idx {
        signer.sign_orchard(UnwrapErr(SysRng), i, &ask).map_err(|e| anyhow!("sign Orchard {i}: {e:?}"))?;
    }
    // Ironwood notes are spent with the account's Orchard spending key.
    for i in ironwood_idx {
        signer.sign_ironwood(UnwrapErr(SysRng), i, &ask).map_err(|e| anyhow!("sign Ironwood {i}: {e:?}"))?;
    }
    Ok(signer.finish())
}

/// What a receiver or a test can read from raw transaction bytes alone.
#[derive(Debug, Clone, Serialize)]
pub struct RawInfo {
    pub txid: String,
    pub version: String,
    pub expiry_height: u32,
    pub ironwood_actions: usize,
    pub orchard_actions: usize,
    pub sapling_spends: usize,
    /// For a fully shielded transaction the fee is the sum of the shielded value balances.
    pub fee: i64,
    /// ZIP 317 fee for this shape (Orchard-style actions; no transparent parts expected).
    pub zip317_fee: u64,
}

pub fn inspect_raw(raw: &[u8]) -> anyhow::Result<RawInfo> {
    let tx = zcash_primitives::transaction::Transaction::read(raw, BranchId::Nu6_3)?;
    let ironwood = tx.ironwood_bundle().map_or(0, |b| b.actions().len());
    let orchard = tx.orchard_bundle().map_or(0, |b| b.actions().len());
    let sapling = tx.sapling_bundle().map_or(0, |b| b.shielded_spends().len());
    let vb = |v: Option<i64>| v.unwrap_or(0);
    let fee = vb(tx.ironwood_bundle().map(|b| i64::from(*b.value_balance())))
        + vb(tx.orchard_bundle().map(|b| i64::from(*b.value_balance())))
        + vb(tx.sapling_bundle().map(|b| i64::from(*b.value_balance())));
    ensure!(tx.transparent_bundle().is_none(), "transparent parts are not expected");
    Ok(RawInfo {
        txid: tx.txid().to_string(),
        version: format!("{:?}", tx.version()),
        expiry_height: u32::from(tx.expiry_height()),
        ironwood_actions: ironwood,
        orchard_actions: orchard,
        sapling_spends: sapling,
        fee,
        zip317_fee: crate::split::MARGINAL_FEE
            * (ironwood + orchard + sapling).max(crate::split::GRACE_ACTIONS) as u64,
    })
}
