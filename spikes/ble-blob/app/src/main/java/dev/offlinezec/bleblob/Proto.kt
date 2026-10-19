package dev.offlinezec.bleblob

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

const val TAG = "BLEBLOB"

/**
 * Wire format for sending one blob over a GATT characteristic.
 *
 * Every frame starts with a 9-byte header: type (1), blob id (4), seq (2), total chunks (2).
 * START (seq 0) carries the blob length (4) and its SHA-256 (32); DATA frames (seq 1..total)
 * carry the chunks. The receiver acks with type (1), blob id (4), ok (1).
 */
object Proto {
    val SERVICE: UUID = UUID.fromString("7f1a0001-5c3e-4b8e-9a51-0ffc0de0a001")
    val DATA: UUID = UUID.fromString("7f1a0002-5c3e-4b8e-9a51-0ffc0de0a001")
    val ACK: UUID = UUID.fromString("7f1a0003-5c3e-4b8e-9a51-0ffc0de0a001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEADER = 9
    const val START_BODY = 36
    const val MIN_MTU = HEADER + START_BODY + 3 + 16
    private const val T_START: Byte = 1
    private const val T_DATA: Byte = 2
    private const val T_ACK: Byte = 3

    /** Largest frame for a negotiated ATT MTU (3 bytes of ATT overhead, 512-byte value cap). */
    fun maxFrame(mtu: Int): Int = minOf(mtu - 3, 512)

    fun frames(blobId: Int, blob: ByteArray, maxFrame: Int): List<ByteArray> {
        require(maxFrame >= HEADER + START_BODY) { "frame size $maxFrame too small" }
        val chunk = maxFrame - HEADER
        val total = (blob.size + chunk - 1) / chunk
        require(total <= 0xFFFF) { "blob too large" }
        val out = ArrayList<ByteArray>(total + 1)
        out += ByteBuffer.allocate(HEADER + START_BODY)
            .put(T_START).putInt(blobId).putShort(0).putShort(total.toShort())
            .putInt(blob.size).put(sha256(blob))
            .array()
        for (i in 0 until total) {
            val from = i * chunk
            val len = minOf(chunk, blob.size - from)
            out += ByteBuffer.allocate(HEADER + len)
                .put(T_DATA).putInt(blobId).putShort((i + 1).toShort()).putShort(total.toShort())
                .put(blob, from, len)
                .array()
        }
        return out
    }

    fun ack(blobId: Int, ok: Boolean): ByteArray =
        ByteBuffer.allocate(6).put(T_ACK).putInt(blobId).put(if (ok) 1 else 0).array()

    /** Returns (blobId, ok), or null if the bytes are not an ack. */
    fun parseAck(b: ByteArray): Pair<Int, Boolean>? {
        if (b.size != 6 || b[0] != T_ACK) return null
        val buf = ByteBuffer.wrap(b)
        buf.get()
        return buf.int to (buf.get() == 1.toByte())
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun shortSha(b: ByteArray): String = sha256(b).take(4).joinToString("") { "%02x".format(it) }

    data class Done(val blobId: Int, val data: ByteArray, val ok: Boolean)

    /**
     * Rebuilds blobs from frames. Tolerates duplicates and any order of DATA frames after
     * START; ignores frames it cannot parse. A blob is reported once all chunks arrive, with
     * ok = false if its length or hash does not match the START frame.
     */
    class Reassembler {
        private class State(val length: Int, val sha: ByteArray, val chunks: Array<ByteArray?>) {
            var received = 0
        }

        private val blobs = HashMap<Int, State>()

        fun accept(frame: ByteArray): Done? {
            if (frame.size < HEADER) return null
            val buf = ByteBuffer.wrap(frame)
            val type = buf.get()
            val id = buf.int
            val seq = buf.short.toInt() and 0xFFFF
            val total = buf.short.toInt() and 0xFFFF
            return when (type) {
                T_START -> {
                    if (seq != 0 || frame.size != HEADER + START_BODY) return null
                    val length = buf.int
                    val sha = ByteArray(32).also { buf.get(it) }
                    if (length < 0) return null
                    blobs[id] = State(length, sha, arrayOfNulls(total))
                    if (total == 0) finish(id) else null
                }
                T_DATA -> {
                    val st = blobs[id] ?: return null
                    if (seq < 1 || seq > st.chunks.size) return null
                    if (st.chunks[seq - 1] == null) {
                        st.chunks[seq - 1] = frame.copyOfRange(HEADER, frame.size)
                        st.received++
                    }
                    if (st.received == st.chunks.size) finish(id) else null
                }
                else -> null
            }
        }

        private fun finish(id: Int): Done {
            val st = blobs.remove(id)!!
            val size = st.chunks.sumOf { it!!.size }
            val data = ByteArray(size)
            var at = 0
            for (c in st.chunks) {
                c!!.copyInto(data, at)
                at += c.size
            }
            val ok = size == st.length && sha256(data).contentEquals(st.sha)
            return Done(id, data, ok)
        }
    }
}
