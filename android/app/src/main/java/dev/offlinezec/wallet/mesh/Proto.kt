package dev.offlinezec.wallet.mesh

import dev.offlinezec.wallet.core.hexToBytes
import dev.offlinezec.wallet.core.toHex
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The mesh protocol between two phones running this app. The phone that connects (the GATT
 * client) asks and pushes; the other (the GATT server) answers. One request at a time:
 *
 *   HELLO       → HELLO_REPLY   app id, protocol version, network; a mismatch is refused
 *   INV_REQ     → INV           txids the server already holds
 *   NF          → NF            nullifier claims (nullifier, txid): the client's, then the server's
 *   TX          → TX_RESULT     one transaction the server lacks: stored, duplicate, rejected…
 *
 * Messages travel as [Wire] frames: client writes on [RX], server notifies on [TX].
 */
object Proto {
    val SERVICE: UUID = UUID.fromString("7f1a1001-5c3e-4b8e-9a51-0ffc0de0a001")
    /** Client → server (write with response). */
    val RX: UUID = UUID.fromString("7f1a1002-5c3e-4b8e-9a51-0ffc0de0a001")
    /** Server → client (notify). */
    val TX: UUID = UUID.fromString("7f1a1003-5c3e-4b8e-9a51-0ffc0de0a001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val APP_ID = "offlinezec-wallet"
    /** 2: adds NF (nullifier gossip, P4.G2). */
    const val VERSION = 2
    /** Phones forward a transaction at most this many Bluetooth hops from where it was signed. */
    const val MAX_HOPS = 6

    const val NODE_ID_BYTES = 8
    /** Most nullifier claims in one NF message: 64 bytes each, so 64 KiB. */
    const val MAX_CLAIMS = 1024

    const val T_HELLO: Byte = 1
    const val T_HELLO_REPLY: Byte = 2
    const val T_INV_REQ: Byte = 3
    const val T_INV: Byte = 4
    const val T_TX: Byte = 5
    const val T_TX_RESULT: Byte = 6
    /** The request arrived corrupt or could not be parsed; the client may resend. */
    const val T_NACK: Byte = 7
    const val T_NF: Byte = 8

    data class Hello(val app: String, val version: Int, val network: String, val node: String) {
        fun encode(): ByteArray = json(T_HELLO, JSONObject().put("app", app).put("v", version).put("net", network).put("node", node))
    }

    data class HelloReply(val ok: Boolean, val reason: String?, val hello: Hello?) {
        fun encode(): ByteArray = json(T_HELLO_REPLY, JSONObject().put("ok", ok).put("reason", reason ?: JSONObject.NULL).apply {
            hello?.let { put("app", it.app).put("v", it.version).put("net", it.network).put("node", it.node) }
        })
    }

    data class Tx(val hops: Int, val txid: String, val raw: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(2 + 32 + raw.size)
            .put(T_TX).put(hops.toByte()).put(txid.hexToBytes()).put(raw).array()
    }

    data class TxResult(val txid: String, val status: String, val reason: String?) {
        fun encode(): ByteArray = json(T_TX_RESULT, JSONObject().put("txid", txid).put("status", status).put("reason", reason ?: JSONObject.NULL))
    }

    fun invReq(): ByteArray = byteArrayOf(T_INV_REQ)

    fun inv(txids: Collection<String>): ByteArray {
        val b = ByteBuffer.allocate(3 + 32 * txids.size).put(T_INV).putShort(txids.size.toShort())
        txids.forEach { b.put(it.hexToBytes()) }
        return b.array()
    }

    fun nf(claims: Collection<Nullifiers.Claim>): ByteArray {
        val list = claims.take(MAX_CLAIMS)
        val b = ByteBuffer.allocate(3 + 64 * list.size).put(T_NF).putShort(list.size.toShort())
        list.forEach { b.put(it.nullifier.hexToBytes()).put(it.txid.hexToBytes()) }
        return b.array()
    }

    fun nack(reason: String): ByteArray = json(T_NACK, JSONObject().put("reason", reason))

    sealed interface Msg
    data class MHello(val hello: Hello) : Msg
    data class MHelloReply(val reply: HelloReply) : Msg
    data object MInvReq : Msg
    data class MInv(val txids: List<String>) : Msg
    data class MTx(val tx: Tx) : Msg
    data class MTxResult(val result: TxResult) : Msg
    data class MNack(val reason: String) : Msg
    data class MNf(val claims: List<Nullifiers.Claim>) : Msg

    /** Parses one reassembled message; throws on anything malformed. */
    fun decode(m: ByteArray): Msg {
        require(m.isNotEmpty()) { "empty message" }
        val body by lazy { JSONObject(String(m, 1, m.size - 1, Charsets.UTF_8)) }
        return when (m[0]) {
            T_HELLO -> MHello(parseHello(body))
            T_HELLO_REPLY -> MHelloReply(
                HelloReply(body.getBoolean("ok"), body.optStringOrNull("reason"), if (body.has("app")) parseHello(body) else null),
            )
            T_INV_REQ -> MInvReq
            T_INV -> {
                val b = ByteBuffer.wrap(m, 1, m.size - 1)
                val n = b.short.toInt() and 0xFFFF
                require(m.size == 3 + 32 * n) { "INV of $n txids has ${m.size} bytes" }
                MInv((0 until n).map { ByteArray(32).also { x -> b.get(x) }.toHex() })
            }
            T_TX -> {
                require(m.size > 34) { "TX too short" }
                MTx(Tx(m[1].toInt() and 0xFF, m.copyOfRange(2, 34).toHex(), m.copyOfRange(34, m.size)))
            }
            T_TX_RESULT -> MTxResult(TxResult(body.getString("txid"), body.getString("status"), body.optStringOrNull("reason")))
            T_NACK -> MNack(body.getString("reason"))
            T_NF -> {
                val b = ByteBuffer.wrap(m, 1, m.size - 1)
                val n = b.short.toInt() and 0xFFFF
                require(n <= MAX_CLAIMS && m.size == 3 + 64 * n) { "NF of $n claims has ${m.size} bytes" }
                MNf(List(n) {
                    val nf = ByteArray(32).also { x -> b.get(x) }.toHex()
                    Nullifiers.Claim(nf, ByteArray(32).also { x -> b.get(x) }.toHex())
                })
            }
            else -> throw IllegalArgumentException("unknown message type ${m[0]}")
        }
    }

    private fun parseHello(o: JSONObject) = Hello(o.getString("app"), o.getInt("v"), o.getString("net"), o.getString("node"))

    private fun json(type: Byte, o: JSONObject): ByteArray = byteArrayOf(type) + o.toString().toByteArray(Charsets.UTF_8)

    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)

