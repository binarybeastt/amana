# Offline shielded Zcash wallet

Android wallet that signs shielded Zcash payments offline and relays them phone to phone over Bluetooth until one phone reaches the network. Built for ZECATHON.

## Where things are

- Spec (what we build): https://claude.ai/code/artifact/6b30698e-2158-4baf-ba77-2987d28fb36e (Claude Doc; edit with the docs tools, not as a file)
- `docs/PLAN.md`: phases split into goals, each with stress tests and pass criteria
- `docs/PROGRESS.md`: live status of every goal, test results, decisions, log
- `offline-core/`: Rust crate (library + `offline-cli`)
- `android/`: Kotlin app
- `spikes/`: Phase 0 spike scripts and raw logs
- `stress/<phase>/`: stress test scripts
- `third_party/`: external clones such as zcash-devtool (git-ignored)

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

## Safety

- Testnet until Phase 6. Never commit seeds, wallet databases or keys; they stay under `wallets/` (git-ignored).
- Mainnet funds are the user's; ask before any mainnet transaction.
