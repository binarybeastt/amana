package dev.offlinezec.wallet.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Chunking of one message (a transaction, a handshake) into GATT-sized frames, and back.
 *
 * Every frame starts with a 9-byte header: type (1), message id (4), seq (2), total chunks (2).
 * START (seq 0) carries the message length (4) and its SHA-256 (32); DATA frames (seq 1..total)
 * carry the chunks. A message is accepted only when every chunk is in and its length and hash
 * match the START frame, so dropped, duplicated, reordered or corrupted frames can delay or fail
 * a message but never deliver wrong bytes.
 */
object Wire {
    const val HEADER = 9
    const val START_BODY = 36
    /** Largest message we send or accept (a large shielded tx is ~60 KB). */
    const val MAX_MESSAGE = 128 * 1024
    /** Smallest frame that holds a START frame. */
    const val MIN_FRAME = HEADER + START_BODY
    /** Partial messages older than this are dropped. */
    const val STALE_MS = 60_000L
    /** Messages being reassembled at once per link; more is a broken or hostile peer. */
    const val MAX_PARTIAL = 4

    private const val T_START: Byte = 1
    private const val T_DATA: Byte = 2

    /** Largest frame for a negotiated ATT MTU (3 bytes of ATT overhead, 512-byte value cap). */
    fun maxFrame(mtu: Int): Int = minOf(mtu - 3, 512)

    fun frames(msgId: Int, message: ByteArray, maxFrame: Int): List<ByteArray> {
        require(maxFrame >= MIN_FRAME) { "frame size $maxFrame too small" }
        require(message.size <= MAX_MESSAGE) { "message of ${message.size} bytes is too large" }
        val chunk = maxFrame - HEADER
        val total = (message.size + chunk - 1) / chunk
        val out = ArrayList<ByteArray>(total + 1)
        out += ByteBuffer.allocate(HEADER + START_BODY)
            .put(T_START).putInt(msgId).putShort(0).putShort(total.toShort())
            .putInt(message.size).put(sha256(message))
            .array()
        for (i in 0 until total) {
            val from = i * chunk
            val len = minOf(chunk, message.size - from)
            out += ByteBuffer.allocate(HEADER + len)
                .put(T_DATA).putInt(msgId).putShort((i + 1).toShort()).putShort(total.toShort())
                .put(message, from, len)
                .array()
        }
        return out
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    sealed interface Event {
        /** A whole message whose length and hash match its START frame. */
        class Done(val msgId: Int, val data: ByteArray) : Event
        /** A message that can never complete correctly; the sender should resend it. */
        class Failed(val msgId: Int, val reason: String) : Event
    }

    /**
     * Rebuilds messages from one peer's frames. Tolerates duplicates and any order, including
     * DATA before START. Ignores frames it cannot parse. Not thread-safe: one per link.
     */
    class Reassembler(private val now: () -> Long = System::currentTimeMillis) {
        private class Partial(val total: Int, val startedAt: Long) {
            var length = -1
            var sha: ByteArray? = null
            val chunks = arrayOfNulls<ByteArray>(total)
            var received = 0
            var bytes = 0
        }

        private val partial = LinkedHashMap<Int, Partial>()

        /** Feeds one frame. Returns an event once a message completes or fails. */
        fun accept(frame: ByteArray): Event? {
            if (frame.size < HEADER) return null
            val buf = ByteBuffer.wrap(frame)
            val type = buf.get()
            val id = buf.int
            val seq = buf.short.toInt() and 0xFFFF
            val total = buf.short.toInt() and 0xFFFF
            if (type != T_START && type != T_DATA) return null
            val p = partial[id] ?: run {
                if (partial.size >= MAX_PARTIAL) partial.remove(partial.keys.first())
                Partial(total, now()).also { partial[id] = it }
            }
            if (p.total != total) return fail(id, "frames disagree on chunk count")
            when (type) {
                T_START -> {
                    if (seq != 0 || frame.size != HEADER + START_BODY) return fail(id, "bad START frame")
                    val length = buf.int
                    val sha = ByteArray(32).also { buf.get(it) }
                    if (length < 0 || length > MAX_MESSAGE) return fail(id, "length $length out of range")
                    if (p.sha != null && (p.length != length || !p.sha.contentEquals(sha))) {
                        return fail(id, "two different START frames")
                    }
                    p.length = length
                    p.sha = sha
                }
                else -> {
                    if (seq < 1 || seq > total) return fail(id, "chunk $seq of $total")
                    if (p.chunks[seq - 1] == null) {
                        val c = frame.copyOfRange(HEADER, frame.size)
                        p.bytes += c.size
                        if (p.bytes > MAX_MESSAGE) return fail(id, "more than $MAX_MESSAGE bytes")
                        p.chunks[seq - 1] = c
                        p.received++
                    }
                }
            }
            return if (p.sha != null && p.received == p.total) finish(id, p) else null
        }

        /** Drops messages that stopped arriving; returns them as failures. */
        fun expire(): List<Event.Failed> {
            val cutoff = now() - STALE_MS
            val stale = partial.filterValues { it.startedAt < cutoff }.keys.toList()
            return stale.map { fail(it, "incomplete after ${STALE_MS / 1000} s") }
        }

        val pending: Int get() = partial.size

        private fun fail(id: Int, reason: String): Event.Failed {
            partial.remove(id)
            return Event.Failed(id, reason)
        }

        private fun finish(id: Int, p: Partial): Event {
            partial.remove(id)
            if (p.bytes != p.length) return Event.Failed(id, "length ${p.bytes}, expected ${p.length}")
            val data = ByteArray(p.bytes)
            var at = 0
            for (c in p.chunks) {
                c!!.copyInto(data, at)
                at += c.size
            }
            if (!sha256(data).contentEquals(p.sha)) return Event.Failed(id, "checksum mismatch")
            return Event.Done(id, data)
        }
    }
}