    /**
     * Why we refuse to talk to a peer, or null if we accept it. The reason is shown to the user
     * and logged on both phones.
     */
    fun refusal(ours: Hello, theirs: Hello): String? = when {
        theirs.app != ours.app -> "not this app (peer is \"${theirs.app}\")"
        theirs.version != ours.version ->
            "protocol version mismatch: peer speaks v${theirs.version}, this phone v${ours.version}; update the older app"
        theirs.network != ours.network -> "network mismatch: peer is on ${theirs.network}, this phone on ${ours.network}"
        theirs.node == ours.node -> "peer has this phone's own node id"
        else -> null
    }

    /**
     * What a phone advertises in its scan response next to [SERVICE]: protocol version (1) and
     * node id (8). 128-bit UUID + 9 bytes fits the 31-byte limit.
     */
    fun advert(nodeId: String, version: Int = VERSION): ByteArray = byteArrayOf(version.toByte()) + nodeId.hexToBytes()

    /** (version, node id) from a scan response, or null if it is not ours. */
    fun parseAdvert(b: ByteArray?): Pair<Int, String>? {
        if (b == null || b.size != 1 + NODE_ID_BYTES) return null
        return (b[0].toInt() and 0xFF) to b.copyOfRange(1, b.size).toHex()
    }
}
