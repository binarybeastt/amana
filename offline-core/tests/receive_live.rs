//! P1.G6: receive verifier against real testnet payments.
//!
//! Needs fixtures from `stress/p1/p1g6_fixtures.sh`. Run with:
//!   set -a; source ../spikes/runs/p1g6/fixtures.env; set +a
//!   cargo test --release --test receive_live -- --ignored --nocapture
//! Wallets are copied to a temp dir first, so nothing live is touched.

use std::{collections::BTreeSet, path::Path, time::Instant};

use offline_core::WalletStore;
use rand::{RngExt, SeedableRng, rngs::StdRng};
use zcash_protocol::consensus::Network;

fn env(k: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| panic!("missing {k}: source spikes/runs/p1g6/fixtures.env"))
}

/// Opens a temp copy of a wallet's database.
fn store_copy(dir: &str) -> (tempfile::TempDir, WalletStore) {
    let tmp = tempfile::tempdir().unwrap();
    std::fs::copy(Path::new(dir).join("data.sqlite"), tmp.path().join("data.sqlite")).unwrap();
    let store = WalletStore::open(tmp.path(), Network::TestNetwork).unwrap();
    (tmp, store)
}

fn raw() -> Vec<u8> {
    std::fs::read(env("RECEIVE_RAW")).unwrap()
}

/// T1: a valid payment shows the right amount, recipient account and memo.
#[test]
#[ignore]
fn t1_valid_payment_is_verified() {
    let (_t, mut store) = store_copy(&env("RECEIVE_WALLET"));
    let p = store.verify_payment(&raw(), Some(&env("RECEIVE_ACCOUNT"))).unwrap();
    assert!(p.is_verified(), "{p:?}");
    assert_eq!(p.amount, env("RECEIVE_AMOUNT").parse::<u64>().unwrap());
    assert_eq!(p.incoming.len(), 1);
    assert_eq!(p.incoming[0].account_uuid, env("RECEIVE_ACCOUNT"));
    assert_eq!(p.incoming[0].memo.as_deref(), Some(env("RECEIVE_MEMO").as_str()));
    assert_eq!(p.expiry_height, 0);
}

/// T2: the same payment checked by another account is not for it.
#[test]
#[ignore]
fn t2_other_account_is_not_paid() {
    let (_t, mut store) = store_copy(&env("RECEIVE_WALLET"));
    let p = store.verify_payment(&raw(), Some(&env("OTHER_ACCOUNT"))).unwrap();
    assert!(!p.is_for_us());
    assert!(!p.is_verified());
}

/// T3: 1,000 single-bit tamperings anywhere in the bytes (proofs, signatures, values,
/// ciphertexts, header) are all rejected.
#[test]
#[ignore]
fn t3_tampered_bytes_are_rejected() {
    let (_t, mut store) = store_copy(&env("RECEIVE_WALLET"));
    let good = raw();
    let mut rng = StdRng::seed_from_u64(244);
    let mut accepted = Vec::new();
    for _ in 0..1_000 {
        let mut bad = good.clone();
        let i = rng.random_range(0..bad.len());
        bad[i] ^= 1 << rng.random_range(0..8);
        if let Ok(p) = store.verify_payment(&bad, None) {
            accepted.push((i, p.proofs_and_signatures_ok));
        }
    }
    assert!(accepted.is_empty(), "tampered variants accepted at byte offsets {accepted:?}");
}

/// T4: 100,000 random, truncated and corrupted inputs: never a panic, never slower than 1 s.
#[test]
#[ignore]
fn t4_fuzz_never_panics_or_hangs() {
    let (_t, mut store) = store_copy(&env("RECEIVE_WALLET"));
    let good = raw();
    let mut rng = StdRng::seed_from_u64(317);
    // The verifying keys are built once per process (about a second); the app builds them at
    // start-up. Time inputs, not that one-time setup (user's call, see PROGRESS.md Decisions).
    offline_core::receive::prewarm_verifier();
    let mut slowest = std::time::Duration::ZERO;
    let mut slowest_k = 0;
    let mut accepted = 0;
    for k in 0..100_000 {
        let input: Vec<u8> = match k % 4 {
            0 => (0..rng.random_range(0..2_000)).map(|_| rng.random()).collect(),
            1 => good[..rng.random_range(0..good.len())].to_vec(),
            2 => {
                let mut b = good.clone();
                for _ in 0..rng.random_range(1..20) {
                    let i = rng.random_range(0..b.len());
                    b[i] = rng.random();
                }
                b
            }
            _ => {
                let mut b = good.clone();
                b.extend((0..rng.random_range(1..64)).map(|_| rng.random::<u8>()));
                b
            }
        };
        // Overwriting a byte with its own value can reproduce the original payment, which is
        // rightly accepted; only count inputs that actually differ from it.
        let changed = input != good;
        let t = Instant::now();
        if store.verify_payment(&input, None).is_ok() && changed {
            accepted += 1;
        }
        if t.elapsed() > slowest {
            (slowest, slowest_k) = (t.elapsed(), k);
        }
    }
    eprintln!("fuzz: 100,000 inputs, slowest {slowest:?} (input {slowest_k}), accepted {accepted}");
    assert_eq!(accepted, 0);
    assert!(slowest.as_secs_f64() < 1.0, "slowest {slowest:?}");
}

/// T5: a receiver whose wallet has not synced the sender's anchor flags it, never "verified".
#[test]
#[ignore]
fn t5_unknown_anchor_is_flagged() {
    let (_t, mut store) = store_copy(&env("STALE_WALLET"));
    let raw = std::fs::read(env("STALE_RAW")).unwrap();
    let p = store.verify_payment(&raw, Some(&env("STALE_ACCOUNT"))).unwrap();
    assert!(p.proofs_and_signatures_ok);
    assert!(p.is_for_us(), "{p:?}");
    assert!(!p.anchor_known);
    assert!(!p.is_verified());
}

/// T6: the payment's nullifiers include every nullifier the sender recorded as spent by it
/// (the rest are dummy spends padding the bundle).
#[test]
#[ignore]
fn t6_nullifiers_match_sender() {
    let (_t, mut store) = store_copy(&env("RECEIVE_WALLET"));
    let p = store.verify_payment(&raw(), None).unwrap();
    let ours: BTreeSet<String> = p.nullifiers.into_iter().collect();
    let sender: BTreeSet<String> =
        env("SENDER_NULLIFIERS").split(',').filter(|s| !s.is_empty()).map(str::to_owned).collect();
    assert!(!sender.is_empty());
    assert!(sender.is_subset(&ours), "sender {sender:?} not within {ours:?}");
}
