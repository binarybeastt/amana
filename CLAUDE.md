# Offline shielded Zcash wallet

Android wallet that signs shielded Zcash payments offline and relays them phone to phone over Bluetooth until one phone reaches the network. Built for ZECATHON.

## Where things are

- Spec (what we build): https://claude.ai/code/artifact/6b30698e-2158-4baf-ba77-2987d28fb36e (Claude Doc; edit with the docs tools, not as a file)
- `docs/PLAN.md`: phases split into goals, each with stress tests and pass criteria
- `docs/PROGRESS.md`: live status of every goal, test results, decisions, log
- `offline-core/`: Rust crate (library + `offline-cli`)
- `android/`: Kotlin app (ZODL SDK + offline-core over JNI in a `:core` process)
- `spikes/`: Phase 0 spike scripts and raw logs
- `stress/<phase>/`: stress test scripts
- `third_party/`: external clones such as zcash-devtool (git-ignored)

## Critical: devices (read first)

- **The user has ONE real Android phone.** Emulators cannot talk Bluetooth to a real phone (netsim is virtual), so every test that needs Bluetooth between real phones (P0.G4.T1–T4, P3.G1.T2–T3, P3.G2.T1–T2, P3.G5.T1) cannot run unless a second phone is found. Do not plan a Bluetooth demo or test on real phones. One phone + emulators CAN do: proving speed/memory on the phone, phone-built bytes broadcast, QR payments phone ↔ emulator (emulator camera = Mac webcam; phone scans the emulator screen), the phone going online and broadcasting.
- **Emulator Bluetooth is broken as of 2026-10-23**: GATT connects never complete on any emulator, even the Phase 0 spike app delivers 0/5, and a full restart on one fresh netsimd did not fix it. Fix plan (not done yet): back up wallets (`stress/backup_emulator.sh`), recreate the three AVDs, restore. Until then, Bluetooth tests (P4.G2.T2, Gate P4) are deferred.
- **User's call (2026-10-23): Gate P4 and the Bluetooth tests are deferred; work moved on to Phase 5 UI polish.** "Start a phase only after the previous gate passes" is suspended for this; don't go back to the gate unprompted, but remind the user it is still open before Phase 6.

## How we work

- Go phase by phase, goal by goal, in PLAN.md order. Start a phase only after the previous gate passes. There is no calendar schedule.
- A goal is done only when all its stress tests pass. Never weaken, skip or reinterpret a pass criterion without the user's OK; record any change under *Decisions* in PROGRESS.md.
- Write a test as a re-runnable script or `cargo test` whenever it can be automated.
- No real phones yet: develop and test on Android emulators (netsim links their Bluetooth). Mark a goal `emu-pass` when its Auto and Emulator tests pass; Real phone tests stay pending and must all pass before Phase 6.

## Keeping progress current (do this without being asked)

Update `docs/PROGRESS.md` in the same step as the work, never batched for later:
- Starting a goal: set it to `doing` and update the **Now** line.
- After any test run that counts: add a row to *Test results* (date, test ID, pass/fail, the command and the key output or log path), and update the goal's "Tests passed" count.
- A goal's last test passes: set it to `done`.
- Something blocks progress: set `blocked`, fill in **Blockers**, and tell the user.
- End of each working session: add a dated line to *Log*.
- Spike results also go into the spec doc's *Verification spikes* section, as the spec asks. At each phase gate, tick that phase's items in the spec doc's *Build order*.

## Working notes

Gotchas learned the hard way:
- `spikes/zcash/env.sh` and `stress/p2/lib.sh` only work when sourced from bash (use `bash -c` or a bash script), not zsh.
- Never background the `nonet` shell function when you need to kill the process; call `sandbox-exec` directly.
- Android's shell does 32-bit arithmetic: subtract timestamps on the host.
- Always sync right before an online build, or a 40-block expiry can already be past.
- In pipelines under `set -o pipefail`, avoid `grep -q` and early-exiting `awk`.
- Don't run tests that copy the live wallet (`wallets/testnet-a`) while another job is syncing or sending from it.
- lightwalletd drops the odd request: use `sync_wallet` / `tip` from env.sh (they retry) in `set -e` scripts.
- `zcash-devtool wallet pay` asks to confirm: pipe `echo y` into it.
- Testnet is past NU7 (activated at 4,465,026). Anything on `zcash_protocol` 0.10.x (zcash-android-sdk 3.x/4.0) signs with the wrong branch ID and is rejected. The app uses the ZODL SDK (`com.zodl.android:zcash-android-sdk:5.2.1-SNAPSHOT`, AGPL-3.0) and offline-core pins the crates it bundles.
- offline-core must only run in the app's `:core` process (`CoreProvider`): it bundles its own SQLite, and two SQLite copies in one process corrupt each other's locking. Use an unstable provider client so the app survives a core-process crash.
- Gradle 8.13 cannot run on Java 25: build with Android Studio's JDK 21 (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).
- Emulator tests: debug builds expose adb hooks (`.debug.TestHooks`) and write `files/state.json`; see `stress/p2/lib.sh`.
- Never boot emulators from a quick-boot snapshot with `-no-snapshot-save`: stopping them discards every write since the snapshot (lost two wallets once). `spikes/ble-blob/emulators.sh` cold-boots with `-no-snapshot`.
- Emulators booted a few seconds apart can each start their own `netsimd` and end up on separate Bluetooth networks (they never see each other; radio cycles don't help). Check `pgrep -f emulator/netsimd` shows one; `emulators.sh start` now waits for the first one's netsimd and fails if there are two.
- Emulator Bluetooth (netsim) degrades after dozens of fast radio cycles (no GATT connects, filtered scans see nothing) until the radios are power-cycled. Airplane mode cycles the radio: for "offline" use `net_off`/`net_on` (Wi-Fi + data) from `stress/p3/lib.sh`.
- Phase 3 helpers: `stress/p3/lib.sh` (`mesh`, `inject`, `log_has`, `bt`, `net_off`). In scripts, check logs with `log_has`, never `mesh_log | grep -q` (pipefail).

## Safety

- Testnet until Phase 6. Never commit seeds, wallet databases or keys; they stay under `wallets/` (git-ignored).
- Mainnet funds are the user's; ask before any mainnet transaction.
