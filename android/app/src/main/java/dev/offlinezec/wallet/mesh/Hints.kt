package dev.offlinezec.wallet.mesh

import dev.offlinezec.wallet.core.hexToBytes
import dev.offlinezec.wallet.core.toHex

/**
 * Nullifier hints in the Bluetooth advert (protocol v3): every phone in radio range hears them
 * at once, without connecting, so a double spend is noticed in about a second instead of
 * waiting for a GATT session with each peer in turn.
 *
 * The scan response holds manufacturer data under [COMPANY] (the Bluetooth SIG's test id):
 *
 *   version (1) | node id (8) | up to [PER_ADVERT] hints of nullifier prefix (6) + txid prefix (3)
 *
 * 2 + 2 + 9 + 18 = 31 bytes, the legacy limit. v2 phones put version + node id in service data
 * instead ([Proto.parseAdvert]); they find nothing there in a v3 scan response and ignore us.
 *
 * A hint is only a reason to connect: adverts can be forged, so nothing alerts on a hint. A
 * scanning phone that finds a [suspect] hint connects to that peer ahead of normal sync, and
 * the conflict alert comes, as before, from the NF gossip in that session.
 *
 * Privacy: anyone scanning sees nullifier and txid prefixes of the unsettled payments a phone
 * carries. Both become public on chain once the payment is mined, and neither reveals an
 * address, an amount or who paid whom; the node id is already in the advert.
 */
object Hints {
    const val COMPANY = 0xFFFF
    const val NF_BYTES = 6
    const val TX_BYTES = 3
    const val HINT_BYTES = NF_BYTES + TX_BYTES
    const val PER_ADVERT = 2
    /** Oldest hints beyond this many frames are left to normal gossip. */
    const val MAX_FRAMES = 32

    /** Hex prefixes: [nf] of a nullifier, [tx] of the txid spending it. */
    data class Hint(val nf: String, val tx: String) {
        override fun toString() = "$nf:$tx"
    }

    data class Advert(val version: Int, val node: String, val hints: List<Hint>)

    fun of(nullifier: String, txid: String) = Hint(nullifier.take(2 * NF_BYTES), txid.take(2 * TX_BYTES))

    fun encode(version: Int, node: String, hints: List<Hint>): ByteArray {
        require(hints.size <= PER_ADVERT) { "${hints.size} hints do not fit" }
        return byteArrayOf(version.toByte()) + node.hexToBytes() +
            hints.fold(ByteArray(0)) { b, h -> b + h.nf.hexToBytes() + h.tx.hexToBytes() }
    }

    /** The manufacturer data of a scan response, or null if it is not a v3+ advert of ours. */
    fun decode(b: ByteArray?): Advert? {
        val head = 1 + Proto.NODE_ID_BYTES
        if (b == null || b.size < head || (b.size - head) % HINT_BYTES != 0) return null
        val n = (b.size - head) / HINT_BYTES
        if (n > PER_ADVERT) return null
        val hints = List(n) {
            val at = head + it * HINT_BYTES
            Hint(b.copyOfRange(at, at + NF_BYTES).toHex(), b.copyOfRange(at + NF_BYTES, at + HINT_BYTES).toHex())
        }
        return Advert(b[0].toInt() and 0xFF, b.copyOfRange(1, head).toHex(), hints)
    }

    /**
     * What this phone advertises, one frame per advert, rotated: both sides of each unsettled
     * conflict first (a pair in one frame, so phones holding neither side still learn of it),
     * then the nullifiers of the unsettled payments it holds, newest first.
     */
    fun frames(conflicts: List<Nullifiers.Conflict>, unsettled: List<RelayQueue.Entry>): List<List<Hint>> {
        val out = ArrayList<List<Hint>>()
        for (c in conflicts.asReversed()) {
            if (c.settled != null) continue
            for (t in c.txids.drop(1)) out += listOf(of(c.nullifier, c.txids[0]), of(c.nullifier, t))
        }
        val conflicted = conflicts.mapTo(HashSet()) { it.nullifier }
        unsettled.sortedByDescending { it.firstSeen }
            .flatMap { e -> e.nullifiers.filter { it !in conflicted }.map { of(it, e.txid) } }
            .distinct().chunked(PER_ADVERT).forEach { out += it }
        return out.take(MAX_FRAMES)
    }

    /**
     * The hints in one peer's advert that point at a double spend we do not know of yet.
     * [known] gives every txid this phone has seen spending a nullifier with that prefix.
     *  - We know the nullifier and the peer's txid is none of ours: suspect.
     *  - Same nullifier, same txid: an honest payment we both carry...
     *  - ...unless we know a conflict on it and the peer shows one side only: it holds a
     *    double-spent payment without knowing, so we bring it the news. (A phone that knows the
     *    conflict advertises the pair instead.)
     *  - We do not know the nullifier: suspect only if the advert itself shows two txids for it
     *    (the peer holds a conflict).
     * Honest traffic never has two txids for one nullifier, so it is never suspect.
     */
    fun suspects(hints: List<Hint>, known: (String) -> Collection<String>): List<Hint> =
        hints.groupBy { it.nf }.flatMap { (nf, hs) ->
            val ours = known(nf)
            val txs = hs.map { it.tx }.distinct()
            when {
                ours.isEmpty() -> if (txs.size >= 2) hs else emptyList()
                ours.size >= 2 && txs.size < 2 -> hs
                else -> hs.filter { h -> ours.none { it.startsWith(h.tx) } }
            }
        }
}
