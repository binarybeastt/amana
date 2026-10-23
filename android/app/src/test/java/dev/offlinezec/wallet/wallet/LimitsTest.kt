package dev.offlinezec.wallet.wallet

import dev.offlinezec.wallet.core.PendingPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4.G3 Auto tests: the offline caps (optional, off by default). The wallet is modelled as what
 * `pendingPayments` returns (stored, unmined txs of ours), which is all [Limits] reads.
 */
class LimitsTest {
    private val zec = 100_000_000L
    private val on = Caps(enabled = true, perPayment = zec / 10, perSession = zec / 2) // 0.1 / 0.5

    /** A wallet DB as the policy sees it: our stored payments, minus those mined. */
    private class Wallet {
        val stored = ArrayList<PendingPayment>()
        val mined = HashSet<String>()
        var online = false
        private var n = 0
        fun pending() = stored.filter { it.txid !in mined }

        /** Payer.pay's order: refuse under the caps, else sign and store. Returns the txid or null. */
        fun pay(caps: Caps, amount: Long): String? {
            if (Limits.refusal(caps, pending(), amount) != null) return null
            return "tx${n++}".also { stored += PendingPayment(it, expiryHeight = 0, rawLen = 9000, amount = amount, fee = 10_000) }
        }
    }

    @Test fun t1_overPerPaymentCap_senderRefuses_receiverWarns() {
        val w = Wallet()
        val r = Limits.refusal(on, w.pending(), on.perPayment + 1)
        assertNotNull(r)
        assertTrue(r!!, "per payment" in r)
        assertNull(w.pay(on, on.perPayment + 1))
        assertTrue(w.stored.isEmpty())
        // At the cap is allowed.
        assertNotNull(w.pay(on, on.perPayment))
        // Receiver: warns above its own cap, accepts either way (warning only, no refusal path).
        assertNotNull(Limits.warning(on, on.perPayment + 1))
        assertNull(Limits.warning(on, on.perPayment))
        assertNull(Limits.warning(on, 1))
    }

    @Test fun t2_smallPaymentsPastSessionCap_refusedOnceHit() {
        val w = Wallet()
        val small = 3 * zec / 100 // 0.03
        var accepted = 0
        var firstRefusal = -1
        for (i in 0 until 40) {
            if (w.pay(on, small) != null) accepted++ else if (firstRefusal < 0) firstRefusal = i
        }
        assertEquals(16, accepted) // 16 × 0.03 = 0.48; the 17th would be 0.51 > 0.5
        assertEquals(16, firstRefusal)
        assertEquals(16 * small, Limits.unsettled(w.pending()))
        val msg = Limits.refusal(on, w.pending(), small)!!
        assertTrue(msg, "session limit" in msg && "0.48" in msg)
        // Whatever still fits is allowed; nothing more after it.
        assertNotNull(w.pay(on, 2 * zec / 100))
        assertEquals(on.perSession, Limits.unsettled(w.pending()))
        assertNull(w.pay(on, 1))
        // Exact fit: ten 0.05 payments reach 0.5 exactly, the eleventh is refused.
        val w2 = Wallet()
        repeat(10) { assertNotNull(w2.pay(on, 5 * zec / 100)) }
        assertNull(w2.pay(on, 5 * zec / 100))
        // An online tx of ours (e.g. a split; expiry ≠ 0) is not offline spending.
        val w3 = Wallet()
        w3.stored += PendingPayment("split", expiryHeight = 4_600_000, rawLen = 9000, amount = 5 * zec, fee = 10_000)
        assertEquals(0, Limits.unsettled(w3.pending()))
        assertNotNull(w3.pay(on, on.perPayment))
    }

    @Test fun t3_reconnectAndSettle_resetsOnlyAfterSettlement() {
        val w = Wallet()
        val txs = (0 until 5).map { w.pay(on, on.perPayment)!! } // 0.5: full
        assertNull(w.pay(on, 1))
        // Reconnect and broadcast: nothing mined yet, so still full.
        w.online = true
        assertNull(w.pay(on, 1))
        // A crash or restart: the total is re-derived from the same DB rows, so still full.
        assertEquals(on.perSession, Limits.unsettled(ArrayList(w.pending())))
        assertNull(w.pay(on, 1))
        // Two are mined: exactly their room frees up.
        w.mined += txs.take(2)
        assertEquals(3 * on.perPayment, Limits.unsettled(w.pending()))
        assertNotNull(w.pay(on, on.perPayment))
        assertNotNull(w.pay(on, on.perPayment))
        assertNull(w.pay(on, 1))
        // Everything mined: back to zero, the whole session cap is free again.
        w.mined += w.stored.map { it.txid }
        assertEquals(0, Limits.unsettled(w.pending()))
        repeat(5) { assertNotNull(w.pay(on, on.perPayment)) }
        assertNull(w.pay(on, 1))
    }

    @Test fun capsOff_nothingRefusedOrWarned() {
        val off = on.copy(enabled = false)
        assertEquals(Caps(), Caps().copy(enabled = false)) // the default is off
        val w = Wallet()
        repeat(200) { assertNotNull(w.pay(off, 10 * zec)) } // far past both caps
        assertNull(Limits.refusal(off, w.pending(), 1_000 * zec))
        assertNull(Limits.warning(off, 1_000 * zec))
        assertNull(Limits.refusal(Caps(), w.pending(), 1_000 * zec))
        assertNull(Limits.warning(Caps(), 1_000 * zec))
    }
}
