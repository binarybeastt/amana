package dev.offlinezec.wallet.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** P3.G2.T3 (handshake refusals) and round trips of every mesh message. */
class ProtoTest {
    private val ours = Proto.Hello(Proto.APP_ID, Proto.VERSION, "test", "0011223344556677")

    @Test
    fun differentProtocolVersionIsRefusedWithAClearReason() {
        for (v in listOf(0, Proto.VERSION + 1, 255)) {
            val theirs = ours.copy(version = v, node = "8899aabbccddeeff")
            val why = Proto.refusal(ours, theirs)
            assertEquals(
                "protocol version mismatch: peer speaks v$v, this phone v${Proto.VERSION}; update the older app",
                why,
            )
            // The reason travels to the peer in the HELLO reply and is shown there too.
            val reply = Proto.decode(Proto.HelloReply(false, why, ours).encode()) as Proto.MHelloReply
            assertFalse(reply.reply.ok)
            assertEquals(why, reply.reply.reason)
            // And the peer, checking our reply's hello against its own, refuses for the same reason.
            assertTrue(Proto.refusal(theirs, reply.reply.hello!!)!!.startsWith("protocol version mismatch"))
        }
    }

    @Test
    fun otherAppsNetworksAndSelfAreRefused() {
        assertEquals("not this app (peer is \"bitchat\")", Proto.refusal(ours, ours.copy(app = "bitchat", node = "ff")))
        assertEquals("network mismatch: peer is on main, this phone on test", Proto.refusal(ours, ours.copy(network = "main", node = "ff")))
        assertEquals("peer has this phone's own node id", Proto.refusal(ours, ours))
        assertNull(Proto.refusal(ours, ours.copy(node = "8899aabbccddeeff")))
    }

    @Test
    fun advertCarriesVersionAndNode() {
        val a = Proto.advert("0011223344556677")
        assertEquals(9, a.size)
        assertEquals(Proto.VERSION to "0011223344556677", Proto.parseAdvert(a))
        assertEquals(7 to "0011223344556677", Proto.parseAdvert(Proto.advert("0011223344556677", 7)))
        // Other devices' service data is not ours.
        assertNull(Proto.parseAdvert(null))
        assertNull(Proto.parseAdvert(ByteArray(3)))
        assertNull(Proto.parseAdvert(ByteArray(20)))
        // 31-byte scan response limit: AD header (2) + 128-bit UUID (16) + payload.
        assertTrue(2 + 16 + a.size <= 31)
    }

    @Test
    fun messagesRoundTrip() {
        val h = Proto.decode(ours.encode()) as Proto.MHello
        assertEquals(ours, h.hello)
        assertEquals(Proto.MInvReq, Proto.decode(Proto.invReq()))
        val txids = List(150) { ByteArray(32).also(Random(it)::nextBytes).joinToString("") { b -> "%02x".format(b) } }
        assertEquals(txids, (Proto.decode(Proto.inv(txids)) as Proto.MInv).txids)
        assertEquals(emptyList<String>(), (Proto.decode(Proto.inv(emptyList())) as Proto.MInv).txids)
        val raw = ByteArray(9_166).also(Random(1)::nextBytes)
        val tx = (Proto.decode(Proto.Tx(3, txids[0], raw).encode()) as Proto.MTx).tx
        assertEquals(3, tx.hops)
        assertEquals(txids[0], tx.txid)
        assertArrayEquals(raw, tx.raw)
        val r = (Proto.decode(Proto.TxResult(txids[1], "rejected", "proofs or signatures do not verify").encode()) as Proto.MTxResult).result
        assertEquals(Proto.TxResult(txids[1], "rejected", "proofs or signatures do not verify"), r)
        assertEquals("corrupt", (Proto.decode(Proto.nack("corrupt")) as Proto.MNack).reason)
    }

    @Test
    fun malformedMessagesThrow() {
        val bad = listOf(byteArrayOf(), byteArrayOf(99), byteArrayOf(Proto.T_INV, 0, 5), byteArrayOf(Proto.T_TX, 1, 2), byteArrayOf(Proto.T_HELLO, '{'.code.toByte()))
        for (b in bad) {
            try {
                Proto.decode(b)
                throw AssertionError("decoded malformed ${b.toList()}")
            } catch (_: Exception) {
            }
        }
    }
}
