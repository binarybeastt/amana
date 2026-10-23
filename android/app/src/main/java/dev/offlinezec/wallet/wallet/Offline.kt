package dev.offlinezec.wallet.wallet

import android.content.Context
import android.util.Log
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import dev.offlinezec.wallet.core.BuiltPayment
import dev.offlinezec.wallet.core.CoreException
import dev.offlinezec.wallet.core.Note
import dev.offlinezec.wallet.core.VerifiedPayment
import dev.offlinezec.wallet.core.hexToBytes
import dev.offlinezec.wallet.core.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Notes need this many confirmations before offline-core will spend them (its MIN_CONFIRMATIONS). */
const val SPEND_CONFIRMATIONS = 10

/** Where "prepare for offline" stands, derived from the wallet DB every time (it is the truth). */
sealed interface PrepareStatus {
    /** No split in flight. [notes] = available Ironwood notes; [ready] = all have 10 confirmations. */
    data class Idle(val notes: List<Note>, val tip: Long?, val ready: Boolean) : PrepareStatus
    /** A split (or other online tx of ours) is waiting to be mined. */
    data class Waiting(val txid: String) : PrepareStatus
}

/**
 * "Prepare for offline": splits the balance into denominations with one online self-payment
 * (planned by offline-core, built and broadcast by the SDK), then waits for it to be mined.
 *
 * Crash- and double-tap-safe: the wallet DB is the only state. A split stored by the SDK is an
 * unmined online transaction spending our notes, so [prepare] refuses to start another while
 * one is listed by `inFlightOnlineTxs`, whether this process started it or a killed one did.
 */
class Preparer(private val mgr: WalletManager) {
    private val mutex = Mutex()

    suspend fun status(): PrepareStatus {
        val w = mgr.wallet ?: error("no wallet")
        val inFlight = mgr.core.inFlightOnlineTxs(w.dbPath)
        if (inFlight.isNotEmpty()) return PrepareStatus.Waiting(inFlight.first())
        val tip = mgr.sync.value.networkHeight ?: mgr.sync.value.scannedHeight
        val notes = mgr.core.notes(w.dbPath)
            .filter { it.accountUuid == w.accountUuid && it.pool == "ironwood" && it.status == "available" }
            .sortedByDescending { it.value }
        val ready = notes.isNotEmpty() && tip != null &&
            notes.all { n -> n.minedHeight != null && n.minedHeight + SPEND_CONFIRMATIONS <= tip + 1 }
        return PrepareStatus.Idle(notes, tip, ready)
    }

    /** Sends the split. Returns its txid, or the txid of the split already in flight. */
    suspend fun prepare(): String = mutex.withLock {
        val w = mgr.wallet ?: error("no wallet")
        mgr.core.inFlightOnlineTxs(w.dbPath).firstOrNull()?.let {
            Log.i(TAG, "prepare: split $it already in flight; not sending another")
            return it
        }
        val req = mgr.core.planSplit(w.dbPath, w.accountUuid, w.address)
        Log.i(TAG, "prepare: ${req.inputNotes} notes, ${req.inputTotal} zats, fee ${req.fee}")
        val proposal = w.synchronizer.proposeFulfillingPaymentUri(w.account, req.zip321)
        val results = w.synchronizer.createProposedTransactions(proposal, mgr.spendingKey()).toList()
        val txid = results.firstOrNull()?.txIdString() ?: error("SDK created no transaction")
        results.filterIsInstance<TransactionSubmitResult.Failure>().forEach {
            // Stored but not sent: the SDK resubmits unmined txs with expiry > tip on its own.
            Log.w(TAG, "prepare: submit failed (${it.code} ${it.description}); SDK will retry")
        }
        Log.i(TAG, "prepare: split $txid sent")
        txid
    }

    private companion object {
        const val TAG = "Preparer"
    }
}

/** Builds offline payments. Only one at a time. [caps] = the user's offline limits (P4.G3). */
class Payer(private val mgr: WalletManager, private val caps: () -> Caps = { Caps() }) {
    private val mutex = Mutex()

