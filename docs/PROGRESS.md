# Progress

Live status of every goal in [PLAN.md](PLAN.md). Updated as work happens; newest log entries first.

**Now:** Phase 0 · waiting on faucet funds for P0.G1–G3; P0.G4 emulator-passed
**Blockers:** testnet wallet needs faucet funds (user). Address: `wallets/testnet-a` default address, listed by `zcash-devtool wallet -w wallets/testnet-a list-addresses`

Status: `todo` · `doing` · `blocked` · `emu-pass` · `done`. A goal is `done` only when all its tests pass. `emu-pass` = all Auto and Emulator tests pass, Real phone tests still pending (allowed until Phase 6).

**Real phones:** none yet. Developing on emulators (API 35/36 images, emulator 35.6 with netsim).

## Phase 0: Spikes

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P0.G1 Toolchain and testnet wallet | blocked | 2/4 | Rust 1.84.0 → 1.99.1 (devtool needs ≥ 1.89). devtool `0b3436b` built with our patch. Wallet `wallets/testnet-a` created (account `spike-a`, seed encrypted to `wallets/testnet-a.age`); testnet tip 4,516,819, so Ironwood is active. **Waiting on faucet funds** for T2/T3 |
| P0.G2 Expiry 0 support | doing | 0/3 | Patched `pczt create` with `--expiry-height <n>` and `--ironwood-change` ([patch](../spikes/patches/zcash-devtool-expiry-ironwood.patch), 53 lines). librustzcash accepts height 0 explicitly (`H0` bypasses the min-target check). ⚠ Its docs warn zero-expiry txs are "not yet well tested end-to-end and are known to cause bugs elsewhere in the stack": spikes 1–2 must shake these out. Needs the build + funded wallet to run T1/T2 |
| P0.G3 Delayed broadcast | todo | 0/5 | |
| P0.G4 Bluetooth blob relay | emu-pass | 1/5 | Test app `spikes/ble-blob/` (sender / relay / sink roles, SHA-256 checked, driven by `run.sh` over adb; 7 protocol unit tests pass). Emulators: 100/100 at ~0.6 s each. T1–T4 wait for real phones |
| **Gate P0** | todo | | |

## Phase 1: Rust offline core

| Goal | Status | Tests passed | Evidence / notes |
|---|---|---|---|
| P1.G1 Crate scaffold and wallet access | todo | 0/2 | |
| P1.G2 Note store | todo | 0/3 | |
| P1.G3 Denomination split | todo | 0/3 | |
| P1.G4 Coin selection | todo | 0/4 | |
| P1.G5 Offline payment builder | todo | 0/5 | |
| P1.G6 Receive verifier | todo | 0/6 | |
| P1.G7 Android cross-compile | todo | 0/5 | |
| **Gate P1** | todo | | |

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
| 2026-10-19 | P0.G4.T5 | pass | `run.sh emulator-5554 emulator-5556 100 20480` (two API 36 emulators, netsim): 100/100 delivered, 0 checksum failures, p50 594 ms, p95 716 ms, max 1,074 ms. Logs: `spikes/logs/p0g4-20261019-005523/` |
| 2026-10-19 | P0.G4.T5 | fail | First 5-blob smoke run: 3/5 acked (sink got all 5 intact). Bug in test client: acks arriving before the last write callback were dropped. Fixed with a separate ack queue |
| 2026-10-19 | P0.G1.T1 | pass | `cargo build --release` from a fresh clone of devtool `0b3436b` (+ our patch): 29m 39s, exit 0. Log: `spikes/logs/p0g1t1-devtool-build.log` |
| 2026-10-19 | P0.G1.T4 | pass | Ironwood = NU6.3 pool (devtool `CONTEXT.md`). Activation: testnet 4,134,000, mainnet 3,428,143 (`zcash_protocol/src/consensus.rs` at librustzcash `eb38d7a`) |

## Decisions

Choices that change the plan, a pass criterion, or the spec. Newest first.

| Date | Decision | Why |
|---|---|---|
| 2026-10-19 | Device tests split into Emulator (22) and Real phone (18). Phase gates may pass in their emulator form; every Real phone test must pass before Phase 6. Added P0.G4.T5 (emulator BLE pre-check) | No real phones yet; user's call to develop on emulators until they arrive. No pass criterion was loosened |
| 2026-10-19 | "Ironwood" = the NU6.3 shielded pool: a fourth pool beside transparent, Sapling and Orchard, with its own note tree and nullifier set; never co-spent with Sapling/Orchard in one step. Activates at testnet 4,134,000 / mainnet 3,428,143 | zcash-devtool `CONTEXT.md`; librustzcash `consensus.rs` |
| 2026-10-19 | Build schedule dates removed from the spec; phases kept. Work goes phase by phase, gated by stress tests | User's call: ignore the timeline |
| 2026-10-19 | Offline wallet chosen over the Inveil idea | User's call |

## Log

Newest first.

- **2026-10-19** devtool built with the expiry/Ironwood patch; testnet wallet created, waiting on faucet funds. BLE blob app built; emulator test found and fixed an ack-ordering bug in the client, then passed 100/100 (P0.G4.T5). Lesson for Phase 3: notifications can overtake write callbacks, so the transport must not assume callback order.
- **2026-10-19** Started P0.G1. Found devtool's `migration` command (Orchard → Ironwood) already plans note-split denominations and pre-signs transactions with deferred anchors: a candidate to reuse for P1.G3 and the v6 stretch goal.
- **2026-10-19** Removed schedule dates from the spec doc. Wrote PLAN.md (goals + stress tests per phase) and this tracker. Repo initialised. Environment: macOS arm64, Rust 1.84.0, Java 25, protoc 35.1, Android SDK at `~/Library/Android/sdk` (NDK 27.1 and 28.2, platform-tools present but `adb` not on PATH), `cargo-ndk` missing, 51 GB disk free.
