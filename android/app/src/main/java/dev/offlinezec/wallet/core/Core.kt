package dev.offlinezec.wallet.core

import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** offline-core failed: a Rust error or panic, or the core process died. */
class CoreException(message: String) : Exception(message)

/** A note of ours as offline-core sees it (statuses: pending, available, spent-offline, spent). */
data class Note(
    val accountUuid: String,
    val pool: String,
    val txid: String,
    val outputIndex: Int,
    val value: Long,
    val minedHeight: Int?,
    val status: String,
    val spentBy: String?,
    /** Hex, as offline-core and the nullifier set write it. Unknown until the note is mined. */
    val nullifier: String? = null,
) {
    val key get() = "$txid:$outputIndex"
}

data class SplitRequest(val inputNotes: Int, val inputTotal: Long, val fee: Long, val zip321: String)

data class BuiltPayment(
    val txid: String,
    val amount: Long,
    val fee: Long,
    val change: Long,
    val spentNotes: List<String>,
    val raw: ByteArray,
)

/**
 * A stored payment of ours, not mined yet. [amount] excludes change; [fee] is null if unknown.
 * No raw bytes: fetch them one payment at a time with [Core.pendingRaw] (binder limit).
 */
data class PendingPayment(val txid: String, val expiryHeight: Int, val rawLen: Int, val amount: Long, val fee: Long?) {
    /** Built offline (expiry 0, never expires), as opposed to an online tx such as a split. */
    val offline get() = expiryHeight == 0
}

data class VerifiedPayment(
    val txid: String,
    val amount: Long,
    val memo: String?,
    val verified: Boolean,
    val proofsOk: Boolean,
    val anchorKnown: Boolean,
    val forUs: Boolean,
    val nullifiers: List<String>,
)

/**
 * The app's handle on offline-core, which runs in the `:core` process behind [CoreProvider].
 * Every call blocks on binder, so it runs on [Dispatchers.IO]. Never throws anything but
 * [CoreException].
 */
class Core(context: Context, private val network: String = "test") {
    private val resolver = context.applicationContext.contentResolver
    private val clientLock = Any()
    private var held: ContentProviderClient? = null

    /**
     * One connection to the core provider, held for the life of this process. While a client
     * holds it, Android ranks `:core` as important as this process, so the cached-app freezer
     * never freezes it. Opening a connection per call left `:core` cached between calls; it
     * was then frozen, and the next call failed or hung until something thawed it (P4.G2.T2,
     * 2026-10-23). *Unstable*: if the core process dies (a native abort, OOM), Android would
     * kill every process holding a stable reference to it, this app included; an unstable
     * client just gets a DeadObjectException, and the next call reconnects.
     */
    private fun client(): ContentProviderClient = synchronized(clientLock) {
        held ?: (resolver.acquireUnstableContentProviderClient(CoreProvider.AUTHORITY)
            ?: throw CoreException("core provider not found")).also { held = it }
    }

    private fun drop(c: ContentProviderClient) = synchronized(clientLock) {
        if (held === c) {
            held = null
            runCatching { c.close() }
        }
    }

    private suspend fun call(method: String, extras: Bundle = Bundle()): String = withContext(Dispatchers.IO) {
        val reply = try {
            val c = client()
            try {
                c.call(method, null, extras)
            } catch (e: Exception) {
                // DeadObjectException etc.: the core process died mid-call. Reconnect next time.
                drop(c)
                throw e
            }
        } catch (e: CoreException) {
            throw e
        } catch (e: Exception) {
            throw CoreException("core process unavailable: ${e.javaClass.simpleName}: ${e.message}")
        } ?: throw CoreException("core process gave no reply to $method")
        reply.getString(CoreProvider.KEY_ERROR)?.let { throw CoreException(it) }
        reply.getString(CoreProvider.KEY_RESULT) ?: throw CoreException("empty reply to $method")
    }

    private fun dbArgs(db: String) = Bundle().apply {
        putString(CoreProvider.KEY_DB, db)
        putString(CoreProvider.KEY_NETWORK, network)
    }

    suspend fun version(): String = call("version")

    /** Test hook: 0 forces a Rust error, 1 a Rust panic, 2 kills the core process. Always throws. */
    suspend fun testFailure(kind: Int): String =
        if (kind == 2) call("testKillProcess") else call("testFailure", Bundle().apply { putInt(CoreProvider.KEY_KIND, kind) })

