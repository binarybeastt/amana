package dev.offlinezec.wallet.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Runs offline-core in its own process (`:core`, see the manifest) and serves it to the app
 * through [call]. One call at a time: the calls share the wallet DB and some write to it.
 *
 * Reply bundle: [KEY_RESULT] on success, or [KEY_ERROR] with the message.
 */
class CoreProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Build the proof verifying keys now, so the first payment received is checked fast.
        Thread {
            synchronized(lock) {
                runCatching { NativeCore.prewarm() }
                    .onSuccess { android.util.Log.i("CoreProvider", "verifier ready in $it") }
                    .onFailure { android.util.Log.w("CoreProvider", "prewarm failed: ${it.message}") }
            }
        }.start()
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val x = extras ?: Bundle()
        fun s(key: String): String = x.getString(key) ?: throw IllegalArgumentException("missing $key")
        val db by lazy { s(KEY_DB) }
        val net by lazy { s(KEY_NETWORK) }
        return synchronized(lock) {
            try {
                val result = when (method) {
                    "version" -> NativeCore.version()
                    "testFailure" -> NativeCore.testFailure(x.getInt(KEY_KIND))
                    "testKillProcess" -> {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        ""
                    }
                    "status" -> NativeCore.status(db, net)
                    "notes" -> NativeCore.notes(db, net)
                    "planSplit" -> NativeCore.planSplit(db, net, s(KEY_ACCOUNT), s(KEY_ADDRESS))
                    "inFlightOnlineTxs" -> NativeCore.inFlightOnlineTxs(db, net)
                    "payOffline" -> NativeCore.payOffline(
                        db, net,
                        x.getByteArray(KEY_SEED) ?: throw IllegalArgumentException("missing seed"),
                        s(KEY_ACCOUNT), s(KEY_TO), x.getLong(KEY_AMOUNT), x.getString(KEY_MEMO),
                    )
                    "pendingPayments" -> NativeCore.pendingPayments(db, net)
                    "verifyPayment" -> NativeCore.verifyPayment(
                        db, net,
                        x.getByteArray(KEY_RAW) ?: throw IllegalArgumentException("missing raw"),
                        x.getString(KEY_ACCOUNT),
                    )
                    "minedHeight" -> NativeCore.minedHeight(db, net, s(KEY_TXID))
                    else -> throw IllegalArgumentException("unknown method $method")
                }
                Bundle().apply { putString(KEY_RESULT, result) }
            } catch (e: Throwable) {
                // Rust errors and panics arrive as NativeCoreException; never crash the process.
                Bundle().apply { putString(KEY_ERROR, "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "dev.offlinezec.wallet.core"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_DB = "db"
        const val KEY_NETWORK = "network"
        const val KEY_KIND = "kind"
        const val KEY_ACCOUNT = "account"
        const val KEY_ADDRESS = "address"
        const val KEY_SEED = "seed"
        const val KEY_TO = "to"
        const val KEY_AMOUNT = "amount"
        const val KEY_MEMO = "memo"
        const val KEY_RAW = "raw"
        const val KEY_TXID = "txid"
        private val lock = Any()
    }
}
