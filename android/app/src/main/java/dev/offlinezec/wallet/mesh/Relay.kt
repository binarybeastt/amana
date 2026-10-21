package dev.offlinezec.wallet.mesh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What offline-core said about a transaction's bytes. */
data class Verdict(
    val txid: String,
    /** Proofs and signatures verify (offline-core throws on anything that is not a valid tx). */
    val proofsOk: Boolean,
    val forUs: Boolean,
    val anchorKnown: Boolean,
    val nullifiers: List<String>,
    /** Zatoshis to this wallet, and the memo, when [forUs]. */
    val amount: Long = 0,
    val memo: String? = null,
)

/**
 * Takes in transactions from peers: refuses ones past the hop limit or already held, verifies
 * the proofs and signatures of the rest (offline-core, in the `:core` process) *before* storing,
 * and queues the good ones for forwarding. A payment to this wallet is also handed to
 * [onForUs], which files it as received.
 */
class Relay(
    private val queue: RelayQueue,
    private val verify: suspend (ByteArray) -> Verdict,
    private val onForUs: suspend (ByteArray, Verdict) -> Unit = { _, _ -> },
    private val maxHops: Int = Proto.MAX_HOPS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Status { STORED, DUPLICATE, REJECTED, HOP_LIMIT, FULL }

    data class Result(val status: Status, val txid: String, val reason: String? = null) {
        val wire get() = status.name.lowercase()
    }

    // One at a time: the same tx from several peers is verified and stored once.
    private val mutex = Mutex()

    /**
     * [claimedTxid] is what the peer says (it must match the bytes); null when it came with
     * no claim (a scanned QR code, a test).
     */
    suspend fun ingest(claimedTxid: String?, hops: Int, raw: ByteArray, from: String): Result = mutex.withLock {
        val shown = claimedTxid ?: "?"
        if (hops > maxHops) return Result(Status.HOP_LIMIT, shown, "$hops hops, limit $maxHops")
        if (claimedTxid != null && queue.contains(claimedTxid)) return Result(Status.DUPLICATE, claimedTxid)
        if (raw.isEmpty() || raw.size > Wire.MAX_MESSAGE) return Result(Status.REJECTED, shown, "size ${raw.size}")
        val v = try {
            verify(raw)
        } catch (e: Exception) {
            return Result(Status.REJECTED, shown, "invalid transaction: ${e.message}")
        }
        if (!v.proofsOk) return Result(Status.REJECTED, shown, "proofs or signatures do not verify")
        if (claimedTxid != null && v.txid != claimedTxid) {
            return Result(Status.REJECTED, claimedTxid, "txid does not match the bytes (${v.txid})")
        }
        val entry = RelayQueue.Entry(
            txid = v.txid, raw = raw, hops = hops, from = from, firstSeen = now(),
            forUs = v.forUs, nullifiers = v.nullifiers,
        )
        when (queue.add(entry, force = v.forUs)) {
            RelayQueue.AddResult.DUPLICATE -> return Result(Status.DUPLICATE, v.txid)
            RelayQueue.AddResult.FULL -> return Result(Status.FULL, v.txid, "relay queue full")
            RelayQueue.AddResult.ADDED -> {}
        }
        if (v.forUs) onForUs(raw, v)
        Result(Status.STORED, v.txid)
    }
}
