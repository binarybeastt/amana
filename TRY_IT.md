# Try Amana

Pay with Zcash with no network at all, on your own Android phone. About 15 minutes, most of it waiting
for the testnet to confirm. Everything here is on the **Zcash testnet**: the coins (TAZ) are free and have no
value.

**You need:** an Android phone (Android 8.1 or newer, 64-bit ARM; almost every phone from the last 6 years).
A second phone is optional: it lets you see the receiving side too.

## 1. Install (1 min)

1. On the phone, download **`amana-testnet.apk`** from the [latest release](../../releases/latest).
2. Open it. Android will ask to allow installs from your browser: allow it, then **Install**.
   (Android may also warn that the app is from an unknown developer: it's a hackathon build, signed for testing.
   Choose *Install anyway*.)

## 2. Create a wallet and get test coins (3 min)

1. Open **Amana** → **Create wallet**. (Restore works too, with any testnet seed phrase.)
2. On the home screen tap **Receive** → **Copy** to copy your address (it starts with `utest1`).
3. Go to **[fauzec.com](https://fauzec.com/)**, paste the address, pass the human check and claim **1 TAZ**.
4. Back in Amana: the coin shows up within a minute or two and can be used about 3 minutes after the claim.

## 3. Prepare offline cash, while you have signal (about 10 min)

1. Tap **Prepare** → keep the amount (all of it) → **Add to offline cash**.
2. Wait until the green line on the balance card shows it as **ready offline**. The testnet has to confirm the
   transaction a few times first (a block is about 75 seconds), usually 5–10 minutes.

Like drawing cash from an ATM: from now on this money can be spent with no network.

## 4. Pay with no network (2 min)

1. Turn on **airplane mode**. (Wi-Fi and mobile data off; Bluetooth can stay on.)
2. Tap **Pay**. In **To**, paste this address (our demo shop), or scan a friend's Amana address QR:

   ```
   utest173rezlqpgdqsh8c987c0f8dhrmx8g8lqas04c92pum8jznef0jphlvws36vduyverkyv7cxc3avyyyerh4tujp903gm8essc0d7lqeqettfl8ruj84k2yv5xf9dmecvgntrw6qkpd373fyymty8nnpp3hngkkpz4mc3g8fnxmz7rpqz6htvp2az3mhjj32lcm6ydcw66ljqh5vjxxnt
   ```

3. Enter **0.01** → **Pay**. The phone builds, proves and signs a fully shielded Zcash payment by itself
   (about 15–30 seconds), then shows it as a moving QR code: **Show the receiver**.

That payment now exists only on your phone. No network was used.

## 5. Settle (2–3 min)

1. Turn airplane mode **off**.
2. Amana sends the stored payment to the network by itself. In **Payments** it goes from *pending* to
   *relayed*, then **settled** once it's mined (about a block).

## With a second phone (optional)

Install Amana on the second phone too (it doesn't need coins to receive). Put **both** in airplane mode.

- **Receive by QR:** on the second phone tap **Receive** → **Show my address**; pay it from the first phone by
  scanning that code in **To**. Then on the second phone tap **Receive** → **Scan a payment** and point it at the
  first phone's moving QR. It checks the payment itself, offline, and shows **Received, not yet settled**.
- **Carried over Bluetooth:** leave Bluetooth on. Phones running Amana pass unsettled payments to each other;
  whichever reaches the network first delivers it. Payments are sealed: relays can't read them.
- **Settle:** turn either phone back online. Both end up showing **settled**.

## Things to know

- **One seed, one phone.** Never use the same seed phrase on two phones offline: only one of two payments
  from the same money can settle. Amana warns you when you restore a wallet, and phones nearby raise a
  **Double spend** alert if they ever see it happen.
- **Received offline** means *checked, not yet settled*: the payment is real and valid, but it's final only
  once a phone takes it to the network.
- The faucet gives one claim per address per 24 hours.

Questions or stuck? Open an issue on this repository.
