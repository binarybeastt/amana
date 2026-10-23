package dev.offlinezec.wallet.mesh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** A lightwalletd server as the broadcaster sees it. */
interface ChainNode {
    sealed interface Submit {
        data object Sent : Submit
        /** The node already has it: in its mempool, or (if [mined]) already in a block. */
        data class AlreadyKnown(val message: String, val mined: Boolean) : Submit
        /** The network refused it for good (double spend, invalid). */
        data class Rejected(val message: String) : Submit
        /** Could not reach the node, or it failed in a way worth retrying. */
        data class Retry(val message: String) : Submit
    }

    sealed interface Mined {
        data class Height(val height: Long) : Mined
        data object Mempool : Mined
        data object NotFound : Mined
        data class Retry(val message: String) : Mined
    }

    suspend fun submit(txid: String, raw: ByteArray): Submit
    suspend fun mined(txid: String): Mined

    companion object {
        /**
         * Sorts a node's error text. "Already in mempool / already in state" means another
         * phone got there first, which is success for a relay (P0.G3.T4 recorded both texts).
         */
        fun classify(message: String): Submit {
            val m = message.lowercase()
            return when {
                // First: "another transaction in the mempool has already spent some of its inputs"
                // also says "already … mempool", but it is a double spend.
                isDoubleSpend(m) || "bad-txns" in m || "invalid" in m || "incorrect consensus branch" in m -> Submit.Rejected(message)
                "already" in m && ("in state" in m || "in the state" in m || "mined" in m || "in block" in m) ->
                    Submit.AlreadyKnown(message, mined = true)
                // Zebra's mempool, for a tx already in a block (seen in Gate P3 on 2026-10-21).
                "committed to the best chain" in m -> Submit.AlreadyKnown(message, mined = true)
                "already" in m && ("mempool" in m || "known" in m || "exists" in m || "have" in m) ->
                    Submit.AlreadyKnown(message, mined = false)
                else -> Submit.Retry(message)
            }
        }

        /** The node refused a tx because another one already spends one of its notes (P4.G4). */
        fun isDoubleSpend(message: String): Boolean {
            val m = message.lowercase()
            return "double-spend" in m || "double spend" in m || "duplicate nullifier" in m ||
                "spent some of its inputs" in m || "already spent" in m
        }
    }
}

/**
 * Sends every unsettled queued transaction to the network while this phone is online, and
 * watches sent ones until they are mined. Unreachable server: exponential back-off from
 * [minBackoffMs] to [maxBackoffMs], then retry; any success resets it.
 */
class Broadcaster(
    private val queue: RelayQueue,
    private val node: () -> ChainNode,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    val minBackoffMs: Long = 2_000,
    val maxBackoffMs: Long = 60_000,
    /** Between passes while something is still unsettled. */
    val pollMs: Long = 10_000,
) {
    data class Pass(val sent: Int, val settled: Int, val rejected: Int, val unreachable: String?)

    private val kick = Channel<Unit>(Channel.CONFLATED)

    /** Something new to send: wake the loop now instead of at its next poll. */
    fun wake() {
        kick.trySend(Unit)
    }

    /** Backoff history of the last [run], for tests and the state dump. */
    @Volatile var lastBackoffMs = 0L
        private set

    /** One pass over the queue. Stops at the first sign the node is unreachable. */
    suspend fun pass(): Pass {
        var sent = 0
        var settled = 0
        var rejected = 0
        for (e in queue.unsettled()) {
            if (e.state == RelayQueue.State.SENT) {
                when (val m = node().mined(e.txid)) {
                    is ChainNode.Mined.Height -> {
                        queue.markSettled(e.txid, m.height)
                        log("SETTLED ${e.txid} height=${m.height}")
                        settled++
                        continue
                    }
                    ChainNode.Mined.Mempool -> continue
                    is ChainNode.Mined.Retry -> return Pass(sent, settled, rejected, m.message)
                    ChainNode.Mined.NotFound -> {} // dropped from the mempool: send it again
                }
            }
            when (val r = node().submit(e.txid, e.raw)) {
                ChainNode.Submit.Sent -> {
                    queue.update(e.txid) { it.copy(state = RelayQueue.State.SENT, sentAt = now(), attempts = it.attempts + 1, lastResult = "sent") }
                    log("BROADCAST ${e.txid} sent")
                    sent++
                }
                is ChainNode.Submit.AlreadyKnown -> {
                    queue.update(e.txid) {
                        it.copy(state = RelayQueue.State.SENT, sentAt = it.sentAt ?: now(), attempts = it.attempts + 1, lastResult = "already known: ${r.message}")
                    }
                    log("BROADCAST ${e.txid} already known (${r.message})")
                    sent++
                    if (r.mined) {
                        val h = (node().mined(e.txid) as? ChainNode.Mined.Height)?.height
                        queue.markSettled(e.txid, h)
                        log("SETTLED ${e.txid} height=${h ?: "?"}")
                        settled++
                    }
                }
                is ChainNode.Submit.Rejected -> {
                    // A double spend can never be mined: conflicted (P4.G4); anything else is just refused.
                    val st = if (ChainNode.isDoubleSpend(r.message)) RelayQueue.State.CONFLICTED else RelayQueue.State.REJECTED
                    // Seen mined wins: a node may refuse our own tx as a double spend once it is in a block.
                    val m = if (st == RelayQueue.State.CONFLICTED) node().mined(e.txid) as? ChainNode.Mined.Height else null
                    if (m != null) {
                        queue.markSettled(e.txid, m.height)
                        log("SETTLED ${e.txid} height=${m.height} (refused as a double spend of itself)")
                        settled++
                        continue
                    }
                    queue.update(e.txid) { it.copy(state = st, attempts = it.attempts + 1, lastResult = "rejected: ${r.message}") }
                    log("BROADCAST ${e.txid} rejected (${r.message})")
                    rejected++
                }
                is ChainNode.Submit.Retry -> {
                    queue.update(e.txid) { it.copy(attempts = it.attempts + 1, lastResult = "retry: ${r.message}") }
                    log("BROADCAST ${e.txid} unreachable (${r.message})")
                    return Pass(sent, settled, rejected, r.message)
                }
            }
        }
        return Pass(sent, settled, rejected, null)
    }

    /** Runs passes until [online] turns false. Call again on the next connection. */
    suspend fun run(online: () -> Boolean) {
        var backoff = minBackoffMs
        while (online()) {
            val p = try {
                pass()
            } catch (e: Exception) {
                Pass(0, 0, 0, "${e.javaClass.simpleName}: ${e.message}")
            }
            val wait = if (p.unreachable != null) {
                backoff.also {
                    lastBackoffMs = it
                    log("BACKOFF ${it}ms (${p.unreachable})")
                    backoff = minOf(backoff * 2, maxBackoffMs)
                }
            } else {
                backoff = minBackoffMs
                lastBackoffMs = 0
                if (queue.unsettled().isEmpty()) IDLE_MS else pollMs
            }
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    companion object {
        const val IDLE_MS = 60_000L
    }
}