    suspend fun statusJson(db: String): JSONObject = JSONObject(call("status", dbArgs(db)))

    suspend fun notes(db: String): List<Note> {
        val a = JSONArray(call("notes", dbArgs(db)))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Note(
                accountUuid = o.getString("account_uuid"),
                pool = o.getString("pool"),
                txid = o.getString("txid"),
                outputIndex = o.getInt("output_index"),
                value = o.getLong("value"),
                minedHeight = if (o.isNull("mined_height")) null else o.getInt("mined_height"),
                status = o.getString("status"),
                spentBy = if (o.isNull("spent_by")) null else o.getString("spent_by"),
                nullifier = if (o.isNull("nullifier")) null else o.optString("nullifier", "").ifEmpty { null },
            )
        }
    }

    suspend fun planSplit(db: String, account: String, address: String): SplitRequest {
        val o = JSONObject(call("planSplit", dbArgs(db).apply {
            putString(CoreProvider.KEY_ACCOUNT, account)
            putString(CoreProvider.KEY_ADDRESS, address)
        }))
        return SplitRequest(
            inputNotes = o.getInt("input_notes"),
            inputTotal = o.getLong("input_total"),
            fee = o.getJSONObject("plan").getLong("fee"),
            zip321 = o.getString("zip321"),
        )
    }

    suspend fun inFlightOnlineTxs(db: String): List<String> {
        val a = JSONArray(call("inFlightOnlineTxs", dbArgs(db)))
        return (0 until a.length()).map { a.getString(it) }
    }

    suspend fun payOffline(db: String, seed: ByteArray, account: String, to: String, amount: Long, memo: String?): BuiltPayment {
        val o = JSONObject(call("payOffline", dbArgs(db).apply {
            putByteArray(CoreProvider.KEY_SEED, seed)
            putString(CoreProvider.KEY_ACCOUNT, account)
            putString(CoreProvider.KEY_TO, to)
            putLong(CoreProvider.KEY_AMOUNT, amount)
            putString(CoreProvider.KEY_MEMO, memo)
        }))
        val spent = o.getJSONArray("spent_notes")
        return BuiltPayment(
            txid = o.getString("txid"),
            amount = o.getLong("amount"),
            fee = o.getLong("fee"),
            change = o.getLong("change"),
            spentNotes = (0 until spent.length()).map { spent.getString(it) },
            raw = o.getString("raw_hex").hexToBytes(),
        )
    }

    suspend fun pendingPayments(db: String): List<PendingPayment> {
        val a = JSONArray(call("pendingPayments", dbArgs(db)))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PendingPayment(
                o.getString("txid"), o.getInt("expiry_height"), o.getInt("raw_len"),
                amount = o.getLong("amount"), fee = if (o.isNull("fee")) null else o.getLong("fee"),
            )
        }
    }

    /** Raw bytes of one stored, unmined payment. Throws if it is no longer pending. */
    suspend fun pendingRaw(db: String, txid: String): ByteArray =
        JSONObject(call("pendingRaw", dbArgs(db).apply { putString(CoreProvider.KEY_TXID, txid) }))
            .getString("raw_hex").hexToBytes()

    suspend fun verifyPayment(db: String, raw: ByteArray, account: String?): VerifiedPayment {
        val o = JSONObject(call("verifyPayment", dbArgs(db).apply {
            putByteArray(CoreProvider.KEY_RAW, raw)
            putString(CoreProvider.KEY_ACCOUNT, account)
        }))
        val incoming = o.getJSONArray("incoming")
        val memo = (0 until incoming.length()).map { incoming.getJSONObject(it) }
            .firstNotNullOfOrNull { if (it.isNull("memo")) null else it.getString("memo") }
        val nfs = o.getJSONArray("nullifiers")
        return VerifiedPayment(
            txid = o.getString("txid"),
            amount = o.getLong("amount"),
            memo = memo,
            verified = o.getBoolean("verified"),
            proofsOk = o.getBoolean("proofs_and_signatures_ok"),
            anchorKnown = o.getBoolean("anchor_known"),
            forUs = incoming.length() > 0,
            nullifiers = (0 until nfs.length()).map { nfs.getString(it) },
        )
    }

    suspend fun minedHeight(db: String, txid: String): Int? {
        val o = JSONObject(call("minedHeight", dbArgs(db).apply { putString(CoreProvider.KEY_TXID, txid) }))
        return if (o.isNull("mined_height")) null else o.getInt("mined_height")
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
