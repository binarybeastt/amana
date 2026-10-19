# Progress

Live status of every goal in [PLAN.md](PLAN.md). Updated as work happens; newest log entries first.

**Now:** Phase 1 done (Gate P1 passed). Next: Phase 2 · P2.G1 Android project setup and bridge. Real-phone tests pending: P0.G4.T1–T4, P1.G7.T3–T4
**Blockers:** none

Status: `todo` · `doing` · `blocked` · `emu-pass` · `done`. A goal is `done` only when all its tests pass. `emu-pass` = all Auto and Emulator tests pass, Real phone tests still pending (allowed until Phase 6).

**Real phones:** none yet. Developing on emulators (API 35/36 images, emulator 35.6 with netsim).

## Phase 0: Spikes

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P0.G1 Toolchain and testnet wallet | done | 4/4 | Rust 1.84.0 → 1.99.1 (devtool needs ≥ 1.89). devtool `0b3436b` built with our patch. Wallet `wallets/testnet-a` created (account `spike-a`, seed encrypted to `wallets/testnet-a.age`); testnet tip 4,516,819, so Ironwood is active. 1 TAZ from faucet landed in the **Ironwood** pool (tx `5a7d13f1…`, block 4,516,846); spendable after 10 confirmations. Spike accounts B (4 h), C (24 h), D (double spend) funded 0.2 TAZ each by tx `8839fb9a…` so offline-built txs never share a note. Scripts: `spikes/zcash/` |
| P0.G2 Expiry 0 support | done | 3/3 | Patched devtool ([patch](../spikes/patches/zcash-devtool-expiry-ironwood.patch), 3 files, +68 lines): `pczt create --expiry-height/--ironwood-change`, Ironwood signing in `pczt sign`, raw bytes + expiry from `pczt extract --output`. ⚠ librustzcash warns zero-expiry txs are "known to cause bugs elsewhere in the stack". |
| P0.G3 Delayed broadcast | done | 5/5 | Offline-built expiry-0 txs accepted 203 blocks (≈ 4.2 h) and 1,165 blocks (≈ 24.3 h) after their anchor. Duplicates harmless; a double spend is rejected by consensus |
| P0.G4 Bluetooth blob relay | emu-pass | 1/5 | Test app `spikes/ble-blob/` (sender / relay / sink roles, SHA-256 checked, driven by `run.sh` over adb; 7 protocol unit tests pass). Emulators: 100/100 at ~0.6 s each. T1–T4 wait for real phones |
| **Gate P0** | pass | | Spikes 1 and 2 passed (P0.G2, P0.G3). P0.G4 is emu-pass; its Real phone tests stay pending until phones arrive |

