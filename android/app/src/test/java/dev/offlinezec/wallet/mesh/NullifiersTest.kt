package dev.offlinezec.wallet.mesh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * P4.G2 Auto tests: the gossip set is pruned on settlement and stays bounded (T3), and conflict
 * detection: two txids sharing a nullifier always alert; honest traffic and a tx seen twice
 * under one txid never do. The device-level tests (T1/T2) are stress/p4/p4g2*.sh.
 */
class NullifiersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rnd = Random(42)
    private fun h32() = ByteArray(32).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }

    /** An Orchard-style tx: a txid and two nullifiers (spend + dummy spend). */
    private data class Tx(val txid: String, val nfs: List<String>)
    private fun tx(vararg nfs: String) = Tx(h32(), nfs.toList().ifEmpty { listOf(h32(), h32()) })

    /** A phone's set, recording every conflict callback. */
    private class Phone(file: java.io.File? = null, maxClaims: Int = 4096, maxSettled: Int = 4096) {
        val alerts = ArrayList<Nullifiers.Conflict>()
        val n = Nullifiers(file, maxClaims = maxClaims, maxSettled = maxSettled, onConflict = { alerts += it })
    }

    /** One Bluetooth session as BleMesh runs it: client sends its claims, server merges and answers with its own. */
    private fun session(client: Nullifiers, server: Nullifiers) {
        val sent = Proto.decode(Proto.nf(client.share())) as Proto.MNf
        server.heard(sent.claims)
        val reply = Proto.decode(Proto.nf(server.share())) as Proto.MNf
        client.heard(reply.claims)
    }

    // ---------------------------------------------------------------- T3

    @Test
    fun t3SettledNullifiersArePrunedAndSizeStaysBounded() {
        val p = Phone(maxClaims = 500, maxSettled = 1000)
        var peak = 0
        val settledTxs = ArrayList<Tx>()
        // 2,000 payments pass through; each settles a few payments later, as a phone online would see.
        val live = ArrayDeque<Tx>()
        repeat(2_000) {
            val t = tx()
            p.n.local(t.txid, t.nfs)
            live.addLast(t)
            if (live.size > 10) live.removeFirst().also { s -> p.n.settle(s.txid, s.nfs); settledTxs += s }
            peak = maxOf(peak, p.n.size())
        }
        assertEquals(20, p.n.size()) // only the 10 unsettled txs × 2 nullifiers remain
        assertTrue("peak $peak", peak <= 22)
        live.forEach { p.n.settle(it.txid, it.nfs) }
        assertEquals("every settled nullifier pruned", 0, p.n.size())
        assertTrue("tombstones capped", p.n.settledCount() <= 1000)

        // A peer that has not heard of the settlement gossips the same claims back: they stay out.
        val recent = settledTxs.takeLast(400)
        p.n.heard(recent.flatMap { t -> t.nfs.map { Nullifiers.Claim(it, t.txid) } })
        assertEquals("stale gossip of settled claims is not re-added", 0, p.n.size())

        // A flood of gossip (10,000 claims) never grows the set past its cap, and our own
        // unsettled claims are never the ones evicted.
        val own = tx()
        p.n.local(own.txid, own.nfs)
        repeat(10) { p.n.heard(List(1_000) { Nullifiers.Claim(h32(), h32()) }) }
        assertTrue("size ${p.n.size()} ≤ 500", p.n.size() <= 500)
        val shared = p.n.share()
        own.nfs.forEach { nf -> assertTrue(Nullifiers.Claim(nf, own.txid) in shared) }
        assertTrue(shared.size <= Proto.MAX_CLAIMS)
        assertEquals("honest traffic, no alerts", 0, p.alerts.size + p.n.conflicts().size)
    }

    @Test
    fun t3SettlementThroughTheRelayQueuePrunes() {
        // Wired as Mesh wires it: queue add → local claims, queue settle → prune.
        val q = RelayQueue(tmp.root.resolve("q"))
        val n = Nullifiers()
        q.onAdded = { n.local(it.txid, it.nullifiers) }
        q.onSettled = { n.settle(it.txid, it.nullifiers) }
        val txs = List(50) { tx() }
        txs.forEach { t ->
            q.add(RelayQueue.Entry(t.txid, ByteArray(100), hops = 1, from = "peer", firstSeen = 0, nullifiers = t.nfs))
        }
        assertEquals(100, n.size())
        txs.take(30).forEach { q.markSettled(it.txid, 4_500_000) }
        assertEquals(40, n.size())
        txs.take(30).forEach { q.markSettled(it.txid, 4_500_000) } // settling again is a no-op
        assertEquals(40, n.size())
        txs.drop(30).forEach { q.markSettled(it.txid, null) }
        assertEquals(0, n.size())
        assertEquals(0, n.conflicts().size)
    }

    // ---------------------------------------------------------------- conflict detection

    @Test
    fun twoTxidsSharingANullifierAlert() {
        val shared = h32()
        val a = tx(shared, h32())
        val b = tx(shared, h32())

        // Both verified on this phone (e.g. both relayed to it).
        val p = Phone()
        p.n.local(a.txid, a.nfs)
        assertTrue(p.n.conflicts().isEmpty())
        p.n.local(b.txid, b.nfs)
        val c = p.n.conflicts().single()
        assertEquals(shared, c.nullifier)
        assertEquals(listOf(a.txid, b.txid).sorted(), c.txids)
        assertTrue(c.verified)
        assertNull(c.settled)
        assertEquals(1, p.alerts.size)

        // One held, the other only gossiped: still an alert, marked as reported.
        val q = Phone()
        q.n.local(a.txid, a.nfs)
        q.n.heard(listOf(Nullifiers.Claim(shared, b.txid)))
        assertEquals(listOf(a.txid, b.txid).sorted(), q.n.conflicts().single().txids)
        assertFalse(q.n.conflicts().single().verified)

        // Neither held: two peers' gossip still disagree.
        val r = Phone()
        r.n.heard(listOf(Nullifiers.Claim(shared, a.txid)))
        r.n.heard(listOf(Nullifiers.Claim(shared, b.txid)))
        assertEquals(1, r.n.conflicts().size)

        // A third txid on the same nullifier joins the same alert.
        val c3 = tx(shared)
        p.n.heard(listOf(Nullifiers.Claim(shared, c3.txid)))
        assertEquals(3, p.n.conflicts().single().txids.size)
        assertEquals(2, p.alerts.size)
    }

    @Test
    fun conflictWithAnAlreadyMinedTxStillAlerts() {
        val shared = h32()
        val a = tx(shared, h32())
        val b = tx(shared, h32())
        val p = Phone()
        p.n.local(a.txid, a.nfs)
        p.n.settle(a.txid, a.nfs)
        p.n.heard(listOf(Nullifiers.Claim(shared, a.txid))) // stale gossip of the winner: fine
        assertTrue(p.n.conflicts().isEmpty())
        p.n.heard(listOf(Nullifiers.Claim(shared, b.txid)))
        val c = p.n.conflicts().single()
        assertEquals(a.txid, c.settled)
        assertEquals(listOf(a.txid, b.txid).sorted(), c.txids)

        // Conflict first, then one side mines: the alert records the winner.
        val q = Phone()
        q.n.local(a.txid, a.nfs)
        q.n.local(b.txid, b.nfs)
        q.n.settle(b.txid, b.nfs)
        assertEquals(b.txid, q.n.conflicts().single().settled)
        assertEquals(1, q.n.size()) // a's other nullifier is still claimed; the shared one is pruned
    }

    @Test
    fun honestTrafficNeverAlerts() {
        // 100 honest payments relayed among 5 phones that meet in every order, repeatedly.
        val phones = List(5) { Phone() }
        val txs = List(100) { tx() }
        txs.forEachIndexed { i, t -> phones[i % 5].n.local(t.txid, t.nfs) }
        repeat(3) {
            for (x in phones) for (y in phones) if (x !== y) session(x.n, y.n)
        }
        // Each phone later receives (verifies) every tx itself, as relaying does.
        phones.forEach { p -> txs.forEach { t -> p.n.local(t.txid, t.nfs) } }
        phones.forEach { p ->
            assertEquals(0, p.alerts.size)
            assertTrue(p.n.conflicts().isEmpty())
            assertEquals(200, p.n.size())
        }
    }

    @Test
    fun sameTxSeenTwiceIsNotAConflict() {
        val t = tx()
        val p = Phone()
        p.n.local(t.txid, t.nfs)
        p.n.local(t.txid, t.nfs)
        p.n.heard(t.nfs.map { Nullifiers.Claim(it, t.txid) })
        p.n.heard(t.nfs.map { Nullifiers.Claim(it, t.txid) })
        assertEquals(2, p.n.size())
        assertTrue(p.n.conflicts().isEmpty())

        // Through the relay: the same txid from three peers, plus a QR copy (extraction gives
        // different bytes but the same txid), is one claim set.
        runBlocking {
            val q = RelayQueue(tmp.root.resolve("q"))
            val n = Nullifiers()
            q.onAdded = { n.local(it.txid, it.nullifiers) }
            val relay = Relay(q, verify = { Verdict(t.txid, true, false, true, t.nfs) })
            listOf("a", "b", "c").forEach { relay.ingest(t.txid, 1, ByteArray(500) { 1 }, it) }
            relay.ingest(null, 1, ByteArray(500) { 2 }, "qr")
            assertEquals(1, q.all().size)
            assertTrue(n.conflicts().isEmpty())
        }
    }

    @Test
    fun relayedConflictingTxsAlertThroughTheQueue() = runBlocking {
        val shared = h32()
        val a = tx(shared, h32())
        val b = tx(shared, h32())
        val byRaw = mapOf(1.toByte() to a, 2.toByte() to b)
        val q = RelayQueue(tmp.root.resolve("q"))
        val p = Phone()
        q.onAdded = { p.n.local(it.txid, it.nullifiers) }
        val relay = Relay(q, verify = { raw -> byRaw.getValue(raw[0]).let { Verdict(it.txid, true, false, true, it.nfs) } })
        assertEquals(Relay.Status.STORED, relay.ingest(a.txid, 1, ByteArray(500) { 1 }, "phoneA").status)
        assertEquals(Relay.Status.STORED, relay.ingest(b.txid, 1, ByteArray(500) { 2 }, "phoneB").status)
        // Both stay in the queue so they spread (they are the evidence); one alert, verified.
        assertEquals(2, q.forwardable().size)
        assertTrue(p.n.conflicts().single().verified)
    }

    @Test
    fun threePhonesThirdMeetsBothAndEveryHolderLearns() {
        // T1 in miniature: A holds tx1, B holds tx2 (same note); C meets A, then B; then A again.
        val shared = h32()
        val t1 = tx(shared, h32())
        val t2 = tx(shared, h32())
        val a = Phone(); val b = Phone(); val c = Phone()
        a.n.local(t1.txid, t1.nfs)
        b.n.local(t2.txid, t2.nfs)
        session(a.n, c.n)
        assertTrue(c.n.conflicts().isEmpty())
        session(b.n, c.n)
        assertEquals(1, c.n.conflicts().size)
        assertEquals(1, b.n.conflicts().size) // B hears tx1 in C's reply
        assertTrue(a.n.conflicts().isEmpty())
        val v = c.n.version
        assertTrue(v > 0) // C's version moved, so its client re-syncs every peer
        session(c.n, a.n)
        assertEquals(listOf(t1.txid, t2.txid).sorted(), a.n.conflicts().single().txids)
    }

    @Test
    fun conflictsSurviveARestart() {
        val f = tmp.root.resolve("conflicts.json")
        val shared = h32()
        val a = tx(shared); val b = tx(shared)
        Phone(f).n.apply { local(a.txid, a.nfs); heard(listOf(Nullifiers.Claim(shared, b.txid))) }
        val again = Nullifiers(f)
        assertEquals(listOf(a.txid, b.txid).sorted(), again.conflicts().single().txids)
        again.clear()
        assertTrue(Nullifiers(f).conflicts().isEmpty())
    }

    @Test
    fun nfMessageRoundTripsAndRejectsGarbage() {
        val claims = List(Proto.MAX_CLAIMS) { Nullifiers.Claim(h32(), h32()) }
        assertEquals(claims, (Proto.decode(Proto.nf(claims)) as Proto.MNf).claims)
        assertEquals(emptyList<Nullifiers.Claim>(), (Proto.decode(Proto.nf(emptyList())) as Proto.MNf).claims)
        // More than the cap is cut to the cap (the message stays under Wire.MAX_MESSAGE).
        val big = Proto.nf(claims + claims)
        assertTrue(big.size <= Wire.MAX_MESSAGE)
        assertEquals(Proto.MAX_CLAIMS, (Proto.decode(big) as Proto.MNf).claims.size)
        for (bad in listOf(byteArrayOf(Proto.T_NF, 0, 1), byteArrayOf(Proto.T_NF, 0, 1) + ByteArray(63))) {
            try {
                Proto.decode(bad)
                fail("decoded ${bad.size} bytes")
            } catch (_: Exception) {}
        }
    }
}
