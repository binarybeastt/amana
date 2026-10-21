package dev.offlinezec.wallet.mesh

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * P3.G3 Auto tests: de-duplication (T1), hop limit (T2), queue cap (T4), invalid tx rejected
 * before storing (T5), and that the queue survives a restart (the logic behind T3).
 *
 * The verifier here stands in for offline-core: a "valid" tx is one whose last 32 bytes are the
 * SHA-256 of the rest (the txid is that hash); anything else throws, as offline-core does for a
 * bad proof. The real verifier runs on devices (stress/p3).
 */
class RelayTest {
    @get:Rule val tmp = TemporaryFolder()

    private val verifies = AtomicInteger()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun validTx(seed: Int, size: Int = 9_166): Pair<String, ByteArray> {
        val body = ByteArray(size - 32).also(Random(seed)::nextBytes)
        val h = sha(body)
        return hex(h) to body + h
    }

    private val verifier: suspend (ByteArray) -> Verdict = { raw ->
        verifies.incrementAndGet()
        val body = raw.copyOfRange(0, raw.size - 32)
        val h = raw.copyOfRange(raw.size - 32, raw.size)
        if (!sha(body).contentEquals(h)) throw IllegalStateException("invalid proof or signature")
        Verdict(hex(h), proofsOk = true, forUs = false, anchorKnown = true, nullifiers = listOf(hex(sha(h))))
    }

    private fun queue(name: String = "q", maxEntries: Int = 200) = RelayQueue(tmp.root.resolve(name), maxEntries = maxEntries)

