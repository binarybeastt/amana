package dev.offlinezec.wallet.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Advert hints (protocol v3): encoding, the 31-byte limit, rotation and the suspect rule. */
class HintsTest {
    private val rnd = Random(7)
    private fun h32() = ByteArray(32).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
    private val node = "0011223344556677"

    private fun entry(txid: String, nfs: List<String>, at: Long) =
        RelayQueue.Entry(txid, ByteArray(0), hops = 0, from = "self", firstSeen = at, nullifiers = nfs)

    @Test
    fun advertRoundTripsAndFitsLegacyScanResponse() {
        val hints = listOf(Hints.of(h32(), h32()), Hints.of(h32(), h32()))
        for (n in 0..2) {
            val b = Hints.encode(Proto.VERSION, node, hints.take(n))
            assertEquals(Hints.Advert(Proto.VERSION, node, hints.take(n)), Hints.decode(b))
            // AD header (2) + company id (2) + payload ≤ 31 bytes.
            assertTrue(2 + 2 + b.size <= 31)
        }
        assertEquals(12, hints[0].nf.length)
        assertEquals(6, hints[0].tx.length)
    }

    @Test
    fun othersAndOldAdvertsAreNotHints() {
        assertNull(Hints.decode(null))
        assertNull(Hints.decode(ByteArray(3)))
        assertNull(Hints.decode(ByteArray(9 + 5))) // not a whole hint
        assertNull(Hints.decode(ByteArray(9 + 27))) // too many
        // A v2 phone's service data still reads as a peer (no hints), and a v3 advert is not
        // v2 service data, so an old build ignores it rather than misreading it.
        assertEquals(2 to node, Proto.parseAdvert(Proto.advert(node, 2)))
        assertNull(Proto.parseAdvert(Hints.encode(Proto.VERSION, node, listOf(Hints.of(h32(), h32())))))
    }

    @Test
    fun framesPutConflictPairsFirstThenNewestPaymentsAndRotateInTwos() {
        val q = (1..5).map { entry(h32(), listOf(h32(), h32()), at = it * 1000L) }
        val nf = h32()
        val c = Nullifiers.Conflict(nf, listOf(h32(), h32()).sorted(), 0, verified = true)
        val f = Hints.frames(listOf(c), q)
        // Both sides of the conflict in one advert.
        assertEquals(listOf(Hints.of(nf, c.txids[0]), Hints.of(nf, c.txids[1])), f[0])
        // Then every nullifier of every payment, newest payment first, two per advert.
        assertEquals(1 + 5, f.size)
        assertEquals(q[4].nullifiers.map { Hints.of(it, q[4].txid) }, f[1])
        assertEquals(q[0].nullifiers.map { Hints.of(it, q[0].txid) }, f[5])
        assertTrue(f.all { it.size <= Hints.PER_ADVERT })
        // A settled conflict is not advertised; nor are more than MAX_FRAMES frames.
        assertEquals(5, Hints.frames(listOf(c.copy(settled = c.txids[0])), q).size)
        val many = (1..100).map { entry(h32(), listOf(h32(), h32()), at = it.toLong()) }
        assertEquals(Hints.MAX_FRAMES, Hints.frames(emptyList(), many).size)
        assertTrue(Hints.frames(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun sameNullifierDifferentTxidIsSuspectSameTxidIsHonest() {
        val n = Nullifiers()
        val nf = h32(); val tx1 = h32(); val tx2 = h32()
        n.local(tx1, listOf(nf, h32()))
        // Honest: the peer carries the same payment.
        assertTrue(Hints.suspects(listOf(Hints.of(nf, tx1)), n::txidsFor).isEmpty())
        // Double spend: our nullifier, another txid.
        assertEquals(listOf(Hints.of(nf, tx2)), Hints.suspects(listOf(Hints.of(nf, tx2)), n::txidsFor))
        // A nullifier we do not know: nothing to compare, nothing to chase.
        assertTrue(Hints.suspects(listOf(Hints.of(h32(), tx2)), n::txidsFor).isEmpty())
        // No alert from the hint: only gossip in the session that follows raises one.
        assertTrue(n.conflicts().isEmpty())
    }

    @Test
    fun aPeersConflictPairIsChasedUntilWeKnowIt() {
        val n = Nullifiers()
        val nf = h32(); val tx1 = h32(); val tx2 = h32()
        val pair = listOf(Hints.of(nf, tx1), Hints.of(nf, tx2))
        // We hold neither side: the advert itself shows two txids for one nullifier.
        assertEquals(pair, Hints.suspects(pair, n::txidsFor))
        // After the session's gossip we know the conflict: nothing left to chase.
        n.heard(listOf(Nullifiers.Claim(nf, tx1), Nullifiers.Claim(nf, tx2)))
        assertEquals(1, n.conflicts().size)
        assertTrue(Hints.suspects(pair, n::txidsFor).isEmpty())
    }

    @Test
    fun aPeerHoldingOneSideOfOurConflictIsChased() {
        val n = Nullifiers()
        val nf = h32(); val tx1 = h32(); val tx2 = h32()
        n.local(tx2, listOf(nf))
        n.heard(listOf(Nullifiers.Claim(nf, tx1)))
        // The peer carries tx1 only: it does not know tx2 spends the same note.
        assertEquals(listOf(Hints.of(nf, tx1)), Hints.suspects(listOf(Hints.of(nf, tx1)), n::txidsFor))
        // Once it knows, it advertises the pair: nothing to chase.
        assertTrue(Hints.suspects(listOf(Hints.of(nf, tx1), Hints.of(nf, tx2)), n::txidsFor).isEmpty())
    }

    @Test
    fun aSettledNullifierRespentIsSuspect() {
        val n = Nullifiers()
        val nf = h32(); val tx1 = h32(); val tx2 = h32()
        n.local(tx1, listOf(nf))
        n.settle(tx1, listOf(nf))
        assertTrue(Hints.suspects(listOf(Hints.of(nf, tx1)), n::txidsFor).isEmpty())
        assertEquals(1, Hints.suspects(listOf(Hints.of(nf, tx2)), n::txidsFor).size)
    }

    /** Honest traffic never looks suspect: many payments, each phone holding some, hints rotating. */
    @Test
    fun honestRotationRaisesNoSuspects() {
        val q = (1..60).map { entry(h32(), listOf(h32(), h32()), at = it.toLong()) }
        val mine = Nullifiers().apply { q.take(40).forEach { local(it.txid, it.nullifiers) } }
        val frames = Hints.frames(emptyList(), q.drop(20))
        assertTrue(frames.isNotEmpty())
        for (f in frames) assertTrue(Hints.suspects(f, mine::txidsFor).isEmpty())
    }
}
