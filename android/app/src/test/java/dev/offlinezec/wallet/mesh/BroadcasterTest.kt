package dev.offlinezec.wallet.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P3.G4.T2 (lightwalletd unreachable, then back: back off, retry, succeed) and P3.G4.T3 (a tx
 * already mined counts as success), against a scripted node on virtual time. The error texts
 * are lightwalletd's own, recorded in Phase 0 (spikes/runs/…/send*.txt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcasterTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        const val ALREADY_IN_STATE = "(-25) failed to validate tx: WtxId(\"private\"), error: transaction is already in state"
        const val ALREADY_IN_MEMPOOL = "(-1) transaction already exists in mempool"
        const val COMMITTED = "(-1) any transaction with the same effects will be rejected from the mempool until a chain reset: transaction was committed to the best chain"
        const val MEMPOOL_DOUBLE_SPEND = "(-25) failed to validate tx: WtxId(\"private\"), error: transaction did not pass consensus validation: another transaction in the mempool has already spent some of its inputs"
        const val CHAIN_DOUBLE_SPEND = "(-25) failed to validate tx: WtxId(\"private\"), error: transaction did not pass consensus validation: could not contextually validate transaction on best chain: ironwood double-spend: duplicate nullifier"
    }

    /** A node that is down until [upAt] (virtual ms), then accepts everything. */
    private class FakeNode(val clock: () -> Long, var upAt: Long = 0, val minedAt: MutableMap<String, Long> = HashMap()) : ChainNode {
        val submits = ArrayList<Pair<Long, String>>()
        val failedAt = ArrayList<Long>()
        val sent = HashSet<String>()
        var script: ((String) -> ChainNode.Submit)? = null

        override suspend fun submit(txid: String, raw: ByteArray): ChainNode.Submit {
            submits += clock() to txid
            if (clock() < upAt) {
                failedAt += clock()
                return ChainNode.Submit.Retry("14 UNAVAILABLE: Unable to resolve host testnet.zec.rocks")
            }
            script?.let { return it(txid) }
            sent += txid
            return ChainNode.Submit.Sent
        }

        override suspend fun mined(txid: String): ChainNode.Mined {
            if (clock() < upAt) return ChainNode.Mined.Retry("unavailable")
            minedAt[txid]?.let { return ChainNode.Mined.Height(it) }
            return if (txid in sent) ChainNode.Mined.Mempool else ChainNode.Mined.NotFound
        }
    }

    private fun entry(i: Int) = RelayQueue.Entry("%064x".format(i), ByteArray(100) { i.toByte() }, 1, "peer", i.toLong())

    @Test
    fun t2UnreachableBacksOffThenSucceeds() = runTest {
        val q = RelayQueue(tmp.root.resolve("q"), now = { currentTime })
        repeat(5) { q.add(entry(it)) }
        val node = FakeNode({ currentTime }, upAt = 100_000)
        val b = Broadcaster(q, { node }, now = { currentTime })
        var online = true
        val job = launch { b.run { online } }
        advanceTimeBy(99_999)
        runCurrent()
        // Retries while down, each wait double the last (2, 4, 8, 16, 32 s), capped at 60 s.
        val gaps = node.failedAt.zipWithNext { a, c -> c - a }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L), gaps.take(5))
        assertTrue(gaps.all { it <= b.maxBackoffMs })
        assertTrue("nothing sent while down", node.sent.isEmpty())
        // A pass stops at the first unreachable send, so only the first entry records attempts.
        assertTrue(q.unsettled().all { it.state == RelayQueue.State.QUEUED })
        assertEquals(node.failedAt.size, q.get(entry(0).txid)!!.attempts)
        assertTrue(q.get(entry(0).txid)!!.lastResult!!.startsWith("retry"))
        // Back up: the next retry (at most 60 s later) sends all five.
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(5, node.sent.size)
        assertTrue(q.all().all { it.state == RelayQueue.State.SENT })
        // Mined: the following poll marks them settled.
        q.all().forEach { node.minedAt[it.txid] = 4_524_100 }
        advanceTimeBy(b.pollMs + 1)
        runCurrent()
        assertTrue(q.all().all { it.state == RelayQueue.State.SETTLED && it.minedHeight == 4_524_100L })
        online = false
        b.wake()
        runCurrent()
        job.join()
    }

    @Test
    fun t3AlreadyMinedIsSuccess() = runTest {
        val q = RelayQueue(tmp.root.resolve("q"), now = { currentTime })
        q.add(entry(1))
        q.add(entry(2))
        val node = FakeNode({ currentTime })
        node.minedAt[entry(1).txid] = 4_517_084
        node.script = { txid -> if (txid == entry(1).txid) ChainNode.classify(ALREADY_IN_STATE) else ChainNode.classify(ALREADY_IN_MEMPOOL) }
        val p = Broadcaster(q, { node }, now = { currentTime }).pass()
        assertEquals(null, p.unreachable)
        val mined = q.get(entry(1).txid)!!
        assertEquals(RelayQueue.State.SETTLED, mined.state)
        assertEquals(4_517_084L, mined.minedHeight)
        // Already in the mempool: success too, now waiting to be mined.
        assertEquals(RelayQueue.State.SENT, q.get(entry(2).txid)!!.state)
    }

    @Test
    fun classifiesLightwalletdTexts() {
        assertEquals(ChainNode.Submit.AlreadyKnown(ALREADY_IN_STATE, mined = true), ChainNode.classify(ALREADY_IN_STATE))
        assertEquals(ChainNode.Submit.AlreadyKnown(ALREADY_IN_MEMPOOL, mined = false), ChainNode.classify(ALREADY_IN_MEMPOOL))
        assertEquals(ChainNode.Submit.AlreadyKnown(COMMITTED, mined = true), ChainNode.classify(COMMITTED))
        // "already spent some of its inputs" is a double spend, not a duplicate.
        assertTrue(ChainNode.classify(MEMPOOL_DOUBLE_SPEND) is ChainNode.Submit.Rejected)
        assertTrue(ChainNode.classify(CHAIN_DOUBLE_SPEND) is ChainNode.Submit.Rejected)
        assertTrue(ChainNode.classify("14 UNAVAILABLE: io exception") is ChainNode.Submit.Retry)
    }

    @Test
    fun droppedFromMempoolIsSentAgain() = runTest {
        val q = RelayQueue(tmp.root.resolve("q"), now = { currentTime })
        q.add(entry(1))
        val node = FakeNode({ currentTime })
        val b = Broadcaster(q, { node }, now = { currentTime })
        b.pass()
        node.sent.clear() // the node forgot it
        b.pass()
        assertEquals(2, node.submits.size)
        assertEquals(RelayQueue.State.SENT, q.get(entry(1).txid)!!.state)
    }
}