    /**
     * Builds, proves, signs and stores the payment (write-before-send) in offline-core. Only
     * when this returns are the bytes safe to show as a QR code: the notes are spent-offline.
     */
    suspend fun pay(to: String, amount: Long, memo: String?): BuiltPayment = mutex.withLock {
        val w = mgr.wallet ?: error("no wallet")
        // Under the lock, so two payments can't both fit under the session cap's last room.
        caps().takeIf { it.enabled }?.let { c ->
            Limits.refusal(c, mgr.core.pendingPayments(w.dbPath), amount)?.let { throw LimitException(it) }
        }
        // The SDK may hold a write lock (e.g. finishing a sync) longer than offline-core waits.
        // Nothing is stored until the last step, so retrying a "database is locked" build is safe.
        var attempt = 1
        while (true) {
            try {
                return mgr.core.payOffline(w.dbPath, mgr.seed(), w.accountUuid, to, amount, memo)
            } catch (e: CoreException) {
                val busy = e.message.orEmpty().let { "database is locked" in it || "DatabaseBusy" in it }
                if (!busy || attempt >= PAY_ATTEMPTS) throw e
                Log.w("Payer", "wallet DB busy (attempt $attempt), retrying")
                attempt++
                delay(2_000)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private companion object {
        const val PAY_ATTEMPTS = 4
    }
}

/** A payment received offline, kept with its raw bytes so this phone can broadcast it too. */
data class Received(
    val txid: String,
    val amount: Long,
    val memo: String?,
    val receivedAt: Long,
    val raw: ByteArray,
    val minedHeight: Int? = null,
) {
    val settled get() = minedHeight != null
}

/** Received payments, one JSON file each under files/received/. */
class ReceivedStore(context: Context) {
    private val dir = File(context.filesDir, "received").apply { mkdirs() }

    fun all(): List<Received> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { parse(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.receivedAt }

    fun get(txid: String): Received? = File(dir, "$txid.json").takeIf { it.exists() }?.let { parse(JSONObject(it.readText())) }

    fun put(r: Received) {
        val o = JSONObject()
            .put("txid", r.txid).put("amount", r.amount).put("memo", r.memo ?: JSONObject.NULL)
            .put("received_at", r.receivedAt).put("raw_hex", r.raw.toHex())
            .put("mined_height", r.minedHeight ?: JSONObject.NULL)
        val tmp = File(dir, "${r.txid}.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "${r.txid}.json"))
    }

    private fun parse(o: JSONObject) = Received(
        txid = o.getString("txid"),
        amount = o.getLong("amount"),
        memo = if (o.isNull("memo")) null else o.getString("memo"),
        receivedAt = o.getLong("received_at"),
        raw = o.getString("raw_hex").hexToBytes(),
        minedHeight = if (o.isNull("mined_height")) null else o.getInt("mined_height"),
    )
}

/** Outcome of trying to receive scanned bytes. */
sealed interface ReceiveResult {
    data class Accepted(val payment: Received, val check: VerifiedPayment) : ReceiveResult
    data class Rejected(val reason: String, val check: VerifiedPayment?) : ReceiveResult
}

class Receiver(private val mgr: WalletManager, private val store: ReceivedStore) {
    /** Verifies [raw] offline against this wallet; keeps it if it is a valid payment to us. */
    suspend fun receive(raw: ByteArray): ReceiveResult {
        val w = mgr.wallet ?: error("no wallet")
        val check = try {
            mgr.core.verifyPayment(w.dbPath, raw, w.accountUuid)
        } catch (e: Exception) {
            return ReceiveResult.Rejected("not a valid transaction: ${e.message}", null)
        }
        val reason = when {
            !check.proofsOk -> "proofs or signatures do not verify"
            !check.forUs -> "this payment is not for this wallet"
            !check.anchorKnown -> "sender's anchor is unknown here: sync this phone, then scan again"
            else -> null
        }
        if (reason != null) return ReceiveResult.Rejected(reason, check)
        val existing = store.get(check.txid)
        val r = existing ?: Received(check.txid, check.amount, check.memo, System.currentTimeMillis(), raw)
        if (existing == null) store.put(r)
        return ReceiveResult.Accepted(r, check)
    }
}

/**
 * One tx this phone can broadcast: its own stored payments, or payments it received. [raw] is
 * null for our own: fetched from offline-core one at a time when sent (binder size limit).
 */
data class Outgoing(val txid: String, val raw: ByteArray?, val mine: Boolean)

/**
 * Broadcasts every unmined payment this phone holds, and marks received ones settled once the
 * wallet has seen them mined. Duplicate sends are harmless ("already in mempool / state").
 */
class Settler(private val mgr: WalletManager, private val store: ReceivedStore) {
    private val mutex = Mutex()

    suspend fun outgoing(): List<Outgoing> {
        val w = mgr.wallet ?: return emptyList()
        val mine = mgr.core.pendingPayments(w.dbPath).map { Outgoing(it.txid, null, true) }
        val received = store.all().filter { !it.settled }.map { Outgoing(it.txid, it.raw, false) }
        return (mine + received).distinctBy { it.txid }
    }

    /** Refreshes settlement of received payments from the wallet DB. Returns how many settled now. */
    suspend fun refreshSettled(): Int {
        val w = mgr.wallet ?: return 0
        var n = 0
        for (r in store.all().filter { !it.settled }) {
            val h = mgr.core.minedHeight(w.dbPath, r.txid) ?: continue
            store.put(r.copy(minedHeight = h))
            n++
        }
        return n
    }

    /** Sends everything unmined. Returns txid -> result text. */
    suspend fun broadcastAll(): Map<String, String> = mutex.withLock {
        val w = mgr.wallet ?: return emptyMap()
        refreshSettled()
        val out = linkedMapOf<String, String>()
        for (tx in outgoing()) {
            if (mgr.core.minedHeight(w.dbPath, tx.txid) != null) continue
            val result = try {
                val raw = tx.raw ?: mgr.core.pendingRaw(w.dbPath, tx.txid)
                val created = CreatedTransaction(
                    txId = FirstClassByteArray(tx.txid.hexToBytes().reversedArray()),
                    raw = FirstClassByteArray(raw),
                    expiryHeight = null,
                )
                when (val r = w.synchronizer.broadcaster.submit(created, Net.endpoint)) {
                    is TransactionSubmitResult.Success -> "sent"
                    is TransactionSubmitResult.Failure ->
                        if (isAlreadyKnown(r.description)) "already known" else "failed: ${r.code} ${r.description}"
                    is TransactionSubmitResult.NotAttempted -> "not attempted"
                }
            } catch (e: Exception) {
                "failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, "broadcast ${tx.txid} (${if (tx.mine) "ours" else "received"}): $result")
            out[tx.txid] = result
        }
        out
    }

    private fun isAlreadyKnown(desc: String?): Boolean {
        val d = desc?.lowercase() ?: return false
        return "already" in d && ("mempool" in d || "in state" in d || "known" in d)
    }

    private companion object {
        const val TAG = "Settler"
    }
}

fun List<Note>.toJson(): JSONArray = JSONArray().also { a ->
    forEach { n ->
        a.put(JSONObject().put("note", n.key).put("value", n.value).put("status", n.status)
            .put("mined_height", n.minedHeight ?: JSONObject.NULL).put("pool", n.pool)
            .put("spent_by", n.spentBy ?: JSONObject.NULL))
    }
}
