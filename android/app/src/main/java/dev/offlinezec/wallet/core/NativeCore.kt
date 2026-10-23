package dev.offlinezec.wallet.core

/**
 * JNI bindings to offline-core (liboffline_core.so). Call only from the `:core` process
 * ([CoreProvider]): this library bundles its own SQLite, and two SQLite copies in one process
 * cannot see each other's file locks, so sharing the SDK's process could corrupt the wallet DB.
 *
 * Every function returns JSON or a string, and throws [NativeCoreException] on any Rust error
 * or panic.
 */
object NativeCore {
    init {
        System.loadLibrary("offline_core")
    }

    @JvmStatic external fun version(): String

    /** Test hook: 0 = Rust error, 1 = Rust panic. Always throws. */
    @JvmStatic external fun testFailure(kind: Int): String

    @JvmStatic external fun status(db: String, network: String): String

    @JvmStatic external fun notes(db: String, network: String): String

    @JvmStatic external fun planSplit(db: String, network: String, account: String, address: String): String

    @JvmStatic external fun inFlightOnlineTxs(db: String, network: String): String

    @JvmStatic external fun payOffline(
        db: String,
        network: String,
        seed: ByteArray,
        account: String,
        to: String,
        amount: Long,
        memo: String?,
    ): String

    @JvmStatic external fun pendingPayments(db: String, network: String): String

    @JvmStatic external fun pendingRaw(db: String, network: String, txid: String): String

    @JvmStatic external fun verifyPayment(db: String, network: String, raw: ByteArray, account: String?): String

    @JvmStatic external fun prewarm(): String

    @JvmStatic external fun minedHeight(db: String, network: String, txid: String): String
}

/** A Rust error or panic inside offline-core, carried across JNI. */
class NativeCoreException(message: String) : RuntimeException(message)
