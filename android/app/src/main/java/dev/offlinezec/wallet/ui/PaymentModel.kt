package dev.offlinezec.wallet.ui

import dev.offlinezec.wallet.core.Note
import dev.offlinezec.wallet.core.PendingPayment
import dev.offlinezec.wallet.mesh.RelayQueue
import dev.offlinezec.wallet.receivedLabel
import dev.offlinezec.wallet.sentLabel
import dev.offlinezec.wallet.wallet.Received
import dev.offlinezec.wallet.wallet.SPEND_CONFIRMATIONS

/**
 * What the screens show, derived from wallet facts with no Android or Compose in the way, so the
 * JVM tests can drive every state (P5.G1, P5.G2).
 */

/** P5.G1: the one status chip a payment wears. */
enum class PayStatus(val chip: String) {
    /** Signed (or received) offline; no phone has reached the network with it yet. */
    PENDING("pending"),
    /** Handed to the network (broadcast), waiting to be mined. */
    RELAYED("relayed"),
    SETTLED("settled"),
    /** Another tx spending the same note won (or will): this one can never settle. */
    CONFLICTED("conflicted"),
    /** The network refused it for another reason. */
    REJECTED("rejected"),
}

enum class Direction { SENT, RECEIVED, RELAY }

data class PaymentRow(
    val txid: String,
    val direction: Direction,
    /** Zatoshis; null when this phone cannot know (a stranger's relayed payment). */
    val amount: Long?,
    val status: PayStatus,
    /** The longer text under the title (the P4.G4 labels for sent and received payments). */
    val label: String,
    /** When this phone first saw it (ms). */
    val since: Long,
    val memo: String? = null,
    /** Own, offline, still pending: its QR can be shown to the receiver again. */
    val canShowQr: Boolean = false,
    /** Bluetooth hops from the phone that signed it (relay rows). */
    val hops: Int = 0,
)

fun statusOf(state: RelayQueue.State): PayStatus = when (state) {
    RelayQueue.State.QUEUED -> PayStatus.PENDING
    RelayQueue.State.SENT -> PayStatus.RELAYED
    RelayQueue.State.SETTLED -> PayStatus.SETTLED
    RelayQueue.State.CONFLICTED -> PayStatus.CONFLICTED
    RelayQueue.State.REJECTED -> PayStatus.REJECTED
}

fun relayText(e: RelayQueue.Entry): String = when (e.state) {
    RelayQueue.State.QUEUED -> "waiting for a connection"
    RelayQueue.State.SENT -> "broadcast, waiting to be mined"
    RelayQueue.State.SETTLED -> "settled" + (e.minedHeight?.let { " at block $it" } ?: "")
    RelayQueue.State.REJECTED -> "rejected by the network"
    RelayQueue.State.CONFLICTED -> "conflicted: double spend, will never settle"
}

/**
 * Every payment this phone knows of, newest first, one row per txid. Sources: [pending] (our
 * stored, unmined txs from the wallet DB), [received] (payments to us), [queue] (all the relay
 * carries, settled ones too) and [sentAmounts] (amounts of our own payments remembered after
 * they leave the pending list).
 */
fun paymentRows(
    pending: List<PendingPayment>,
    received: List<Received>,
    queue: List<RelayQueue.Entry>,
    sentAmounts: Map<String, Long> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): List<PaymentRow> {
    val byTxid = queue.associateBy { it.txid }
    val rows = LinkedHashMap<String, PaymentRow>()
    for (r in received) {
        val e = byTxid[r.txid]
        val status = when {
            r.settled -> PayStatus.SETTLED
            r.conflicted -> PayStatus.CONFLICTED
            e != null -> statusOf(e.state).let { if (it == PayStatus.SETTLED || it == PayStatus.CONFLICTED) PayStatus.PENDING else it }
            else -> PayStatus.PENDING
        }
        rows[r.txid] = PaymentRow(r.txid, Direction.RECEIVED, r.amount, status, receivedLabel(r), r.receivedAt, r.memo)
    }
    for (p in pending.filter { it.offline }) {
        val e = byTxid[p.txid]
        val status = e?.let { statusOf(it.state) } ?: PayStatus.PENDING
        rows[p.txid] = PaymentRow(
            p.txid, Direction.SENT, p.amount, status,
            label = when (status) {
                PayStatus.CONFLICTED -> sentLabel(true)
                PayStatus.PENDING -> sentLabel(false)
                else -> relayText(e!!)
            },
            since = e?.firstSeen ?: now,
            canShowQr = status == PayStatus.PENDING || status == PayStatus.RELAYED,
        )
    }
    for (e in queue) {
        if (e.txid in rows) continue
        val status = statusOf(e.state)
        rows[e.txid] = when {
            e.mine -> PaymentRow(
                e.txid, Direction.SENT, sentAmounts[e.txid], status,
                if (status == PayStatus.CONFLICTED) sentLabel(true) else relayText(e), e.firstSeen,
            )
            // Received over Bluetooth but not (yet) in the received store: still ours.
            e.forUs -> PaymentRow(e.txid, Direction.RECEIVED, null, status, relayText(e), e.firstSeen)
            else -> PaymentRow(e.txid, Direction.RELAY, null, status, relayText(e), e.firstSeen, hops = e.hops)
        }
    }
    return rows.values.sortedByDescending { it.since }
}

/**
 * P5.G2: the wallet's own value, split four ways. Every unspent note of ours falls in exactly
 * one part, so available + locked + pending + wallet is the wallet total (what the SDK shows too:
 * notes spent by an offline payment are already gone from it).
 */
data class BalanceSplit(
    /** Offline pocket notes, mined, with enough confirmations: offline-core can pay with them now. */
    val available: Long,
    /** Pocket notes not spendable offline yet: too few confirmations, or not in the Ironwood pool. */
    val locked: Long,
    /** Ours but unmined: change and incoming from transactions still waiting to be mined. */
    val pending: Long,
    /** Value of notes our own unsettled offline payments spend (already paid; not in the total). */
    val inPayments: Long,
    /** Mined notes outside the offline pocket: spendable online, or after "prepare". */
    val wallet: Long = 0,
) {
    val total get() = available + locked + pending + wallet
}

/** [notes]: this account's notes from offline-core; [tip]: the latest height known. */
fun balanceSplit(notes: List<Note>, tip: Long?): BalanceSplit {
    var available = 0L
    var locked = 0L
    var pending = 0L
    var inPayments = 0L
    var wallet = 0L
    for (n in notes) {
        when (n.status) {
            "spent" -> {}
            "spent-offline" -> inPayments += n.value
            "pending" -> pending += n.value
            else -> {
                val confirmed = n.minedHeight != null && tip != null && n.minedHeight + SPEND_CONFIRMATIONS <= tip + 1
                if (!n.offline) wallet += n.value
                else if (n.pool == "ironwood" && confirmed) available += n.value else locked += n.value
            }
        }
    }
    return BalanceSplit(available, locked, pending, inPayments, wallet)
}

/** "07daed6f…c8ceac": first 8 and last 6 hex digits. */
fun shortTxid(txid: String): String = if (txid.length <= 16) txid else "${txid.take(8)}…${txid.takeLast(6)}"

/** "utest1599…sm0q": enough of an address to recognise it. */
fun shortAddress(a: String): String = if (a.length <= 18) a else "${a.take(10)}…${a.takeLast(6)}"

/** How long ago, in words that never need a spinner: "just now", "5 min ago", "1 d 2 h ago". */
fun ago(since: Long, now: Long): String {
    val m = ((now - since) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} d ${m / 60 % 24} h ago"
    }
}
