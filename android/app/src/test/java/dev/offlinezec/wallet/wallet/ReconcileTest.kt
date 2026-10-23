package dev.offlinezec.wallet.wallet

import dev.offlinezec.wallet.incomingUnsettled
import dev.offlinezec.wallet.mesh.Broadcaster
import dev.offlinezec.wallet.mesh.ChainNode
import dev.offlinezec.wallet.mesh.Nullifiers
import dev.offlinezec.wallet.mesh.RelayQueue
import dev.offlinezec.wallet.receivedLabel
import dev.offlinezec.wallet.receivedResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P4.G4.T1: reconciliation on reconnect. The 9-case matrix {own tx mined, conflicting tx mined,
 * nothing mined} × {sender, receiver, relay}, each fed the chain facts that role really gets:
 *  - sender: the wallet DB (its tx mined; or its note spent by a different mined tx);
 *  - receiver: the wallet DB (tx mined) or the nullifier set (a different tx with a shared
 *    nullifier seen mined), and a double-spend rejection;
 *  - relay: lightwalletd through the Broadcaster (mined; or refused as a double spend).
 * Each must end `spent`/`settled`, `conflicted` or still pending, on the real RelayQueue,
 * Nullifiers and Broadcaster, with Reconcile.apply doing what AppController does every 3 s.
 */
class ReconcileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tx1 = "a1".repeat(32) // the payment in question
    private val tx2 = "b2".repeat(32) // the double spend of the same note
    private val nf = "c3".repeat(32)  // the nullifier they share
    private val other = "d4".repeat(32)

    /** Queue + nullifier set wired as Mesh wires them. */
    private inner class Phone(name: String) {
        val nullifiers = Nullifiers()
        val queue = RelayQueue(tmp.root.resolve(name)).also { q ->
            q.onAdded = { nullifiers.local(it.txid, it.nullifiers) }
            q.onSettled = { nullifiers.settle(it.txid, it.nullifiers) }
        }
        var walletMined = HashMap<String, Long>()
        var minedSpends = HashMap<String, String>()

        fun hold(txid: String, mine: Boolean = false, forUs: Boolean = false, nfs: List<String> = listOf(nf, other)) =
            queue.add(RelayQueue.Entry(txid, ByteArray(64) { 7 }, if (mine) 0 else 1, "test", 0, mine = mine, forUs = forUs, nullifiers = nfs), force = true)

        fun reconcile() = runBlocking { Reconcile.apply(queue, nullifiers.conflicts(), minedSpends) { walletMined[it.txid] } }

        fun state(txid: String = tx1) = queue.get(txid)!!.state
    }

    /** lightwalletd as the relay meets it. */
    private class Node(val mined: Map<String, Long> = emptyMap(), val refuse: Map<String, String> = emptyMap()) : ChainNode {
        override suspend fun submit(txid: String, raw: ByteArray): ChainNode.Submit =
            refuse[txid]?.let(ChainNode::classify) ?: if (txid in mined) ChainNode.classify(BroadcasterTestTexts.ALREADY_IN_STATE) else ChainNode.Submit.Sent
        override suspend fun mined(txid: String): ChainNode.Mined = mined[txid]?.let { ChainNode.Mined.Height(it) } ?: ChainNode.Mined.Mempool
    }

    private object BroadcasterTestTexts {
        const val ALREADY_IN_STATE = "(-25) failed to validate tx: WtxId(\"private\"), error: transaction is already in state"
        const val MEMPOOL_DOUBLE_SPEND = "(-25) failed to validate tx: WtxId(\"private\"), error: transaction did not pass consensus validation: another transaction in the mempool has already spent some of its inputs"
        const val CHAIN_DOUBLE_SPEND = "(-25) failed to validate tx: WtxId(\"private\"), error: transaction did not pass consensus validation: could not contextually validate transaction on best chain: ironwood double-spend: duplicate nullifier"
    }

    private fun received(minedHeight: Int? = null, conflictedBy: String? = null, amount: Long = 100_000) =
        Received(tx1, amount, null, 0, ByteArray(1), minedHeight, conflictedBy)

    // ---- Sender ----

    @Test
    fun senderOwnTxMined() {
        val p = Phone("s1")
        p.hold(tx1, mine = true)
        p.walletMined[tx1] = 4_530_500
        p.minedSpends[nf] = tx1 // the wallet: our note spent by our own tx
        p.reconcile()
        assertEquals(RelayQueue.State.SETTLED, p.state())
        assertEquals(4_530_500L, p.queue.get(tx1)!!.minedHeight)
        val r = Reconcile.reconcile(Reconcile.Role.SENDER, Reconcile.facts(tx1, listOf(nf), 4_530_500, p.minedSpends))
        assertEquals(Reconcile.Outcome.SETTLED, r.outcome)
        assertEquals("spent", r.noteStatus)
        assertFalse(r.rebroadcast)
    }

    @Test
    fun senderConflictingTxMined() {
        val p = Phone("s2")
        p.hold(tx1, mine = true)
        p.minedSpends[nf] = tx2 // the wallet: our note spent by a different mined tx (seed on two phones)
        val changed = p.reconcile()
        assertEquals(RelayQueue.State.CONFLICTED, p.state())
        assertEquals(listOf(tx1 to Reconcile.Outcome.CONFLICTED), changed.map { it.first.txid to it.second })
        assertTrue(p.queue.get(tx1)!!.lastResult!!.contains(tx2))
        assertTrue("no longer carried", p.queue.forwardable().isEmpty())
        val r = Reconcile.reconcile(Reconcile.Role.SENDER, Reconcile.facts(tx1, listOf(nf), null, p.minedSpends))
        assertEquals(Reconcile.Outcome.CONFLICTED, r.outcome)
        assertEquals("spent", r.noteStatus) // follows the wallet: spent, by the other tx
        assertFalse(r.rebroadcast)
        assertTrue(r.label.startsWith("conflicted"))
        // Reconciling again changes nothing.
        assertTrue(p.reconcile().isEmpty())
    }

    @Test
    fun senderNothingMined() {
        val p = Phone("s3")
        p.hold(tx1, mine = true)
        assertTrue(p.reconcile().isEmpty())
        assertEquals(RelayQueue.State.QUEUED, p.state())
        assertEquals(listOf(tx1), p.queue.forwardable().map { it.txid })
        val r = Reconcile.reconcile(Reconcile.Role.SENDER, Reconcile.facts(tx1, listOf(nf), null))
        assertEquals(Reconcile.Outcome.PENDING, r.outcome)
        assertEquals("spent-offline", r.noteStatus)
        assertTrue("rebroadcast", r.rebroadcast)
    }

    // ---- Receiver ----

    @Test
    fun receiverOwnTxMined() {
        val p = Phone("r1")
        p.hold(tx1, forUs = true)
        p.walletMined[tx1] = 4_530_501
        p.reconcile()
        assertEquals(RelayQueue.State.SETTLED, p.state())
        val r = received(minedHeight = 4_530_501)
        assertEquals("settled", receivedLabel(r))
        assertEquals(0L, incomingUnsettled(listOf(r))) // in the wallet balance now, not "incoming"
    }

    @Test
    fun receiverConflictingTxMined() {
        // The receiver also holds tx2 (from the mesh); lightwalletd reports tx2 mined.
        val p = Phone("r2")
        p.hold(tx1, forUs = true)
        p.hold(tx2, nfs = listOf(nf))
        assertEquals(1, p.nullifiers.conflicts().size) // P4.G2's alert, before anything is mined
        p.queue.markSettled(tx2, 4_530_502)
        assertEquals(tx2, p.nullifiers.conflicts().single().settled)
        p.reconcile()
        assertEquals(RelayQueue.State.CONFLICTED, p.state())
        assertEquals(RelayQueue.State.SETTLED, p.state(tx2))
        // What AppController files on the received payment: labelled, and in no balance.
        val r = received(conflictedBy = p.queue.get(tx1)!!.lastResult!!.removePrefix("conflicted: "))
        assertTrue(r.conflicted)
        assertTrue(receivedLabel(r), receivedLabel(r).startsWith("conflicted") && "not counted" in receivedLabel(r))
        assertFalse(receivedResult(r).countsAsIncoming)
        assertEquals(50_000L, incomingUnsettled(listOf(r, received(amount = 50_000).copy(txid = other))))
    }

    @Test
    fun receiverConflictByGossipThenRejection() {
        // Only gossip says tx2 exists (not mined, no settled): still pending. The network then
        // refuses tx1 as a double spend (tx2 got there first): conflicted.
        val p = Phone("r2b")
        p.hold(tx1, forUs = true)
        p.nullifiers.heard(listOf(Nullifiers.Claim(nf, tx2)))
        assertTrue(p.reconcile().isEmpty())
        assertEquals(RelayQueue.State.QUEUED, p.state())
        p.queue.update(tx1) { it.copy(lastResult = "failed: -25 ${BroadcasterTestTexts.MEMPOOL_DOUBLE_SPEND}") }
        p.reconcile()
        assertEquals(RelayQueue.State.CONFLICTED, p.state())
    }

    @Test
    fun receiverNothingMined() {
        val p = Phone("r3")
        p.hold(tx1, forUs = true)
        assertTrue(p.reconcile().isEmpty())
        assertEquals(RelayQueue.State.QUEUED, p.state())
        val r = received()
        assertEquals("received, not yet settled", receivedLabel(r))
        assertEquals(100_000L, incomingUnsettled(listOf(r)))
        assertTrue(receivedResult(r).rebroadcast)
    }

    // ---- Relay ----

    @Test
    fun relayOwnTxMined() = runBlocking {
        val p = Phone("l1")
        p.hold(tx1)
        val pass = Broadcaster(p.queue, { Node(mined = mapOf(tx1 to 4_530_503L)) }).pass()
        assertEquals(1, pass.settled)
        p.reconcile()
        assertEquals(RelayQueue.State.SETTLED, p.state())
        assertEquals(4_530_503L, p.queue.get(tx1)!!.minedHeight)
    }

    @Test
    fun relayConflictingTxMined() = runBlocking {
        val p = Phone("l2")
        p.hold(tx1)
        // tx2 is in a block: lightwalletd refuses tx1 with "duplicate nullifier".
        val pass = Broadcaster(p.queue, { Node(mined = mapOf(tx2 to 4_530_504L), refuse = mapOf(tx1 to BroadcasterTestTexts.CHAIN_DOUBLE_SPEND)) }).pass()
        assertEquals(1, pass.rejected)
        assertEquals(RelayQueue.State.CONFLICTED, p.state())
        assertTrue(p.reconcile().isEmpty()) // stays conflicted
        assertEquals(RelayQueue.State.CONFLICTED, p.state())
        assertTrue(p.queue.forwardable().isEmpty())
        assertEquals("conflicted: double spend, will never settle",
            Reconcile.reconcile(Reconcile.Role.RELAY, Reconcile.facts(tx1, listOf(nf), null, lastResult = p.queue.get(tx1)!!.lastResult)).label)
    }

    @Test
    fun relayConflictingTxMinedBothHeld() = runBlocking {
        // The relay carries both; tx2 settles first (mined), tx1 is then known lost without a send.
        val p = Phone("l2b")
        p.hold(tx1)
        p.hold(tx2, nfs = listOf(nf))
        p.queue.markSettled(tx2, 4_530_505)
        p.reconcile()
        assertEquals(RelayQueue.State.CONFLICTED, p.state())
    }

    @Test
    fun relayNothingMined() = runBlocking {
        val p = Phone("l3")
        p.hold(tx1)
        Broadcaster(p.queue, { Node() }).pass()
        p.reconcile()
        assertEquals(RelayQueue.State.SENT, p.state()) // in the mempool: still carried and watched
        assertTrue(p.queue.get(tx1)!!.unsettled)
        assertEquals(Reconcile.Outcome.PENDING, Reconcile.outcome(Reconcile.facts(tx1, listOf(nf), null)))
    }

    // ---- Edges ----

    @Test
    fun minedAlwaysWins() {
        // Wrongly thought conflicted (a mempool rejection), then the wallet sees our own tx mined.
        val p = Phone("e1")
        p.hold(tx1, mine = true)
        p.queue.markConflicted(tx1, "rejected as a double spend")
        p.walletMined[tx1] = 4_530_506
        p.reconcile()
        assertEquals(RelayQueue.State.SETTLED, p.state())
        assertEquals("settled", receivedLabel(received(minedHeight = 4_530_506, conflictedBy = "x")))
        // The relay path: a node refusing our tx as a double spend of itself once it is mined.
        runBlocking {
            val q = Phone("e2")
            q.hold(tx1)
            Broadcaster(q.queue, { Node(mined = mapOf(tx1 to 4_530_507L), refuse = mapOf(tx1 to BroadcasterTestTexts.CHAIN_DOUBLE_SPEND)) }).pass()
            assertEquals(RelayQueue.State.SETTLED, q.state())
        }
    }

    @Test
    fun otherRejectionsAreNotConflicts() = runBlocking {
        val p = Phone("e3")
        p.hold(tx1)
        Broadcaster(p.queue, { Node(refuse = mapOf(tx1 to "(-25) bad-txns-invalid-binding-signature")) }).pass()
        assertEquals(RelayQueue.State.REJECTED, p.state())
        assertFalse(ChainNode.isDoubleSpend("transaction is already in state"))
        assertTrue(ChainNode.isDoubleSpend(BroadcasterTestTexts.MEMPOOL_DOUBLE_SPEND))
        assertTrue(ChainNode.isDoubleSpend(BroadcasterTestTexts.CHAIN_DOUBLE_SPEND))
    }
}
