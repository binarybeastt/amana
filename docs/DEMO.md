# Demo video: Amana

**Status:** built 2026-10-27: `demo/out/amana.mp4` (Remotion composition `Amana`, `demo/motion/src/Amana.tsx`).
Story agreed with the user: problem → why → solution → how it holds up → where it goes. Earlier cuts (`demo/out/demo.mp4`, `demo-motion.mp4`, `launch.mp4`, `vertical.mp4`) are superseded:
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

## Storyboard (as built)

Times are approximate (scenes cross-dissolve, 16 frames). Every app screen is a real recording, shown whole.

| # | Scene | On screen | Text | Sound |
|---|---|---|---|---|
| 1 | Problem: the map | NASA night image of Europe, Africa and Asia, slow push-in; region names; dims for the numbers | "Europe, Africa and Asia, at night." → **666M** "people live without electricity." → **300M** "live outside mobile broadband coverage." Sources under each | bed; low hit on each number |
| 2 | Problem: shutdowns | Black | "Even where the network reaches, **296** times in 2024, the internet was switched off. In 54 countries." (Access Now) | hit |
| 3 | Why | Black | **1.3B** "adults have no account at all. Not at a bank. Not on mobile money." (Global Findex 2025) → "Payments shouldn't need a signal. / And they shouldn't need to be watched." | hit |
| 4 | Brand | The arch strokes in, the green dot drops in and settles, "amana" slides out | "*amana* · trust, held in good faith. One word across Arabic, Swahili, Hausa and Malay." | chime |
| 5 | Open | Amana cold start on the buyer's phone (splash, "Opening wallet…", home with the mark); the phone slides right | "A buyer and a seller at a market stall. Both use Amana." | — |
| 6 | 1 Prepare | `tunde-prepare.mp4`: Prepare → 0.03 → Add to offline cash → ready offline ("Sped up 5×" while sending) | "While there's still signal, the buyer sets aside offline cash. Like drawing cash at an ATM." | — |
| 7 | 2 Pay | `v-tunde-pay.mp4`: Pay → 0.01 → Pay → Signing (6×) → payment QR, push-in | "No network on either phone." → "The phone signs a shielded Zcash payment. Right there." | taps, sent cue |
| 8 | 3 Receive | `v-ada-pay.mp4` (real phone, airplane mode): Receive → Scan → Checking → ✓ 0.01, push-in → home "Received offline, not yet settled" | "The seller scans it. The phone checks the payment itself." → "Received. Not yet settled." | taps, received cue |
| 9 | 4 Carry | `v-relay.mp4`: Payments, pending → relayed | "The payment hops from phone to phone over Bluetooth. Sealed: no phone along the way can read it." → "The first phone to reach the network delivers it." | sent cue |
| 10 | 5 Settle | `v-ada-settle-clean.mp4` (real phone): pending → settled, push-in | "The seller's phone finds signal again." → "**Settled.** Mined in block 4,544,893 on the Zcash testnet." | chime |
| 11 | Holds up: the cheat | Three emulators side by side, lined up on the second payment; a clock counts since it (wait sped up 8×) and stops red when the nearby phone's "Double spend" banner lands | "What if someone spends the same money twice?" → clock **24.2 s** → each label turns "warned" as its own banner lands → "No network anywhere. All three phones warned, within half a second of each other." Source line: a real test pair, never broadcast; emulators over virtual Bluetooth; fastest run 0.5 s | alert + hit |
| 12 | Holds up: facts | Four statements; the real transaction's bytes faint on the right | Real shielded Zcash · Checked on the phone · Relays see nothing · Losses are capped | soft ticks |
| 13 | Where it goes | Black | "Today it moves ZEC. / Next, a shielded digital dollar, on the same rails." | — |
| 14 | End | Mark + "amana", "Money held in trust." Credits | "Built on Zcash · ZECATHON 2026 · Shielded Payments. Real recordings on the Zcash testnet: one real phone, three emulators." | bed fades out |

The demo scenes carry a step rail at the top ("The solution · 1 Prepare 2 Pay 3 Receive 4 Carry 5 Settle"), the
current step lit.

**Changed from the agreed outline, on purpose:** the double spend is not claimed as "half a second". Three takes
on 2026-10-27 (fresh emulator boot) alerted in 43 s, 34.5 s and 24.2 s; the film shows the 24.2 s take with a real
clock and names the fastest measured run (0.5 s) only in the source line. In that take all three phones showed the
banner within 0.47 s of each other (B 23.8 s, C 24.2 s, A 24.3 s after the second payment), which the film says.

## Footage used

Old clips (shot 2026-10-26, before the rename, so no mark in their header; the name never appears in them):
`tunde-prepare.mp4`, `v-tunde-pay.mp4`, `v-ada-pay.mp4`, `v-relay.mp4`, `v-ada-settle.mp4`. The settle clip had a
personal WhatsApp pop-up over the header at the moment of settling; `v-ada-settle-clean.mp4` patches that strip
(x 160–920, y 55–245, 12.2–17.2 s) with the same strip from 12.0 s. Use only the clean copy.

New on the Amana build (2026-10-27, emulators only):
- `v-open.mp4`: cold start on emulator-5556 (splash with the mark → "Opening wallet…" → home).
- `cheat-5554/5556/5558.mp4`: `SETTLE=25 stress/p6/shoot_cheat.sh emulator-5554 emulator-5556 emulator-5558`,
  right after a cold boot. Android's screenrecord writes frames only on change, so clips are padded with their
  last frame and converted to 30 fps (`tpad=stop_mode=clone`) before use. Timings in `demo/motion/src/cheat.ts`.
- `world_night.jpg`: NASA Black Marble 2016 tile C1 (public domain), cropped to lon 0–90°E, lat 4.4–55°N
  (rows 8400–20550 of the 240 px/degree tile), scaled to 3840 px and graded to warm lights on near-black. Raw
  tile in `demo/raw/nasa/`.

Sounds: `hit.wav`, `softtick.wav`, `drone.wav` are synthesized with ffmpeg (`aevalsrc`, brown noise); the cues
are the app's own (`res/raw/cue_*.wav`). No voice yet: the user can record the lines above per scene and they
drop in as `<Audio>` per scene.

Render: `cd demo/motion && npx remotion render src/index.ts Amana ../out/amana.mp4 --codec h264 --crf 18`.

## Data and sources (checked 2026-10-27)

Global on purpose (user's call 2026-10-27: the film should not read as built for one market). Characters are "the
buyer" and "the seller", not named.

| Line | Source |
|---|---|
| 666 million people without electricity | IEA, IRENA, UN Statistics Division, World Bank, WHO: *Tracking SDG7: The Energy Progress Report 2025* (2023 data, 666.4 million) |
| ~300 million outside mobile broadband coverage | GSMA, *The State of Mobile Internet Connectivity 2025* (coverage gap, 4% of people) |
| 296 internet shutdowns in 54 countries in 2024 | Access Now, #KeepItOn report on 2024 (Feb 2025) |
| 1.3 billion adults with no account (bank or mobile money) | World Bank, *Global Findex Database 2025* |
| Night image | NASA Earth Observatory, Black Marble 2016, tile C1 cropped to lon 0–90°E, lat 4.4–55°N, graded (`world_night.jpg`) |

Left out on purpose: GSMA's usage gap (3.1 billion covered but offline: mostly a usage problem, not ours).

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