    @Test
    fun t1SameTxFromThreePeersIsStoredOnce() = runBlocking {
        val q = queue()
        val relay = Relay(q, verifier)
        val (txid, raw) = validTx(1)
        // One after another, as three peers meet us in turn…
        val results = listOf("peerA", "peerB", "peerC").map { relay.ingest(txid, 1, raw, it) }
        assertEquals(listOf(Relay.Status.STORED, Relay.Status.DUPLICATE, Relay.Status.DUPLICATE), results.map { it.status })
        assertEquals(1, verifies.get()) // known txids are not even verified again
        // …and all at once, three links delivering the same tx in parallel.
        val (txid2, raw2) = validTx(2)
        val parallel = List(3) { async { relay.ingest(txid2, 2, raw2, "peer$it") } }.awaitAll()
        assertEquals(1, parallel.count { it.status == Relay.Status.STORED })
        assertEquals(2, parallel.count { it.status == Relay.Status.DUPLICATE })
        assertEquals(listOf(txid, txid2), q.txids())
        assertEquals(2, tmp.root.resolve("q").listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test
    fun t2NeverForwardedPastTheHopLimit() = runBlocking {
        for (limit in 1..Proto.MAX_HOPS + 2) {
            // A chain of phones 0 → 1 → 2 → … ; each pushes its forwardable entries to the next.
            val n = limit + 4
            val queues = List(n) { queue("hop$limit-$it") }
            val relays = queues.map { Relay(it, verifier, maxHops = limit) }
            val (txid, raw) = validTx(limit)
            queues[0].add(RelayQueue.Entry(txid, raw, hops = 0, from = "self", firstSeen = 0, mine = true))
            var maxHopsSeen = 0
            repeat(3) { // several rounds: no new copies appear later
                for (i in 0 until n - 1) {
                    for (e in queues[i].forwardable(limit)) {
                        val r = relays[i + 1].ingest(e.txid, e.hops + 1, e.raw, "node$i")
                        if (r.status == Relay.Status.STORED) maxHopsSeen = maxOf(maxHopsSeen, e.hops + 1)
                    }
                }
            }
            val holders = queues.indices.filter { queues[it].contains(txid) }
            assertEquals("limit $limit", (0..limit).toList(), holders)
            assertEquals(limit, maxHopsSeen)
            queues.forEachIndexed { i, q -> q.get(txid)?.let { assertEquals(i, it.hops) } }
            // Pushed with too many hops directly: refused, not stored.
            val (t2, r2) = validTx(1000 + limit)
            assertEquals(Relay.Status.HOP_LIMIT, relays[1].ingest(t2, limit + 1, r2, "liar").status)
            assertFalse(queues[1].contains(t2))
        }
    }

    @Test
    fun t3QueueSurvivesARestart() {
        val dir = "persist"
        val q = queue(dir)
        val txs = (1..5).map { validTx(it) }
        txs.forEach { (id, raw) -> q.add(RelayQueue.Entry(id, raw, 1, "peer", firstSeen = id.hashCode().toLong())) }
        q.markSettled(txs[0].first, 4_524_000)
        // A crash mid-write leaves a temp file; it must be ignored.
        tmp.root.resolve(dir).resolve("deadbeef.json.tmp").writeText("{half")
        val again = queue(dir)
        assertEquals(txs.map { it.first }.toSet(), again.txids().toSet())
        txs.forEach { (id, raw) -> assertTrue(again.get(id)!!.raw.contentEquals(raw)) }
        assertEquals(4_524_000L, again.get(txs[0].first)!!.minedHeight)
        assertEquals(RelayQueue.State.SETTLED, again.get(txs[0].first)!!.state)
        assertEquals(4, again.unsettled().size)
    }

    @Test
    fun t4FullQueueEvictsOldestSettledFirstAndKeepsUnsettled() = runBlocking {
        var clock = 0L
        val q = RelayQueue(tmp.root.resolve("cap"), maxEntries = 10, now = { clock })
        val relay = Relay(q, verifier, now = { clock })
        val ids = ArrayList<String>()
        for (i in 0 until 10) {
            clock = i * 1000L
            val (id, raw) = validTx(100 + i)
            assertEquals(Relay.Status.STORED, relay.ingest(id, 1, raw, "p").status)
            ids += id
        }
        // Settle 0..5 in the order 3, 1, 5, 0, 4, 2.
        for ((k, i) in listOf(3, 1, 5, 0, 4, 2).withIndex()) {
            clock = 20_000L + k * 1000
            q.markSettled(ids[i], 4_520_000L + i)
        }
        val unsettled = ids.subList(6, 10).toSet()
        val evictedOrder = ArrayList<String>()
        for (i in 0 until 6) {
            val before = q.txids().toSet()
            val (id, raw) = validTx(200 + i)
            assertEquals(Relay.Status.STORED, relay.ingest(id, 1, raw, "p").status)
            evictedOrder += (before - q.txids().toSet()).single()
            assertTrue(q.txids().containsAll(unsettled))
            assertEquals(10, q.txids().size)
        }
        assertEquals(listOf(3, 1, 5, 0, 4, 2).map { ids[it] }, evictedOrder)
        // Now all 10 are unsettled: a stranger's tx is refused, nothing is evicted.
        val (id, raw) = validTx(300)
        val r = relay.ingest(id, 1, raw, "p")
        assertEquals(Relay.Status.FULL, r.status)
        assertFalse(q.contains(id))
        assertEquals(10, q.unsettled().size)
        assertTrue(q.txids().containsAll(unsettled))
        // Our own payment still gets in (it is never dropped for lack of space).
        val (own, ownRaw) = validTx(301)
        assertEquals(RelayQueue.AddResult.ADDED, q.add(RelayQueue.Entry(own, ownRaw, 0, "self", clock, mine = true), force = true))
        assertTrue(q.txids().containsAll(unsettled))
    }

    @Test
    fun t5InvalidTxIsRejectedBeforeStoringAndNeverForwarded() = runBlocking {
        val q = queue()
        val relay = Relay(q, verifier)
        val (txid, raw) = validTx(7)
        val rnd = Random(5)
        // 500 single-bit tamperings of a valid tx (a bad proof, signature or anything else).
        repeat(500) {
            val bad = raw.copyOf()
            val bit = rnd.nextInt(bad.size * 8)
            bad[bit / 8] = (bad[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            val r = relay.ingest(txid, 1, bad, "attacker")
            assertEquals(Relay.Status.REJECTED, r.status)
        }
        // A good tx under someone else's txid, and a verdict that says the proofs failed.
        val (other, otherRaw) = validTx(8)
        assertEquals(Relay.Status.REJECTED, relay.ingest(txid, 1, otherRaw, "attacker").status)
        val failing = Relay(q, { r -> verifier(r).copy(proofsOk = false) })
        assertEquals(Relay.Status.REJECTED, failing.ingest(other, 1, otherRaw, "attacker").status)
        assertEquals(Relay.Status.REJECTED, relay.ingest(txid, 1, ByteArray(0), "attacker").status)
        assertTrue(q.txids().isEmpty())
        assertTrue(q.forwardable().isEmpty())
        assertEquals(0, tmp.root.resolve("q").listFiles()!!.size)
        // The real one still goes through afterwards.
        assertEquals(Relay.Status.STORED, relay.ingest(txid, 1, raw, "honest").status)
        assertEquals(listOf(txid), q.forwardable().map { it.txid })
    }

    @Test
    fun settledAndRejectedAreNotForwarded() {
        val q = queue()
        val a = validTx(1)
        val b = validTx(2)
        val c = validTx(3)
        listOf(a, b, c).forEach { (id, raw) -> q.add(RelayQueue.Entry(id, raw, 1, "p", 0)) }
        q.markSettled(a.first, 1)
        q.update(b.first) { it.copy(state = RelayQueue.State.REJECTED) }
        assertEquals(listOf(c.first), q.forwardable().map { it.txid })
        assertNull(q.update("00".repeat(32)) { it })
    }
}
