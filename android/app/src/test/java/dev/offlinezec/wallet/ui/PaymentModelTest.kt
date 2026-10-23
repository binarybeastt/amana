package dev.offlinezec.wallet.ui

import dev.offlinezec.wallet.core.Note
import dev.offlinezec.wallet.core.PendingPayment
import dev.offlinezec.wallet.mesh.RelayQueue
import dev.offlinezec.wallet.wallet.Received
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5.G1.T1: every payment state (pending, relayed, settled, conflicted, and rejected) is reached
 * from real inputs for sent, received and relayed payments, and each renders: one row per txid,
 * a chip text, a label, a colour. P5.G2.T1: available + locked + pending is the wallet total in
 * every one of those states, through a payment's whole life.
 */
class PaymentModelTest {
    private fun tx(i: Int) = "%02x".format(i).repeat(32)
    private val now = 1_000_000_000_000L

    private fun entry(txid: String, state: RelayQueue.State, mine: Boolean = false, forUs: Boolean = false) =
        RelayQueue.Entry(txid, ByteArray(8), if (mine) 0 else 2, "test", now - 60_000, state = state, mine = mine, forUs = forUs,
            minedHeight = if (state == RelayQueue.State.SETTLED) 100L else null)

    private fun received(txid: String, settled: Boolean = false, conflicted: Boolean = false) =
        Received(txid, 10_000, "hi", now - 30_000, ByteArray(8), minedHeight = if (settled) 100 else null,
            conflictedBy = if (conflicted) "double spend" else null)

    private fun pending(txid: String) = PendingPayment(txid, 0, 100, 20_000, 1_000)

    /** Every relay-queue state, for each kind of payment this phone can hold. */
    @Test
    fun everyStateRenders() {
        val states = RelayQueue.State.entries
        val queue = ArrayList<RelayQueue.Entry>()
        val pend = ArrayList<PendingPayment>()
        val recv = ArrayList<Received>()
        val expect = HashMap<String, Pair<Direction, PayStatus>>()
        var i = 1
        for (s in states) {
            // Own payment still in the wallet's pending list (not yet seen mined).
            if (s != RelayQueue.State.SETTLED) {
                val t = tx(i++); pend += pending(t); queue += entry(t, s, mine = true); expect[t] = Direction.SENT to statusOf(s)
            }
            // Own payment no longer pending (mined or lost): from the queue and the remembered amount.
            val t2 = tx(i++); queue += entry(t2, s, mine = true); expect[t2] = Direction.SENT to statusOf(s)
            // A stranger's payment we carry.
            val t3 = tx(i++); queue += entry(t3, s); expect[t3] = Direction.RELAY to statusOf(s)
            // Payment to us, received by QR, carried in the queue too.
            if (s == RelayQueue.State.QUEUED || s == RelayQueue.State.SENT) {
                val t4 = tx(i++); recv += received(t4); queue += entry(t4, s, forUs = true); expect[t4] = Direction.RECEIVED to statusOf(s)
            }
        }
        // Received payment settled, conflicted, and with no queue entry at all; own pending with no entry (crash recovery).
        tx(i++).let { recv += received(it, settled = true); expect[it] = Direction.RECEIVED to PayStatus.SETTLED }
        tx(i++).let { recv += received(it, conflicted = true); expect[it] = Direction.RECEIVED to PayStatus.CONFLICTED }
        tx(i++).let { recv += received(it); expect[it] = Direction.RECEIVED to PayStatus.PENDING }
        tx(i++).let { pend += pending(it); expect[it] = Direction.SENT to PayStatus.PENDING }

        val amounts = queue.filter { it.mine }.associate { it.txid to 20_000L }
        val rows = paymentRows(pend, recv, queue, amounts, now)
        assertEquals("one row per txid", expect.keys, rows.map { it.txid }.toSet())
        assertEquals(rows.size, rows.map { it.txid }.distinct().size)
        for (r in rows) {
            val (dir, st) = expect.getValue(r.txid)
            assertEquals("direction of ${r.txid.take(4)}", dir, r.direction)
            assertEquals("status of ${r.txid.take(4)} ($dir)", st, r.status)
            assertTrue(r.status.chip.isNotBlank())
            assertTrue("label for $dir $st", r.label.isNotBlank())
            assertTrue(shortTxid(r.txid).length < r.txid.length)
            if (r.direction != Direction.RELAY) assertTrue("amount known for $dir $st", r.amount != null)
            // Only our own payment still on its way can be shown as a QR again.
            assertEquals(r.direction == Direction.SENT && r.txid in pend.map { it.txid } &&
                (st == PayStatus.PENDING || st == PayStatus.RELAYED), r.canShowQr)
            if (st == PayStatus.CONFLICTED) assertTrue(r.label.startsWith("conflicted"))
        }
        // No unreachable state: every status turns up for some payment.
        assertEquals(PayStatus.entries.toSet(), rows.map { it.status }.toSet())
        for (d in Direction.entries) assertTrue(rows.any { it.direction == d })
        // Received rows keep the P4.G4 labels the stress scripts read.
        assertEquals("settled", rows.first { it.txid == tx(i - 4) }.label)
        assertTrue(rows.first { it.txid == tx(i - 3) }.label.contains("not counted"))
        assertEquals("received, not yet settled", rows.first { it.txid == tx(i - 2) }.label)
    }

