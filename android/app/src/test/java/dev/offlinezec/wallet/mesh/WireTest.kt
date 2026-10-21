package dev.offlinezec.wallet.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * P3.G1.T1: the chunking harness. Frames are dropped, duplicated, reordered, interleaved and
 * corrupted; a message must either reassemble to exactly the bytes sent or fail cleanly, and
 * corrupt data must never be accepted.
 */
class WireTest {
    private val rnd = Random(20261021)
    private var clock = 0L
    private fun reassembler() = Wire.Reassembler { clock }

    private fun message(size: Int = rnd.nextInt(1, 30 * 1024 + 1)) = ByteArray(size).also { rnd.nextBytes(it) }

    /** Frame sizes from the ATT minimum we accept up to the 512-byte cap. */
    private fun frameSize() = rnd.nextInt(Wire.MIN_FRAME, 513)

    private fun feed(r: Wire.Reassembler, frames: List<ByteArray>): List<Wire.Event> = frames.mapNotNull { r.accept(it) }

    @Test
    fun inOrderAndShuffled() {
        repeat(2_000) { i ->
            val m = message()
            val frames = Wire.frames(i, m, frameSize()).let { if (i % 2 == 0) it else it.shuffled(rnd) }
            val ev = feed(reassembler(), frames)
            assertEquals(1, ev.size)
            val done = ev.single() as? Wire.Event.Done ?: fail("message $i failed: ${(ev.single() as Wire.Event.Failed).reason}") as Nothing
            assertArrayEquals(m, done.data)
        }
    }

    @Test
    fun duplicatesAndStartLast() {
        repeat(2_000) { i ->
            val m = message()
            val frames = Wire.frames(i, m, frameSize())
            // Every frame 1–3 times, any order, START always last.
            val noisy = frames.drop(1).flatMap { f -> List(rnd.nextInt(1, 4)) { f } }.shuffled(rnd) + frames[0] + frames[0]
            val r = reassembler()
            val ev = feed(r, noisy)
            assertEquals("exactly one completion for message $i", 1, ev.size)
            assertArrayEquals(m, (ev.single() as Wire.Event.Done).data)
            assertEquals(1, r.pending) // the late duplicate START opens a partial that never completes (expires)
        }
    }

    @Test
    fun dropsNeverCompleteAndExpireCleanly() {
        repeat(2_000) { i ->
            val m = message()
            val frames = Wire.frames(i, m, frameSize())
            val keep = frames.filterIndexed { k, _ -> k == 0 && rnd.nextBoolean() || k > 0 && rnd.nextInt(10) != 0 }
            if (keep.size == frames.size) return@repeat
            val r = reassembler()
            val ev = feed(r, keep.shuffled(rnd))
            assertTrue("incomplete message $i must not complete", ev.none { it is Wire.Event.Done })
            clock += Wire.STALE_MS + 1
            val expired = r.expire()
            assertEquals(listOf(i), expired.map { it.msgId })
            assertEquals(0, r.pending)
        }
    }

    @Test
    fun interleavedMessagesOnOneLink() {
        repeat(500) {
            val msgs = List(3) { message() }
            val frames = msgs.mapIndexed { k, m -> Wire.frames(k, m, frameSize()) }.flatten().shuffled(rnd)
            val done = feed(reassembler(), frames).map { it as Wire.Event.Done }
            assertEquals(3, done.size)
            done.forEach { assertArrayEquals(msgs[it.msgId], it.data) }
        }
    }

    @Test
    fun corruptionIsNeverAccepted() {
        var failed = 0
        repeat(5_000) { i ->
            val m = message()
            val frames = Wire.frames(i, m, frameSize()).map { it.copyOf() }.toMutableList()
            // Flip one bit anywhere in one frame: payload, length, hash, or header.
            val k = rnd.nextInt(frames.size)
            val bit = rnd.nextInt(frames[k].size * 8)
            frames[k][bit / 8] = (frames[k][bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            val r = reassembler()
            for (e in feed(r, frames.shuffled(rnd))) {
                when (e) {
                    is Wire.Event.Done -> {
                        // Only possible if the flip hit a header field of a frame that then
                        // became a separate, never-completing message: the data must be exact.
                        assertEquals(i, e.msgId)
                        assertArrayEquals("corrupt data accepted for message $i", m, e.data)
                    }
                    is Wire.Event.Failed -> failed++
                }
            }
            clock += Wire.STALE_MS + 1
            failed += r.expire().size
        }
        assertTrue("most corruptions must surface as failures (got $failed)", failed > 4_000)
    }

    @Test
    fun garbageFramesNeverDeliver() {
        val r = reassembler()
        repeat(200_000) {
            val f = ByteArray(rnd.nextInt(0, 60)).also { rnd.nextBytes(it) }
            if (rnd.nextBoolean() && f.isNotEmpty()) f[0] = rnd.nextInt(1, 3).toByte()
            val e = r.accept(f)
            assertTrue("garbage produced a message", e !is Wire.Event.Done)
        }
    }

    @Test
    fun oversizeAndInconsistentFramesFail() {
        val m = message(4_000)
        val frames = Wire.frames(7, m, 100)
        // A DATA frame claiming a different chunk count.
        val bad = frames[2].copyOf().also { it[7] = (it[7] + 1).toByte() }
        val r = reassembler()
        r.accept(frames[0])
        r.accept(frames[1])
        val e = r.accept(bad)
        assertTrue(e is Wire.Event.Failed)
        // Too large to send at all.
        try {
            Wire.frames(1, ByteArray(Wire.MAX_MESSAGE + 1), 512)
            fail("oversize message accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun emptyMessage() {
        val ev = feed(reassembler(), Wire.frames(3, ByteArray(0), 64))
        assertEquals(0, (ev.single() as Wire.Event.Done).data.size)
    }
}
