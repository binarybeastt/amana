# Build plan: goals and stress tests

The spec's phases, each split into goals small enough to finish and prove. A goal is **done** only when every stress test under it passes, with evidence recorded in [PROGRESS.md](PROGRESS.md). A phase starts only when the previous phase's gate passes.

Spec (source of truth for *what* we build): [Offline Shielded Zcash Payments: Technical Spec](https://claude.ai/code/artifact/6b30698e-2158-4baf-ba77-2987d28fb36e)

**How to read this file**
- IDs: `P1.G4` = Phase 1, Goal 4. `P1.G4.T2` = its second stress test.
- **Auto** tests run from a script or `cargo test` and must be re-runnable.
- **Emulator** tests run on Android emulators (netsim gives them a shared virtual Bluetooth network). **Real phone** tests check things emulators hide: radio loss, range, OEM battery killers, phone speed. Both kinds are run by hand or by `adb` scripts, with results logged.
- Until real phones arrive, a goal whose Auto and Emulator tests pass is `emu-pass` and its phase gate can be passed in its emulator form, so work keeps moving. Every Real phone test must pass before Phase 6 starts.
- Test scripts live in `stress/<phase>/`. Spike scripts and raw logs live in `spikes/`.
- Pass criteria are fixed. Changing one needs the user's OK and an entry under *Decisions* in PROGRESS.md.

**Additions beyond the spec** (marked ➕ below): the receiver checks the anchor against roots it has synced; relays verify proofs before storing, so the mesh can't be spammed; `offline-core` pins the same librustzcash versions the Android SDK bundles, so both can share one wallet database.

---

## Phase 0: Spikes (go / no-go)

### P0.G1 Toolchain and testnet wallet
Rust is current, zcash-devtool builds, and a funded testnet wallet is synced.

| ID | Type | Test | Pass |
|---|---|---|---|
| P0.G1.T1 | Auto | Build zcash-devtool from a clean checkout (`cargo build --release`) | Builds with no errors |
| P0.G1.T2 | Auto | Create testnet wallet, fund from faucet, sync to tip | Shielded balance > 0 shown, sync height = chain tip ±2 |
| P0.G1.T3 | Auto | Kill sync mid-way (`kill -9`), restart | Resumes and reaches tip; balance unchanged |
| P0.G1.T4 | Manual | Confirm what "Ironwood" is in devtool / librustzcash (pool, network upgrade, testnet activation) | Written down in PROGRESS.md *Decisions* with source links |

### P0.G2 Expiry 0 support (spec spike 2)
| ID | Type | Test | Pass |
|---|---|---|---|
| P0.G2.T1 | Auto | `pczt create --help`; create a PCZT with expiry 0; inspect it | Inspected PCZT shows `expiry_height = 0` |
| P0.G2.T2 | Auto | Build, sign and send an expiry-0 tx **immediately** (online baseline) | Mined within 10 blocks |
| P0.G2.T3 | Manual | If a patch was needed: diff reviewed and kept in our fork | Patch ≤ ~50 lines, documented in PROGRESS.md |

### P0.G3 Delayed broadcast with an old anchor (spec spike 1)
| ID | Type | Test | Pass |
|---|---|---|---|
| P0.G3.T1 | Auto | Sync, go offline (Wi-Fi off), `pczt create → prove → sign → combine`, save bytes | All steps succeed with no network |
| P0.G3.T2 | Auto | Broadcast those bytes ≥ 4 h later (≥ 192 blocks) | Mined |
| P0.G3.T3 | Auto | Same at ≥ 24 h (≥ 1,152 blocks) | Mined, or the anchor-age limit is found and recorded |
| P0.G3.T4 | Auto | Broadcast the same bytes twice | Second attempt is harmless (duplicate / already mined); no state damage |
| P0.G3.T5 | Auto | Sign two txs spending the same note; broadcast both | First mined, second rejected; the rejection message is recorded (needed for P4) |

### P0.G4 Bluetooth blob relay (spec spike 4, runs in parallel)
A throwaway Android app that sends random blobs over BLE GATT. No Zcash involved.

| ID | Type | Test | Pass |
|---|---|---|---|
| P0.G4.T1 | Real phone | 100 × 20 KB transfers, 1 hop, screens on | ≥ 95% delivered within 60 s; 100% checksum match on delivered |
| P0.G4.T2 | Real phone | Same with screens off, phones in pockets, foreground service | ≥ 95% within 60 s |
| P0.G4.T3 | Real phone | 2 hops: A → B → C | ≥ 90% delivered within 120 s |
| P0.G4.T4 | Real phone | Run T1 across every pairing of the 3 demo phones (different brands) | Every pairing passes T1 |
| P0.G4.T5 | Emulator | T1 between two emulators over netsim (shakes out the test app before phones arrive) | 100% delivered within 60 s |

**Gate P0:** P0.G2 and P0.G3 pass (or the anchor window is known and acceptable). P0.G4 decides whether Phase 3 leans on BLE or Wi-Fi Direct.

---

## Phase 1: Rust offline core

One crate, `offline-core` (library + `offline-cli` binary), proven on desktop before it touches Android.

### P1.G1 Crate scaffold and wallet access
| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G1.T1 | Auto | `cargo build && cargo test && cargo clippy -- -D warnings` | All green |
| P1.G1.T2 | Auto | `offline-cli status` against the synced testnet wallet | Balance equals zcash-devtool's, to the zatoshi |

### P1.G2 Note store
Per note: value, nullifier, witness, anchor height, status. The only legal status moves are `available → spent-offline → spent` and `available → spent`.

| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G2.T1 | Auto | Property test: 10,000 random sequences of status changes | No illegal transition ever accepted |
| P1.G2.T2 | Auto | `kill -9` the writer at random points, 1,000 runs | DB always opens; no note lost or duplicated; integrity check passes |
| P1.G2.T3 | Auto | Import notes from the synced wallet | Sum of `available` notes = wallet shielded balance; every note has a witness |

### P1.G3 Denomination split
| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G3.T1 | Auto | Plan splits for 10,000 random balances (0.001 to 100 ZEC) | Outputs + ZIP 317 fee = input exactly; ≤ 20 outputs; fee matches formula |
| P1.G3.T2 | Auto | Balance too small to pay the fee | Clean error, nothing built |
| P1.G3.T3 | Auto | Run a real split on testnet, wait for mining, re-sync | Every denomination note is `available` with a fresh witness |

### P1.G4 Coin selection
Exact sum first, smallest overpay second, never touches non-`available` notes. The fee depends on how many actions are used, so selection must account for it.

| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G4.T1 | Auto | 10,000 random note sets (≤ 20 notes) vs a brute-force solver | Finds an exact match whenever one exists; otherwise overpay equals brute-force minimum |
| P1.G4.T2 | Auto | Note sets containing `spent-offline` / `spent` notes | Never selected, in any case |
| P1.G4.T3 | Auto | Amount + fee where adding a note raises the fee | Selected sum ≥ amount + final fee, every time |
| P1.G4.T4 | Auto | Time 1,000 selections on 20 notes | p99 < 50 ms |

### P1.G5 Offline payment builder
PCZT create → prove → sign → combine → extract bytes; expiry 0; ZIP 317 fee; spent-offline record written **before** bytes are returned.

| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G5.T1 | Auto | Build a payment with networking off | Succeeds; no socket opened (checked with a no-network sandbox or Wi-Fi off) |
| P1.G5.T2 | Auto | Parse the extracted bytes | `expiry_height = 0`; fee = ZIP 317 for its action count |
| P1.G5.T3 | Auto | Build 10 payments back to back in one offline session | No note used twice across the 10 |
| P1.G5.T4 | Auto | `kill -9` between the record write and the return, 100 runs | After restart: notes are `spent-offline` and the raw bytes are recoverable |
| P1.G5.T5 | Auto | Broadcast all 10 payments ≥ 1 h later | All 10 mined |

### P1.G6 Receive verifier
| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G6.T1 | Auto | Verify a valid payment with the receiver's viewing key | Amount, address and memo match what was sent |
| P1.G6.T2 | Auto | Payment addressed to someone else | Reported "not for this wallet" |
| P1.G6.T3 | Auto | Tamper with proof, signature or value bytes, 1,000 variants | All rejected |
| P1.G6.T4 | Auto | Fuzz with 100,000 random / truncated inputs | No panic, no hang (> 1 s) |
| P1.G6.T5 ➕ | Auto | Anchor not among roots the receiver has synced | Flagged "anchor unknown", not shown as valid |
| P1.G6.T6 | Auto | Return the payment's nullifiers | Match the nullifiers the sender recorded |

### P1.G7 Android cross-compile (spec spike 3)
| ID | Type | Test | Pass |
|---|---|---|---|
| P1.G7.T1 | Auto | `cargo ndk -t arm64-v8a build --release` | Builds `.so` and `offline-cli` for aarch64-linux-android |
| P1.G7.T2 | Emulator | Run `offline-cli pay` via `adb` with the phone in airplane mode | Produces signed bytes |
| P1.G7.T3 | Real phone | 10 builds on the **slowest** demo phone | Median proving < 30 s, worst < 45 s |
| P1.G7.T4 | Real phone | Peak memory during proving (`/proc/<pid>/status` VmHWM) | < 1 GB |
| P1.G7.T5 | Emulator | Broadcast phone-built bytes from desktop later | Mined |

**Gate P1:** a scripted end-to-end run (`stress/p1/e2e.sh`): CLI syncs, goes offline, pays, waits, broadcasts, and the payment confirms on testnet. Passes 3 times in a row.

---

## Phase 2: Android wallet shell

Kotlin app; the SDK handles sync, `offline-core` handles offline work.

### P2.G1 Project setup and bridge
| ID | Type | Test | Pass |
|---|---|---|---|
| P2.G1.T1 | Auto | `./gradlew assembleDebug` from a clean clone | Builds |
| P2.G1.T2 | Auto | Instrumented test calls into `offline-core` | Returns version string |
| P2.G1.T3 | Auto | Force a Rust error and a Rust panic across the bridge | Kotlin gets an exception; app does not crash |
| P2.G1.T4 ➕ | Auto | `offline-core` opens the SDK's wallet DB | Reads the same balance the SDK shows (versions pinned to match) |

### P2.G2 Wallet create, restore, sync, balance
| ID | Type | Test | Pass |
|---|---|---|---|
| P2.G2.T1 | Emulator | Create a wallet; restore the same seed on a second phone | Same address and balance on both |
| P2.G2.T2 | Emulator | Kill the app mid-sync, 5 times | Resumes each time and reaches tip |
| P2.G2.T3 | Emulator | Shielded balance vs zcash-devtool on the same seed | Equal to the zatoshi |

### P2.G3 Prepare-for-offline screen
| ID | Type | Test | Pass |
|---|---|---|---|
| P2.G3.T1 | Emulator | Tap Prepare → split broadcast → mined → screen shows denominations | Shown set matches the note store |
| P2.G3.T2 | Emulator | Kill the app while waiting for mining | Restart resumes waiting; no second split tx |
| P2.G3.T3 | Emulator | Double-tap Prepare | Exactly one split tx |

### P2.G4 Pay and receive by animated QR
| ID | Type | Test | Pass |
|---|---|---|---|
| P2.G4.T1 | Real phone | 20 × 20 KB QR transfers between two phones | 100% intact; median < 15 s |
| P2.G4.T2 | Real phone | Cover the QR / skip frames mid-transfer | Still completes (fountain-coded frames) or fails cleanly |
| P2.G4.T3 | Emulator | Sender's notes after Pay | `spent-offline` **before** the QR appears |
| P2.G4.T4 | Emulator | Receiver after scan | Shows "received, not yet settled" with the right amount |

**Gate P2:** both phones in airplane mode pay each other by QR; either phone broadcasts later; the payment settles on testnet. 5 runs, both directions.
*Emulator form:* the same on two emulators, with QR frames passed through the emulator's virtual camera or a test hook.

---

## Phase 3: Bluetooth mesh

### P3.G1 BLE transport
| ID | Type | Test | Pass |
|---|---|---|---|
| P3.G1.T1 | Auto | Unit harness: drop, duplicate and reorder chunks | Reassembles or fails cleanly; corrupt data is never accepted |
| P3.G1.T2 | Real phone | 1,000 random 1–30 KB blobs between two phones | 100% integrity on delivered; ≥ 95% delivered |
| P3.G1.T3 | Real phone | 20 KB transfer time | Median < 10 s |

### P3.G2 Discovery and handshake
| ID | Type | Test | Pass |
|---|---|---|---|
| P3.G2.T1 | Real phone | Two app phones come into range, 20 trials | Discover each other within 15 s every time |
| P3.G2.T2 | Real phone | Other BLE devices nearby (headphones, watches) | Ignored |
| P3.G2.T3 | Auto | Peer with a different protocol version | Refused with a clear reason |

### P3.G3 Store-and-forward queue
| ID | Type | Test | Pass |
|---|---|---|---|
| P3.G3.T1 | Auto | Same tx arrives from 3 peers | Stored once (dedupe by txid) |
| P3.G3.T2 | Auto | Hop limit N | Never forwarded past N hops |
| P3.G3.T3 | Emulator | Reboot the phone with 5 queued txs | All 5 still queued |
| P3.G3.T4 | Auto | Fill past the queue cap | Oldest settled entries evicted first; unsettled ones kept |
| P3.G3.T5 ➕ | Auto | Peer sends an invalid tx (bad proof) | Rejected before storing; never forwarded |

### P3.G4 Connectivity watcher
| ID | Type | Test | Pass |
|---|---|---|---|
| P3.G4.T1 | Emulator | Turn airplane mode off with 5 queued txs | All broadcast within 30 s |
| P3.G4.T2 | Auto | lightwalletd unreachable, then back | Backs off and retries; succeeds once it's reachable |
| P3.G4.T3 | Auto | Broadcast a tx that is already mined | Treated as success |
| P3.G4.T4 | Emulator | Toggle connectivity 20 times quickly | No crash, no duplicate state |

### P3.G5 Background relaying
| ID | Type | Test | Pass |
|---|---|---|---|
| P3.G5.T1 | Real phone | Screen off for 30 min, then a payment arrives nearby | Relayed |
| P3.G5.T2 | Emulator | Force Doze (`adb shell dumpsys deviceidle force-idle`) | Still relays |
| P3.G5.T3 | Real phone | Battery drain over 1 h of relaying | Measured and recorded (no pass line; informs the demo) |

**Gate P3:** phone A pays phone B offline; phone C picks it up, leaves range, reconnects and broadcasts; it settles. 5 runs.
*Emulator form:* three emulators on netsim; "leaves range" = C turns Bluetooth off, then turns networking on.

---

## Phase 4: Double-spend layers

### P4.G1 Write-before-send and crash recovery
| ID | Type | Test | Pass |
|---|---|---|---|
| P4.G1.T1 | Emulator | Kill the app at random points in the pay flow, 50 runs | Never "bytes sent but notes still `available`" |
| P4.G1.T2 | Emulator | Restart after each kill | Pending payments listed with raw bytes and can be rebroadcast |

### P4.G2 Nullifier gossip and conflict alert
| ID | Type | Test | Pass |
|---|---|---|---|
| P4.G2.T1 | Emulator | Two conflicting txs injected on two phones; a third phone meets both | Conflict alert within 60 s |
| P4.G2.T2 | Emulator | 100 honest payments through the mesh | Zero false alerts |
| P4.G2.T3 | Auto | Gossip set after settlement | Settled nullifiers pruned; size stays bounded |

### P4.G3 Offline limits
| ID | Type | Test | Pass |
|---|---|---|---|
| P4.G3.T1 | Auto | Payment above the per-payment cap | Sender refuses; receiver warns |
| P4.G3.T2 | Auto | Many small payments adding up past the per-session cap | Refused once the cap is hit |
| P4.G3.T3 | Auto | Reconnect and settle | Session cap resets only after settlement |

### P4.G4 Reconciliation on reconnect
| ID | Type | Test | Pass |
|---|---|---|---|
| P4.G4.T1 | Auto | Matrix: {own tx mined, conflicting tx mined, nothing mined} × {sender, receiver, relay} | All 9 cases end in the right state: `spent`, `settled` or `conflicted` |
| P4.G4.T2 | Emulator | Conflicted payment on the receiver | Clearly labelled; excluded from balance |

**Gate P4:** a deliberate double spend from a second device is flagged on nearby phones within 60 s.
*Emulator form:* the same across three emulators on netsim.

---

## Phase 5: UX and polish

### P5.G1 Payment states end to end
| ID | Type | Test | Pass |
|---|---|---|---|
| P5.G1.T1 | Auto | Drive every state (pending, relayed, settled, conflicted) | Each one renders; no unreachable state |
| P5.G1.T2 | Emulator | Leave a payment unsettled for 24 h | Still shown correctly; no stuck spinner |

### P5.G2 Offline balance view
| ID | Type | Test | Pass |
|---|---|---|---|
| P5.G2.T1 | Auto | Available + locked + pending | Equals the wallet total in every state from P5.G1 |

### P5.G3 Seed-on-two-devices warning
| ID | Type | Test | Pass |
|---|---|---|---|
| P5.G3.T1 | Emulator | Restore a seed; go offline | Warning shown both times and must be acknowledged |

### P5.G4 First-time user run
| ID | Type | Test | Pass |
|---|---|---|---|
| P5.G4.T1 | Real phone | Someone who hasn't seen the app pays offline with no help | Completes in < 60 s |

### P5.G5 Receiver gives change (cut first if needed)
| ID | Type | Test | Pass |
|---|---|---|---|
| P5.G5.T1 | Emulator | Pay 0.07 with a 0.1 note; receiver returns 0.03 offline | Both balances correct after settlement |

---

## Phase 6: Mainnet and rehearsal

### P6.G1 Mainnet runs
| ID | Type | Test | Pass |
|---|---|---|---|
| P6.G1.T1 | Real phone | Full flow on mainnet with small amounts, 10 times | ≥ 9 of 10 settle; every failure root-caused in PROGRESS.md |

### P6.G2 Demo rehearsal and video
| ID | Type | Test | Pass |
|---|---|---|---|
| P6.G2.T1 | Real phone | Run the spec's demo script 3 times | Each run < 3 min and ends on the block explorer |
| P6.G2.T2 | Manual | Recorded video | Shows explorer confirmation on screen |

### P6.G3 Field test
| ID | Type | Test | Pass |
|---|---|---|---|
| P6.G3.T1 | Real phone | Market or campus with weak signal | Payment settles; problems logged |

---

## Phase 7: Submission

### P7.G1 README
| ID | Type | Test | Pass |
|---|---|---|---|
| P7.G1.T1 | Auto | Follow the README from a fresh clone in an empty directory | CLI and APK build with no undocumented steps |
| P7.G1.T2 | Manual | README covers architecture, threat model and limits | Matches the spec's double-spend section |

### P7.G2 Submit
| ID | Type | Test | Pass |
|---|---|---|---|
| P7.G2.T1 | Manual | Check against ZECATHON rules (mainnet, licence, video length, format) | Every rule met |
| P7.G2.T2 | Manual | Submission confirmed on the ZECATHON dashboard | Confirmation recorded |