    /** P5.G1.T2's rule in JVM form: age is words, a day-old payment is still just "pending". */
    @Test
    fun oldPaymentStillPending() {
        val t = tx(1)
        val r = paymentRows(listOf(pending(t)), emptyList(), listOf(entry(t, RelayQueue.State.QUEUED, mine = true)), now = now + 86_400_000)
            .single()
        assertEquals(PayStatus.PENDING, r.status)
        assertEquals("1 d 0 h ago", ago(r.since, now + 86_400_000))
        assertEquals("just now", ago(now, now))
        assertEquals("5 min ago", ago(now, now + 5 * 60_000))
        assertEquals("3 h ago", ago(now, now + 3 * 3_600_000))
    }

    private fun note(txid: String, i: Int, v: Long, h: Int?, st: String, pool: String = "ironwood", by: String? = null) =
        Note("acct", pool, txid, i, v, h, st, by)

    /**
     * P5.G2.T1: one wallet through a payment's life. 1 ZEC in prepared notes (0.5 + 0.3 + 0.2),
     * plus 0.05 of fresh, unconfirmed funds and 0.01 in Orchard. Pay 0.3 offline with the 0.5
     * note (fee 0.0001, change 0.1999). Expected totals are worked out by hand: the SDK's
     * total never counts notes our offline payments spend.
     */
    @Test
    fun splitAddsUpInEveryState() {
        val zec = 100_000_000L
        val fund = tx(9); val pay = tx(10); val rival = tx(11)
        val tip = 1_000L
        val base = listOf(
            note(fund, 1, 3 * zec / 10, 900, "available"),
            note(fund, 2, 2 * zec / 10, 900, "available"),
            note(tx(12), 0, 5 * zec / 100, 995, "available"), // 6 confirmations: locked
            note(tx(13), 0, zec / 100, 900, "available", pool = "orchard"), // not offline-spendable: locked
            note(tx(14), 0, 7 * zec / 10, 800, "spent", by = tx(15)), // long gone
        )
        val big = 5 * zec / 10
        val change = 1999 * zec / 10_000
        val cases = linkedMapOf(
            // Prepared, nothing paid yet.
            "idle" to (base + note(fund, 0, big, 900, "available")) to (zec + 6 * zec / 100),
            // Pending and relayed look the same to the wallet: note spent offline, change unmined.
            "pending" to (base + note(fund, 0, big, 900, "spent-offline", by = pay) + note(pay, 1, change, null, "pending")) to
                (zec - big + change + 6 * zec / 100),
            "relayed" to (base + note(fund, 0, big, 900, "spent-offline", by = pay) + note(pay, 1, change, null, "pending")) to
                (zec - big + change + 6 * zec / 100),
            // Mined at 999: the change is ours but not yet 10 confirmations deep.
            "settled" to (base + note(fund, 0, big, 900, "spent", by = pay) + note(pay, 1, change, 999, "available")) to
                (zec - big + change + 6 * zec / 100),
            // A rival tx from the same seed (0.2 + fee, change 0.2999) was mined instead: ours never will be.
            "conflicted" to (base + note(fund, 0, big, 900, "spent", by = rival) + note(rival, 1, 2999 * zec / 10_000, 999, "available")) to
                (zec - big + 2999 * zec / 10_000 + 6 * zec / 100),
        )
        for ((k, expected) in cases) {
            val (name, notes) = k
            val s = balanceSplit(notes, tip)
            assertEquals("$name: available + locked + pending", expected, s.available + s.locked + s.pending)
            assertEquals("$name: total", expected, s.total)
            // The split's total is exactly the unspent notes, the wallet's own sum.
            assertEquals(name, notes.filter { it.status != "spent" && it.status != "spent-offline" }.sumOf { it.value }, s.total)
            assertTrue(name, s.available >= 0 && s.locked >= 0 && s.pending >= 0)
        }
        // Spot checks of where the value sits.
        val pend = balanceSplit(cases.keys.first { it.first == "pending" }.second, tip)
        assertEquals(5 * zec / 10, pend.available) // 0.3 + 0.2
        assertEquals(6 * zec / 100, pend.locked)
        assertEquals(change, pend.pending)
        assertEquals(big, pend.inPayments)
        val settled = balanceSplit(cases.keys.first { it.first == "settled" }.second, tip)
        assertEquals(0L, settled.pending)
        assertEquals(6 * zec / 100 + change, settled.locked)
        assertFalse(settled.inPayments > 0)
        // Nine blocks on, the change and the fresh funds have their 10 confirmations.
        assertEquals(5 * zec / 10 + 5 * zec / 100 + change, balanceSplit(cases.keys.first { it.first == "settled" }.second, tip + 9).available)
    }

    @Test
    fun keypad() {
        var a = ""
        for (k in "0.0100") a = keypadInput(a, k)
        assertEquals("0.0100", a)
        a = ""; for (k in ".5") a = keypadInput(a, k)
        assertEquals("0.5", a)
        a = ""; for (k in "1..2<<3") a = keypadInput(a, k)
        assertEquals("13", a)
        a = ""; for (k in "0.123456789") a = keypadInput(a, k)
        assertEquals("0.12345678", a)
        a = ""; for (k in "007") a = keypadInput(a, k)
        assertEquals("7", a)
    }
}
