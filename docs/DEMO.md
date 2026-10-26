# Demo video: Amana

**Status:** story agreed in outline 2026-10-27 (problem → why → solution → how it holds up → where it goes). Not
yet built. Earlier cuts (`demo/out/demo.mp4`, `demo-motion.mp4`, `launch.mp4`, `vertical.mp4`) are superseded:
the user rejected the TTS voice, the mockups and glows ("AI slop"), cropped screens, and a story that didn't
say why it matters.

## The brief

- **Audience:** ZECATHON judges, Shielded Payments track. They watch on a laptop, often only the video.
- **The one thing they must leave with:** two people with no network just paid each other privately, and it
  really settled. Amana means trust (Hausa/Swahili, from Arabic *amānah*); the film is about money held in
  trust by strangers.
- **Shape:** problem → why we solve it → the solution, demoed for real → how it holds up → where it goes.
- **Format:** 16:9, 1920×1080, about 2:30. Burned-in captions. Voice: the user's own (recorded per scene, after
  the edit is locked); until then the captions carry it. No TTS voice.
- **Honesty rules:** every number on screen has a source line under it. Every app screen is a real recording of
  the current Amana build, shown whole (never cropped, never a mockup). Testnet is said on screen once.

## Motion language

- Black (`#050505`), white (`#F2F2F7`), Inter only. Green (`#30D158`) means one thing: settled / safe, as in the
  app and the mark.
- Not allowed: glows, pills, dot grids, gradients, particle effects, word-by-word typing, bouncing, stock
  footage of people, generic copy ("revolutionising payments").
- Text enters once (short opacity + 12 px rise, ease-out ~350 ms), holds long enough to read twice, and cuts on
  a sound beat. One idea per card. Numbers count up once over ~0.8 s and then stay still.
- Phone screens: the full screen, full height of the frame, on black; text in the free side. Camera moves are
  slow push-ins (to ~1.6×) onto the moment that matters, eased in and out; tap ripples come from the real tap
  log (`demo/raw/taps.jsonl`).
- Sound: room tone under the problem, the app's own cues (`res/raw/cue_*.wav`) under the demo, one low hit on
  the double-spend alert, silence before the end card. No music bed, or a very quiet one.

## Storyboard

Times are targets. Waits (proving, mining) are cut or sped up, with the real duration said in the caption.

### 1. Problem (0:00–0:35)

| # | Time | On screen | Text / voice | Motion and sound | Footage / asset |
|---|---|---|---|---|---|
| 1.1 | 0:00–0:08 | Nigeria at night from space, mostly dark | "This is Nigeria at night." | Very slow push-in on the image; room tone | NASA Black Marble (public domain) cropped to Nigeria. **Download: ask the user first** |
| 1.2 | 0:08–0:16 | Same image, dimmed; number top-left | "About **20 million** Nigerians live where a phone gets no signal at all." · source: Federal Ministry of Communications, NUCAP rollout, Oct 2026 | Number counts up, holds | — |
| 1.3 | 0:16–0:24 | Same, second line replaces the first | "**86.8 million** have no electricity. The most of any country." · source: World Bank, Tracking SDG7 2025 | Same treatment | — |
| 1.4 | 0:24–0:35 | Cut to black; a POS-style line | "Even in the city, about **1 in 7** card payments at a POS fails." · source: NIBSS | Hard cut to black, one dry error beep | Optional: a real shot of a POS "network" error (user films) |

### 2. Why we solve it (0:35–0:55)

| # | Time | On screen | Text / voice | Motion and sound | Footage / asset |
|---|---|---|---|---|---|
| 2.1 | 0:35–0:43 | Black | "**28.8 million** adults are outside the financial system. In the Northwest, nearly half." · source: EFInA A2F 2023 | Number counts up | — |
| 2.2 | 0:43–0:50 | Black | "Payments shouldn't need a signal." … "And they shouldn't need to be watched." | Two lines, second after a beat | — |
| 2.3 | 0:50–0:55 | The Amana mark draws in (arch, then the dot drops in), wordmark | "Amana. *Trust, in Hausa.*" | The arch strokes in (~0.6 s), the green dot settles; one soft tone | `docs/brand/amana-wordmark.svg` |

### 3. The solution, for real (0:55–1:55)

Each beat: the full phone screen, centre-left; one caption line right. Two people: **Tunde** (buyer, emulator)
and **Ada** (seller, the real phone, airplane mode).

| # | Time | On screen | Text / voice | Motion and sound | Footage |
|---|---|---|---|---|---|
| 3.1 | 0:55–1:05 | Tunde's Home → Prepare → "ready offline" | "While he still has signal, Tunde sets aside offline cash. Like drawing cash at an ATM." caption: "Ready in ~4 min (sped up)" | Push-in on "ready offline"; tick cue | **New:** prepare on the Amana build |
| 3.2 | 1:05–1:20 | Tunde: Pay → amount 0.01 → Signing → payment QR | "No network on either phone. Tunde's phone signs a shielded Zcash payment, right here." | Tap ripples; push-in on the QR | **New:** `v-tunde-pay` re-shot on the Amana build |
| 3.3 | 1:20–1:35 | Ada (real phone, airplane mode): Receive → Scan → Checking → ✓ received → Home "Received offline, not yet settled" | "Ada scans it. Her phone checks the proof itself, offline. Received, not yet settled: honest about where the money is." | Status bar shows airplane icon (personal notifications blurred); received cue | **New:** `v-ada-pay` re-shot on the Amana build |
| 3.4 | 1:35–1:45 | Split: Ada's phone and a stranger's phone (Payments: relaying → relayed) | "Any phone with Amana carries it along over Bluetooth. It can't read it." | Simple horizontal cut between the two full screens, no animated "data line" | **New:** relay on Amana build (emulators, fresh boot) |
| 3.5 | 1:45–1:55 | Ada's Home: pending → **settled** (green) | "The first phone to find signal sends it. Settled." caption: "Mined in block N on the Zcash testnet" | Push-in on the green "settled"; chime | **New:** settle on the Amana build |

