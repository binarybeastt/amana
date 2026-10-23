package dev.offlinezec.wallet.wallet

import dev.offlinezec.wallet.mesh.ChainNode
import dev.offlinezec.wallet.mesh.Nullifiers
import dev.offlinezec.wallet.mesh.RelayQueue

/**
 * Reconciliation on reconnect (P4.G4): where an offline payment this phone knows of ends up,
 * from chain facts alone. Pure: [AppController] feeds it what the wallet DB, the relay queue,
 * the nullifier set and lightwalletd say, and applies the result.
 *
 * One tx's fate is one of three: it was mined (settled), a different tx spending one of its
 * nullifiers was mined or the network refused it as a double spend (conflicted: it can never
 * be mined), or neither (still pending: keep carrying and rebroadcasting it). Seen mined always
 * wins, so a tx wrongly thought conflicted still settles.
 */
object Reconcile {
    enum class Role { SENDER, RECEIVER, RELAY }
    enum class Outcome { SETTLED, CONFLICTED, PENDING }

    /** What this phone knows about one tx. */
    data class Facts(
        /** This tx seen mined (wallet DB or lightwalletd). */
        val minedHeight: Long? = null,
        /** A different txid seen mined spending one of this tx's nullifiers. */
        val conflictMined: String? = null,
        /** The network refused this tx as a double spend. */
        val doubleSpendRejected: Boolean = false,
    )

    data class Result(
        val outcome: Outcome,
        /** What the screen says. */
        val label: String,
        /** Keep sending it to the network (and carrying it over Bluetooth). */
        val rebroadcast: Boolean,
        /** Sender: what the spent notes' status must be. Null for receiver and relay. */
        val noteStatus: String?,
        /** Receiver: counted in "received, not yet settled". Never for a conflicted one. */
        val countsAsIncoming: Boolean,
    )

    fun outcome(f: Facts): Outcome = when {
        f.minedHeight != null -> Outcome.SETTLED
        f.conflictMined != null || f.doubleSpendRejected -> Outcome.CONFLICTED
        else -> Outcome.PENDING
    }

    fun reconcile(role: Role, f: Facts): Result {
        val o = outcome(f)
        val conflicted = "conflicted: double spend, will never settle"
        return when (role) {
            // The notes follow the wallet: spent by whichever tx was mined, ours or the other.
            Role.SENDER -> when (o) {
                Outcome.SETTLED -> Result(o, "settled", false, "spent", false)
                Outcome.CONFLICTED -> Result(o, "$conflicted (another payment spent the same note)", false, "spent", false)
                Outcome.PENDING -> Result(o, "signed offline, not yet settled", true, "spent-offline", false)
            }
            Role.RECEIVER -> when (o) {
                Outcome.SETTLED -> Result(o, "settled", false, null, false)
                Outcome.CONFLICTED -> Result(o, "$conflicted (not counted)", false, null, false)
                Outcome.PENDING -> Result(o, "received, not yet settled", true, null, true)
            }
            Role.RELAY -> when (o) {
                Outcome.SETTLED -> Result(o, "settled", false, null, false)
                Outcome.CONFLICTED -> Result(o, conflicted, false, null, false)
                Outcome.PENDING -> Result(o, "queued", true, null, false)
            }
        }
    }

    /**
     * Applies the chain facts to every entry of [queue] not yet settled and returns the entries
     * that changed, with their outcome. [walletMined]: the height the wallet saw an own or
     * received tx mined at (relays learn it from lightwalletd, in the Broadcaster).
     */
    suspend fun apply(
        queue: RelayQueue,
        conflicts: Collection<Nullifiers.Conflict>,
        minedSpends: Map<String, String>,
        walletMined: suspend (RelayQueue.Entry) -> Long?,
    ): List<Pair<RelayQueue.Entry, Outcome>> {
        val out = ArrayList<Pair<RelayQueue.Entry, Outcome>>()
        for (e in queue.all().filter { it.state != RelayQueue.State.SETTLED }) {
            val ours = e.mine || e.forUs
            // A relayed tx that lost stays lost: only our own are re-checked against the wallet.
            if (!ours && e.state == RelayQueue.State.CONFLICTED) continue
            val mined = if (ours) walletMined(e) else null
            val f = facts(e.txid, e.nullifiers, mined, minedSpends, conflicts, e.lastResult)
            val new = when (outcome(f)) {
                Outcome.SETTLED -> queue.markSettled(e.txid, mined)
                Outcome.CONFLICTED -> queue.markConflicted(e.txid, f.conflictMined?.let { "mined $it" } ?: "rejected as a double spend")
                Outcome.PENDING -> null
            }
            if (new != null && new != e) out += new to outcome(f)
        }
        return out
    }

    /**
     * Gathers the facts for [txid], which spends [nullifiers]. [minedSpends]: nullifier → the
     * mined txid the wallet saw spend it (only our own notes; the sender's view). [conflicts]:
     * the nullifier set's double spends, whose `settled` is a txid this phone saw mined.
     * [lastResult]: the last broadcast result text, if any.
     */
    fun facts(
        txid: String,
        nullifiers: Collection<String>,
        minedHeight: Long?,
        minedSpends: Map<String, String> = emptyMap(),
        conflicts: Collection<Nullifiers.Conflict> = emptyList(),
        lastResult: String? = null,
    ): Facts {
        val byWallet = nullifiers.firstNotNullOfOrNull { nf -> minedSpends[nf]?.takeIf { it != txid } }
        val bySet = conflicts.firstOrNull { c -> c.nullifier in nullifiers && c.settled != null && c.settled != txid }?.settled
        return Facts(minedHeight, byWallet ?: bySet, lastResult?.let(ChainNode::isDoubleSpend) == true)
    }
}
