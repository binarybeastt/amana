# Demo video

About 3 minutes, recorded on **testnet** (user's call, 2026-10-25). One real phone plus three
emulators: phone ↔ emulator payments go by QR (the emulator's camera is the Mac webcam), and the
Bluetooth relay runs between emulators (emulator Bluetooth can't reach a real phone).

## Cast

| Role | Device | Notes |
|---|---|---|
| Tunde, buyer | The real phone (Galaxy S21+) | Airplane mode on camera |
| Ada, bread seller | `emulator-5556` (Small_Phone) | Offline (Wi-Fi + data off), Bluetooth on |
| Relay phone, a stranger | `emulator-5558` (Relay_Phone) | Offline until it "walks into signal" |
| Second device of a cheater | `emulator-5554` (Medium_Phone) | Only in scene 6 |

## Scenes and voice-over

Times are targets. Waits (proving, mining) are sped up in the edit; captions in brackets.

**1. Hook (15 s).** Real camera: phone in hand, a stall or shop counter.
> "When the power's out and data is down, people still need to pay. Cash gets stolen. Mobile
> money needs a network. What if your phone could pay privately, with no signal at all?"

**2. Prepare offline cash (20 s).** Phone screen: Prepare, choose an amount, tap; "ready offline" a
minute later (sped up). [Caption: "Ready in about a minute"]
> "While I still have signal, I set aside offline cash, like drawing cash from an ATM. The phone
> splits it into notes, the way a wallet holds bills."

**3. Pay offline (40 s).** Real camera: airplane mode on. Then side by side, phone + Ada's screen:
Tunde scans Ada's address QR off the Mac screen, enters the amount, the phone signs and shows the
payment QR; Tunde holds it to the webcam; Ada's screen: "Received offline, not yet settled."
[Captions: "No internet", "Signed on the phone"]
> "Now there's no internet on either side. Tunde scans Ada's code, and his phone signs a shielded
> Zcash payment right there. Ada's phone checks the proof itself and shows: received, not yet
> settled."

**4. Relay over Bluetooth (35 s).** Ada's and the relay phone's screens: the payment hops over
Bluetooth; the relay phone gets signal and broadcasts. [Captions: "Bluetooth hop", "Encrypted:
the relay learns nothing"]
> "Any phone running the app carries the payment along, and none of them can read it. It's
> encrypted. The first phone to find signal hands it to the Zcash network."

**5. Settle (25 s).** Ada's payment flips to settled; Tunde turns airplane mode off and sees it
settled; cut to the block explorer. [Caption: "Settled at block N"]
> "Settled. And on the blockchain, nobody can see who paid whom, or how much."

**6. The cheat (30 s).** Three emulators: the same note spent twice on two devices; the third
phone shows the red conflict alert within a second. [Caption: "Alert in under a second"]
> "What if someone copies their wallet to a second phone and pays twice? Every phone nearby
> knows in under a second, and the receiver is warned before handing anything over."

**7. Close (15 s).** Title card.
> "We can't make offline double spending impossible without special hardware. So we stop honest
> mistakes, catch cheats in seconds, and cap what anyone can lose, while the strangers carrying
> your money learn nothing. Today it moves Zcash. Tomorrow, the same mesh can carry a shielded
> dollar, for markets that run on cash and patchy data."

## Recording

- Emulator screens: `adb -s <emulator> shell screenrecord` (built in, up to 3 min per clip),
  pulled to the Mac. The app is driven by its debug hooks and taps so takes repeat exactly.
- Phone screen: the same over USB (USB debugging on); you tap.
- Real-world shots (scenes 1 and 3: airplane mode, the phone held to the webcam): filmed by you.
- Narration: your voice, one short voice memo per scene, recorded after the footage.
- Edit: ffmpeg (side-by-side layouts, speed-ups, captions, title cards), first cut by Claude.

## Before recording

1. Main's build on all three emulators and the phone (mesh protocol v3 can't talk to v2).
2. Offline cash prepared on the phone (scene 2 can prepare live; Ada needs none).
3. Restart all three emulators right before scenes 4 and 6: netsim Bluetooth jams after a while
   (see `CLAUDE.md`).
4. Scene 6 uses the double-spend pair in `stress/p4/logs/p4g2-pair/` (made by
   `stress/p4/p4g2_make_pair.sh`), injected while all phones are offline, as in
   `stress/p4/p4g2t1_conflict.sh`; clear queues and alerts after, so the pair is never broadcast.

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