### 4. How it holds up (1:55–2:15)

| # | Time | On screen | Text / voice | Motion and sound | Footage |
|---|---|---|---|---|---|
| 4.1 | 1:55–2:05 | Three screens: the same note paid to two sellers; the third phone's red "Double spend" banner | "Spend the same money twice, and every phone nearby is warned in half a second." | Hold on the banner; one low hit | **New:** double spend right after a fresh emulator boot |
| 4.2 | 2:05–2:15 | Black, three short lines | "Real shielded Zcash. The proof is checked on the phone. Relays see only encrypted bytes. Losses are capped." | One line at a time | Optional: real tx bytes (`offline-cli inspect-raw`) dim behind |

### 5. Where it goes (2:15–2:30)

| # | Time | On screen | Text / voice | Motion and sound | Footage |
|---|---|---|---|---|---|
| 5.1 | 2:15–2:24 | Black | "Today it moves ZEC. Next, a shielded digital dollar, on the same rails." | — | — |
| 5.2 | 2:24–2:30 | Mark + wordmark, small line under | "Amana. Money held in trust." · "Built on Zcash · ZECATHON 2026 · Shielded Payments · recorded on testnet" | Silence, then the dot settles | — |

## Footage and assets to get

All app footage is re-shot on the Amana build (the old clips show "Offline ZEC" and no mark).

1. Install main's build on the phone and all three emulators. emulator-5554's storage is full: free space or
   wipe its app data first (it holds no needed wallet).
2. Restart all emulators right before the relay and double-spend takes (netsim jams; see `CLAUDE.md`).
3. Shots, in order: 3.1 prepare (Tunde), 3.2 pay (Tunde), 3.3 receive (Ada, real phone, airplane mode, user
   unlocks and taps), 3.4 relay (stranger), 3.5 settle (Ada), 4.1 double spend (three emulators).
4. Assets: NASA Black Marble of Nigeria (download needs the user's OK); the brand SVGs in `docs/brand/`.
5. Optional, filmed by the user: a real POS "network" failure, hands holding the phone in airplane mode.
6. Voice: the user reads the voice lines above, one memo per scene, after the picture is locked.

## Data and sources (checked 2026-10-27)

| Line | Source |
|---|---|
| ~20 million with no signal | Federal Government NUCAP rollout (3,700 towers), Oct 2026; USPF earlier said 23 million |
| 86.8 million without electricity, most of any country | World Bank, *Tracking SDG7: The Energy Progress Report 2025* (2023 data) |
| ~1 in 7 POS card payments fail | NIBSS: 13–15% failure on POS (undated page); 20% peak in April 2019 |
| 28.8 million adults excluded; Northwest 47% | EFInA, *Access to Financial Services in Nigeria* 2023 |

Left out on purpose: GSMA's "120 million without mobile internet" (mostly people with coverage who don't use
it) and 27% smartphone ownership (true; answer it in Q&A, not in the film).

## Build

Remotion in `demo/motion/` (new composition, 1920×1080, 30 fps), reusing the tap-ripple and push-in code
from `src/Vertical.tsx`. Footage via `stress/p6/shoot.sh` / `stress/p6/demo_lib.sh`.

## How the first cut was made (2026-10-26)

Roles swapped from the plan above so no webcam is needed: **Ada is the real phone**, Tunde is
`emulator-5556`. The payment QR reaches the phone through the debug QR bridge (`relay_frames` in
`stress/p2/lib.sh`); the phone decodes and verifies it itself, offline. Footage: `adb screenrecord`
via `stress/p6/demo_lib.sh`; cards: `demo/cards/make_cards.py`; draft voice: macOS `say`
(Samantha) from `demo/vo/lines.txt`; assembly: `demo/build_video.sh` → `demo/out/demo.mp4`.

Gotchas found while recording:
- Sync **both** phones right before they go offline. A receiver that synced before the sender's
  anchor rejects the payment ("sender's anchor is unknown here").
- Take the payer offline only after a complete sync. Taken offline mid-sync, its wallet library
  saw no spendable notes (InsufficientFunds, available 0) until it synced again.
- Typing a 217-character address with `adb input text` on an emulator drops characters; use the
  `PAY` hook for the payment itself.
- The public testnet explorer (testnet.zcashexplorer.app) follows another testnet branch (tip
  ~4,494,200), so it can't show our transactions; the "on chain" card is built from the real
  transaction bytes (`offline-cli inspect-raw`).
- Record the double spend within minutes of a fresh emulator boot; Android's screenrecord stops
  at 3 minutes. The banner shot used is from the run where it came 0.5 s after the second spend.