## Phase 1: Rust offline core

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P1.G1 Crate scaffold and wallet access | done | 2/2 | `offline-core/` (lib + `offline-cli`), pinned to librustzcash `eb38d7a`; no lightwalletd/Tor features, so the crate cannot reach the network. `offline-cli status [--json]` |
| P1.G2 Note store | done | 3/3 | Statuses: pending (in an unmined tx; can't be signed) → available → spent-offline → spent. Status derived from the wallet DB (see Decisions): `offline-cli notes`, guard `check_can_sign`, write-before-send `store-pczt` (~1.1 s). Live: C's pending 24 h payment stored in `wallets/testnet-a`; its note is spent-offline and devtool's own builder now sees 0 spendable for account C |
| P1.G3 Denomination split | done | 3/3 | `offline_core::split::plan_split` fills small sizes first (two each of 0.01 → 2 ZEC), leftover into the largest sizes that fit, fee solved as a fixed point. `offline-cli plan-split` prints a ZIP 321 request; real split tx `bb65b071…` mined at 4,517,718 |
| P1.G4 Coin selection | done | 4/4 | `offline_core::select`: exhaustive Gray-code search over ≤ 22 notes (greedy fallback above), each set judged with its own ZIP 317 fee. ⚠ Finding: see Decisions (fee-sized notes) |
| P1.G5 Offline payment builder | done | 5/5 | `WalletStore::pay_offline` / `offline-cli pay`: our coin selection → librustzcash proposal (other notes briefly locked so it can only use ours; inputs and fee cross-checked) → PCZT with expiry 0 → prove → sign → store → raw bytes. ~3.7 s per payment on desktop. Also `pending`, `inspect-raw`, `release-locks`, `receive` |
| P1.G6 Receive verifier | done | 6/6 | `WalletStore::verify_payment` / `offline-cli receive`: parse (fully shielded only, canonical bytes), Orchard-style batch validation of proofs + spend + binding signatures (sighash = txid, ZIP 244 S.2, no transparent inputs), anchor vs retained tree checkpoints, trial decryption (incoming only), nullifiers. Never panics (`catch_unwind`). Verifying key cached per circuit version |
| P1.G7 Android cross-compile | emu-pass | 3/5 | `cargo ndk -t arm64-v8a --platform 33 build --release` (SQLite bundled on Android only). On-device runner: `stress/p1/p1g7_device_pay.sh <serial> [runs]`. Emulator: median 13.6 s, peak 115 MB (indicative only). T3/T4 need the slowest real phone |
| **Gate P1** | pass | | `stress/p1/e2e.sh 3 5`: 3/3 in a row (sync → offline pay → wait 5 blocks → broadcast stored bytes → mined). P1.G7 T3/T4 stay pending for real phones |

## Phase 2: Android wallet shell

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P2.G1 Project setup and bridge | todo | 0/4 | |
| P2.G2 Wallet create, restore, sync | todo | 0/3 | |
| P2.G3 Prepare-for-offline screen | todo | 0/3 | |
| P2.G4 Pay and receive by animated QR | todo | 0/4 | |
| **Gate P2** | todo | | |

## Phase 3: Bluetooth mesh

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P3.G1 BLE transport | todo | 0/3 | |
| P3.G2 Discovery and handshake | todo | 0/3 | |
| P3.G3 Store-and-forward queue | todo | 0/5 | |
| P3.G4 Connectivity watcher | todo | 0/4 | |
| P3.G5 Background relaying | todo | 0/3 | |
| **Gate P3** | todo | | |

## Phase 4: Double-spend layers

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P4.G1 Write-before-send and recovery | todo | 0/2 | |
| P4.G2 Nullifier gossip and alert | todo | 0/3 | |
| P4.G3 Offline limits | todo | 0/3 | |
| P4.G4 Reconciliation on reconnect | todo | 0/2 | |
| **Gate P4** | todo | | |

## Phase 5: UX and polish

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P5.G1 Payment states end to end | todo | 0/2 | |
| P5.G2 Offline balance view | todo | 0/1 | |
| P5.G3 Seed-on-two-devices warning | todo | 0/1 | |
| P5.G4 First-time user run | todo | 0/1 | |
| P5.G5 Receiver gives change | todo | 0/1 | Cut first if needed |

## Phase 6: Mainnet and rehearsal

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P6.G1 Mainnet runs | todo | 0/1 | |
| P6.G2 Demo rehearsal and video | todo | 0/2 | |
| P6.G3 Field test | todo | 0/1 | |

## Phase 7: Submission

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P7.G1 README | todo | 0/2 | |
| P7.G2 Submit | todo | 0/2 | |

## Test results

One row per test run that counts toward a goal; newest first. Failed runs are recorded too.

| Date | Test | Result | Evidence |
|---|---|---|---|
| 2026-10-19 | Gate P1 | pass | `stress/p1/e2e.sh 3 5 1000000` (A → D, 0.01 ZEC): run 1 built at 4,519,569 → mined 4,519,577; run 2 4,519,577 → 4,519,586; run 3 4,519,588 → 4,519,604. Log: `stress/p1/logs/gate-p1.log` |
| 2026-10-19 | P1.G7.T5 | pass (emulator-built) | Bytes built on the emulator in airplane mode (`spikes/runs/p1g7/emulator-5554/run1.raw`, txid `f9968918…`) broadcast from the Mac with `pczt send-raw --file` at tip 4,519,554; mined at 4,519,565. Re-run with real-phone bytes once phones arrive |
| 2026-10-19 | P1.G7.T2 | pass (emulator) | `p1g7_device_pay.sh emulator-5554 3`: API 36 arm64 emulator, airplane mode on and ping to 8.8.8.8 failing; 3/3 payments built on device (median 13.6 s, worst 17.5 s, peak 115 MB). Desktop `receive` verified the device-built bytes. Log: `stress/p1/logs/p1g7-emulator-5554.log`. (First run's timings were garbage: Android mksh arithmetic is 32-bit, fixed by subtracting on the host) |
| 2026-10-19 | P1.G7.T1 | pass | `cargo ndk -t arm64-v8a --platform 33 build --release`: `offline-cli` (15 MB) and `liboffline_core.so` (1.3 MB), ELF aarch64, 75 s. Log: `stress/p1/logs/p1g7t1.log` |
| 2026-10-19 | P1.G6.T1–T6 | pass | `offline-core/tests/receive_live.rs` (fixtures from `stress/p1/p1g6_fixtures.sh`, built on a wallet copy): T1 A→D 0.02 ZEC with memo "bread for Ada" verified with the right amount, account and memo; T2 account B gets "not for this wallet"; T3 1,000 single-bit tamperings all rejected; T4 100,000 random/truncated/corrupted/extended inputs, none accepted, no panic, slowest 41 ms; T5 a wallet stuck ~1,800 blocks behind flags "anchor unknown", never verified; T6 the sender's recorded nullifier is among the payment's. Log: `stress/p1/logs/p1g6.log` |
| 2026-10-19 | P1.G6.T4 + T5 | fail | First run: T5 errored with `TreeIncomplete` — a receiver whose tree can't produce a root at some checkpoint rejected a good payment instead of saying "anchor unknown" (fixed: such checkpoints just don't match). T4 "accepted 5" was a test bug: random overwrites sometimes rewrote the same byte, recreating the valid payment (fixed: only inputs that differ count) |
| 2026-10-19 | P1.G5.T4 | pass | `stress/p1/p1g5t4_pay_crash.sh 100` with the harness fixed: 52 stored (note spent-offline, raw recovered via `pending`, expiry 0), 48 not stored (note available, nothing pending), **0 failures**. No kill landed in the ms-long lock window, so lock recovery was tested directly: a planted stale selection lock on B's only note was released by the next `pay`, which then spent it. Logs: `stress/p1/logs/p1g5t4.log`, `p1g5t4-lock-recovery.log` |
| 2026-10-19 | P1.G5.T4 | fail (harness bug) | Earlier "inconsistent" runs were a test bug: `pay` was started through the `nonet` shell function, so `$!` was a subshell and `kill -9` left the real process running; it finished storing after the check. The saved wallet copies were fully consistent. Fixed by starting `sandbox-exec` directly and asserting no `pay` survives a kill |
| 2026-10-19 | P1.G5.T4 | inconclusive | Pay crash test, 2 attempts. Run A: 98/100 clean, 2 inconsistent (a new pending payment but the spent note still available), not reproduced in 40 targeted retries. Run B confounded: the T5 broadcast job synced the live wallet mid-test, so every copy after run 35 had a different baseline (runs 1–35 clean). Re-running with the wallet idle and failure states captured |
| 2026-10-19 | P1.G5.T5 | pass | All 10 offline payments broadcast from the wallet's stored bytes (`pczt send-raw --txid`) at tip 4,519,468, 48 blocks after the build, and all 10 mined within 3 blocks. ⚠ Testnet blocks were fast: 48 blocks (nominally 1 h) took ≈ 18 min wall-clock. Anchor age in blocks is what limits validity, and P0.G3.T3 already showed 1,165 blocks. `spikes/runs/p1g5/broadcast.log` |
| 2026-10-19 | P1.G5.T1–T3 | pass | `stress/p1/p1g5_ten_payments.sh`: 10 payments from account A built back to back under `sandbox-exec (deny network*)` (T1); each `inspect-raw` shows expiry 0 and fee = ZIP 317 for its shape = builder's fee (T2); 16 notes spent across the 10, none reused (T3); 5 of 10 exact. Payments 1–7 used round amounts (0.05, 0.01, 0.1, 0.02, 0.03, 0.07, 0.05 ZEC); 8–10 failed for lack of available notes (change pending), so they were rebuilt at 10,000 / 20,000 / 25,000 zats from fee-sized notes. Log: `stress/p1/logs/p1g5.log` |
| 2026-10-19 | P1.G3.T3 | pass (re-run) | Split with fee notes: tx `271f73ee…` mined at 4,519,396, 17 outputs: all 7 fee notes + planned denominations present, available, positioned. Fee 0.00085 = plan. Log: `stress/p1/logs/p1g3t3-feenotes.log` |
| 2026-10-19 | P1.G3.T1 + P1.G4 | pass (re-run) | After adding fee notes: split proptest (10,000) also checks fee notes are known sizes, one each; new test pays 0.05 / 0.37 / 0.88 / 1.0 ZEC exactly from a prepared wallet. All 20 tests + clippy clean: `stress/p1/logs/p1-all-tests.log` |
| 2026-10-19 | P0.G3.T3 | pass | C built offline at anchor tip 4,516,878; broadcast at 4,518,043 (1,165 blocks ≈ 24.3 h later); mined at 4,518,044. In the wallet, C's note moved spent-offline → spent and its pending change → available. `spikes/runs/C-24h/meta.txt` |
| 2026-10-19 | P1.G2.T1 + T3 | pass (re-run) | After adding `pending`: proptest 10,000 sequences from pending or available start; T3 all 4 accounts match with C's pending change counted. All 18 tests + clippy clean: `stress/p1/logs/p1-all-tests.log` |
| 2026-10-19 | P1.G3.T3 | pass | Plan for account A (6 notes, 0.5395 TAZ) → ZIP 321 → `wallet pay`; tx `bb65b071…` mined at 4,517,718. All 8 planned denominations (2 × 0.1, 0.05, 0.02, 0.01) present, available, mined, with tree positions. Library picked 5 of 6 inputs, so change was 0.0559 instead of the planned 0.179 remainder. Log: `stress/p1/logs/p1g3t3.log` |
| 2026-10-19 | P1.G4.T1–T4 | pass | `tests/select.rs` (release): 10,000 random note sets (≤ 12 notes, 10% spent-offline/spent) vs an independent recursive brute force — exact chosen whenever one exists with the fewest notes, else the same minimum change; never a non-available note (T2); total = amount + ZIP 317 fee for the chosen shape + change, every case (T3); 1,000 selections over 20 notes p99 2.6 ms (T4, < 50 ms). Clippy clean. Log: `stress/p1/logs/p1g4.log` |
| 2026-10-19 | P1.G3.T3 | fail | First split attempt rejected: `transaction must not be mined at a block Height(4517699) greater than its expiry Height(4517694)`. The wallet had not synced for ~45 blocks, so devtool's 40-block expiry was already past. Its notes correctly showed available again once the expiry passed. Second attempt (after sync) hit a dropped TLS connection during send; rebroadcast from the wallet's stored bytes with the new `pczt send-raw --txid` |
| 2026-10-19 | P1.G2.T2 | pass | `stress/p1/p1g2t2_store_crash.sh 1000`: kill -9 during `store-pczt` at 0.5–1.4 s on a fresh wallet copy each run → 323 committed, 677 not committed, **0 failures** (integrity_check ok, notes = before- or after-state, tx and spend rows always together). Log: `stress/p1/logs/p1g2t2.log` |
| 2026-10-19 | P1.G3.T2 | pass | `tests/split.rs`: every total from 0 to the fee, for 1–20 input notes, is refused with an error and builds nothing |
| 2026-10-19 | P1.G3.T1 | pass | `tests/split.rs` proptest, 10,000 random balances (0.001–100 ZEC, 1–20 notes): outputs + fee = input to the zatoshi, 1–20 outputs, fee = ZIP 317 for the shape, ≤ 2 of each denomination. Re-run after switching to small-first fill: still passes |
| 2026-10-19 | P1.G2.T3 | pass | `stress/p1/p1g2t3_notes_match_balance.sh` (offline): all 4 accounts' available notes sum exactly to their balance; every available note is mined with a tree position. Log: `stress/p1/logs/p1g2t3.log` |
| 2026-10-19 | P1.G2.T1 | pass | `offline-core/tests/note_status.rs`: proptest, 10,000 random sequences (0–50 events) — never back to available, signing accepted only once and only from available; plus an exhaustive pair test. Clippy clean. Log: `stress/p1/logs/p1g2t1.log` |
| 2026-10-19 | P0.G1.T3 | pass | `spikes/zcash/sync_kill_test.sh`: throwaway wallet (birthday 4,450,000) funded 0.01 TAZ; sync killed with `kill -9` 3 times, then resumed to tip 4,517,630 = chain tip; total 1,000,000 zats as expected. `spikes/runs/sync-kill/result.txt`. First two attempts failed on script bugs (`pipefail` + early-exiting `awk`/`grep -q`), fixed |
| 2026-10-19 | P1.G1.T2 | pass | `stress/p1/p1g1t2_status_matches_devtool.sh`: all 4 accounts match `zcash-devtool wallet balance --json` field for field (total + spendable per pool) and tip; offline-cli ran under the no-network sandbox. Log: `stress/p1/logs/p1g1t2.log` |
| 2026-10-19 | P1.G1.T1 | pass | `cargo build`, `cargo test` (3 tests), `cargo clippy --all-targets -D warnings`: all exit 0. Log: `stress/p1/logs/p1g1t1.log` |
| 2026-10-19 | P0.G3.T4 | pass | Resend after mined: `(-25) … transaction is already in state`; balances unchanged. With the mempool case above, a duplicate broadcast is harmless in both states. `spikes/runs/B-4h/send-after-mined.txt` |
| 2026-10-19 | P0.G3.T2 | pass | B built offline at anchor tip 4,516,875; broadcast at 4,517,078 (203 blocks ≈ 4.2 h later); mined at 4,517,084. `spikes/runs/B-4h/meta.txt` |
| 2026-10-19 | P0.G1.T2 | pass | Wallet synced to tip (4,516,846 = chain tip at the time); 1 TAZ shielded (Ironwood) balance shown by `wallet balance` |
| 2026-10-19 | P0.G3.T5 | pass | D1 mined at 4,516,884. D2 resent after that: `(-25) … could not contextually validate transaction on best chain: ironwood double-spend: duplicate nullifier`. `spikes/runs/D2-dblspend/send-after-mined.txt` |
| 2026-10-19 | P0.G2.T3 | pass | Patch kept in `spikes/patches/`; the expiry part is 21 lines in `create.rs` (under ~50). The Ironwood signing (`sign.rs`, 36 lines) and raw-bytes extract (`extract.rs`, 15 lines) are extra fixes found along the way |
| 2026-10-19 | P0.G2.T2 | pass | A's expiry-0 tx `b49de3fe…` broadcast at 4,516,878, mined at 4,516,884 (6 blocks, limit 10) |
| 2026-10-19 | P0.G3.T4 | partial | Duplicate send while in mempool: `(-1) transaction already exists in mempool`, no state change. Already-mined case comes with B. `spikes/runs/A-online/send2.txt` |
| 2026-10-19 | P0.G3.T5 | partial | D1 `30212617…` accepted; D2 `2251c7e3…` (same note) rejected: `(-25) … another transaction in the mempool has already spent some of its inputs`. Waiting for D1 to be mined. `spikes/runs/D*-dblspend/` |
| 2026-10-19 | P0.G3.T1 | pass | `spikes/zcash/build_offline.sh` ran create → prove → sign → combine → extract under `sandbox-exec (deny network*)` for B, C, D1, D2, A: all succeeded. Each tx: v6, 2 Ironwood actions, 9,166 bytes, fee 10,000 zats (ZIP 317 for 2 actions), ~11 s on M-series Mac |
| 2026-10-19 | P0.G2.T1 | pass | Extracted tx reports `expiry_height=0` (read from the transaction itself, `spikes/runs/*/meta.txt`) |
| 2026-10-19 | P0.G3.T1 | fail | First offline build: extract failed `Ironwood(Extract(MissingSpendAuthSig))`. devtool `pczt sign` had no Ironwood branch. Fixed by signing Ironwood spends with the account's Orchard spend-authorizing key (`Signer::sign_ironwood`) |
| 2026-10-19 | P0.G4.T5 | pass | `run.sh emulator-5554 emulator-5556 100 20480` (two API 36 emulators, netsim): 100/100 delivered, 0 checksum failures, p50 594 ms, p95 716 ms, max 1,074 ms. Logs: `spikes/logs/p0g4-20261019-005523/` |
| 2026-10-19 | P0.G4.T5 | fail | First 5-blob smoke run: 3/5 acked (sink got all 5 intact). Bug in test client: acks arriving before the last write callback were dropped. Fixed with a separate ack queue |
| 2026-10-19 | P0.G1.T1 | pass | `cargo build --release` from a fresh clone of devtool `0b3436b` (+ our patch): 29m 39s, exit 0. Log: `spikes/logs/p0g1t1-devtool-build.log` |
| 2026-10-19 | P0.G1.T4 | pass | Ironwood = NU6.3 pool (devtool `CONTEXT.md`). Activation: testnet 4,134,000, mainnet 3,428,143 (`zcash_protocol/src/consensus.rs` at librustzcash `eb38d7a`) |

## Decisions

Choices that change the plan, a pass criterion, or the spec. Newest first.

| Date | Decision | Why |
|---|---|---|
| 2026-10-19 | Noted, not changed: (1) pending change locks value, so one prepared wallet of 0.64 TAZ ran out of available notes after 7 ordinary payments (≈ 0.3 TAZ stuck as pending change); (2) exact-first selection can fragment: a 0.1 payment used five small notes exactly instead of one 0.1 note + change, starving later small payments | Both follow the spec's rules (change unspendable until mined; exact payments first). Inputs for P4.G3 caps, P5.G5 receiver-gives-change, and a possible "prefer fewer notes unless change" tweak — for the user to decide |
| 2026-10-19 | Added a `pending` note status (change from a stored, unmined payment) and widened P1.G2.T3 to "available + pending = balance" | Found when C's stored payment made the original T3 check differ: the wallet counts pending change in its balance. The spec already calls this the pending change note, unspendable until mined. Notes from expired, unmined txs are excluded. Not a loosening: T3 originally assumed nothing was pending |
| 2026-10-19 | **Approved by user:** add a few fee-sized notes (5,000 × k zats, k = 2…8, ≈ 0.0018 ZEC total) to "Prepare for offline" | The fee comes out of the sender's notes, so a round amount (0.05, 0.37, 1 ZEC) is almost never "a sum of denominations minus fee": nearly every offline payment would make a change note, which the spec keeps locked until mined. With one fee-sized note per payment, round amounts become exact. Implemented: `split::FEE_NOTES` (one each of 10,000…40,000 zats) fill first, then two each of 0.01→0.5 ZEC small-first, then the remainder; still ≤ 20 outputs. Selection's exhaustive limit raised 22 → 24 notes |
| 2026-10-19 | The note store is the wallet DB itself, not a second list. `spent-offline` = spent by a stored, unmined tx; signing stores the tx via librustzcash `extract_and_store_transaction_from_pczt` before any bytes leave (write-before-send). `offline-core` adds a refuse-to-sign-twice guard on top. Raw bytes for the receiver are the stored ones (extraction randomizes the binding signature, so re-extracting gives different bytes with the same txid) | The wallet's own note selection then skips those notes for us and for the SDK; a separate list could drift. Expiry 0 means a stored payment never expires, so a spent-offline note never silently becomes available. Witnesses come from the wallet's commitment tree at build time instead of being copied out |
| 2026-10-19 | Phase 1 starts before Gate P0 closes. Gate P0 stays open until the 4 h (B) and 24 h (C) delayed broadcasts report; if either fails, the anchor-age window becomes an offline-time cap and Phase 1 adapts | User's call: spike 1's remaining tests only bound how long a payment can stay offline, which no Phase 1 goal before P1.G5 depends on |
| 2026-10-19 | Device tests split into Emulator (22) and Real phone (18). Phase gates may pass in their emulator form; every Real phone test must pass before Phase 6. Added P0.G4.T5 (emulator BLE pre-check) | No real phones yet; user's call to develop on emulators until they arrive. No pass criterion was loosened |
| 2026-10-19 | "Ironwood" = the NU6.3 shielded pool: a fourth pool beside transparent, Sapling and Orchard, with its own note tree and nullifier set; never co-spent with Sapling/Orchard in one step. Activates at testnet 4,134,000 / mainnet 3,428,143 | zcash-devtool `CONTEXT.md`; librustzcash `consensus.rs` |
| 2026-10-19 | Build schedule dates removed from the spec; phases kept. Work goes phase by phase, gated by stress tests | User's call: ignore the timeline |
| 2026-10-19 | Offline wallet chosen over the Inveil idea | User's call |

## Log

Newest first.

- **2026-10-19** Phase 1 done: Gate P1 passed 3/3. P1.G5 builder, P1.G6 receive verifier (found a real bug: incomplete tree checkpoints made good payments error instead of "anchor unknown"), P1.G7 Android build runs on an emulator in airplane mode. Two test-harness bugs found and fixed along the way (shell-function `kill -9`, 32-bit device arithmetic). Spec doc: Phase 0 and Phase 1 items ticked except the two that need real phones.
- **2026-10-19** Gate P0 passed: an offline payment stays valid for at least 24 h. Fee-sized notes added to the split (user's call); round amounts now pay exactly. Re-running the testnet split with fee notes.
- **2026-10-19** P1.G2 done. Two lessons from the first real split, both for the app: (1) always sync right before an online build, or the normal 40-block expiry can already be past; (2) a send can fail mid-flight with a dropped connection, so the sender must keep the stored bytes and rebroadcast (spec flow 4). Added `pczt send-raw` to the devtool fork for that; relays need the same thing in P3.G4.
- **2026-10-19** P1.G2 note store built on the wallet DB (Decisions). Live check on a wallet copy: storing C's payment moved its note to spent-offline, and a second store was refused. P1.G3 planner done; first version (largest-first) split 0.54 TAZ into 0.5 + 0.02 + 0.01, which can't pay 0.05 exactly, so it now fills small sizes first.
- **2026-10-19** Spike 1 core result: a tx built offline with expiry 0 was accepted 203 blocks (≈ 4.2 h) after its anchor. P1.G1 done: `offline-core` reads the wallet and matches devtool to the zatoshi. Started P0.G1.T3 (sync kill test) in the background. Note for P3.G4.T3: "already in state" and "already exists in mempool" both mean success for a relay.
- **2026-10-19** Spikes 1–2 under way. Found and patched a gap: devtool could create Ironwood PCZTs but not sign them. Lesson for P1.G5: `offline-core` signs Ironwood spends with the Orchard spend-authorizing key via `Signer::sign_ironwood`. Offline-built txs are 9,166 bytes (2 actions): inside the BLE spike's 20 KB test size.
- **2026-10-19** Faucet 1 TAZ arrived (Ironwood). Created spike accounts B/C/D and funded them in one tx (fee 0.0003 TAZ = 6 actions × 5,000 zats, matches ZIP 317). Wrote `spikes/zcash/` scripts: offline build runs under a macOS no-network sandbox (`sandbox-exec ... (deny network*)`), so P0.G3.T1 is checked by the OS, not by trust.
- **2026-10-19** devtool built with the expiry/Ironwood patch; testnet wallet created, waiting on faucet funds. BLE blob app built; emulator test found and fixed an ack-ordering bug in the client, then passed 100/100 (P0.G4.T5). Lesson for Phase 3: notifications can overtake write callbacks, so the transport must not assume callback order.
- **2026-10-19** Started P0.G1. Found devtool's `migration` command (Orchard → Ironwood) already plans note-split denominations and pre-signs transactions with deferred anchors: a candidate to reuse for P1.G3 and the v6 stretch goal.
- **2026-10-19** Removed schedule dates from the spec doc. Wrote PLAN.md (goals + stress tests per phase) and this tracker. Repo initialised. Environment: macOS arm64, Rust 1.84.0, Java 25, protoc 35.1, Android SDK at `~/Library/Android/sdk` (NDK 27.1 and 28.2, platform-tools present but `adb` not on PATH), `cargo-ndk` missing, 51 GB disk free.
