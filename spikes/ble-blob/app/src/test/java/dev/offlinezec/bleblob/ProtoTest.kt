package dev.offlinezec.bleblob

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProtoTest {
    private val rnd = Random(42)

    private fun blob(size: Int) = ByteArray(size).also { rnd.nextBytes(it) }

    @Test
    fun roundTripAcrossSizesAndMtus() {
        for (size in listOf(0, 1, 499, 500, 501, 20 * 1024, 30 * 1024)) {
            for (mtu in listOf(Proto.MIN_MTU, 185, 247, 517)) {
                val b = blob(size)
                val r = Proto.Reassembler()
                val done = Proto.frames(7, b, Proto.maxFrame(mtu)).mapNotNull(r::accept)
                assertEquals("size=$size mtu=$mtu", 1, done.size)
                assertTrue(done[0].ok)
                assertArrayEquals(b, done[0].data)
            }
        }
    }

    @Test
    fun framesNeverExceedMaxFrame() {
        for (mtu in listOf(Proto.MIN_MTU, 185, 517)) {
            val max = Proto.maxFrame(mtu)
            assertTrue(Proto.frames(1, blob(20 * 1024), max).all { it.size <= max })
        }
    }

    @Test
    fun shuffledAndDuplicatedDataFramesStillReassemble() {
        repeat(200) {
            val b = blob(rnd.nextInt(1, 30 * 1024))
            val frames = Proto.frames(3, b, 244)
            val data = frames.drop(1)
            val noisy = (data + data.shuffled(rnd).take(data.size / 3)).shuffled(rnd)
            val r = Proto.Reassembler()
            val done = (listOf(frames[0]) + noisy).mapNotNull(r::accept)
            assertEquals(1, done.size)
            assertTrue(done[0].ok)
            assertArrayEquals(b, done[0].data)
        }
    }

    @Test
    fun corruptedChunkIsReportedNotAccepted() {
        repeat(200) {
            val b = blob(20 * 1024)
            val frames = Proto.frames(9, b, 244).toMutableList()
            val i = rnd.nextInt(1, frames.size)
            val f = frames[i].copyOf()
            val at = rnd.nextInt(Proto.HEADER, f.size)
            f[at] = (f[at].toInt() xor 0x01).toByte()
            frames[i] = f
            val done = frames.mapNotNull(Proto.Reassembler()::accept)
            assertEquals(1, done.size)
            assertFalse(done[0].ok)
        }
    }

    @Test
    fun interleavedBlobsAreKeptApart() {
        val a = blob(5000)
        val b = blob(7000)
        val fa = Proto.frames(1, a, 244)
        val fb = Proto.frames(2, b, 244)
        val r = Proto.Reassembler()
        val mixed = listOf(fa[0], fb[0]) + (fa.drop(1) + fb.drop(1)).shuffled(rnd)
        val done = mixed.mapNotNull(r::accept).associateBy { it.blobId }
        assertArrayEquals(a, done[1]!!.data)
        assertArrayEquals(b, done[2]!!.data)
    }

    @Test
    fun garbageNeverCrashes() {
        val r = Proto.Reassembler()
        repeat(100_000) {
            assertNull(r.accept(blob(rnd.nextInt(0, 64)).also { if (it.isNotEmpty()) it[0] = 2 }))
        }
    }

    @Test
    fun ackRoundTrip() {
        assertEquals(-5 to true, Proto.parseAck(Proto.ack(-5, true)))
        assertEquals(12 to false, Proto.parseAck(Proto.ack(12, false)))
        assertNull(Proto.parseAck(byteArrayOf(1, 2, 3)))
    }
}
