package dev.offlinezec.wallet.wallet

import android.content.Context

/**
 * P5.G3: a restored seed may still live on another phone, and two phones paying offline from
 * one seed spend the same notes (a double spend, of which at most one settles). The warning
 * comes up twice and blocks the app until acknowledged: right after the restore, and the first
 * time the phone is offline afterwards (when paying offline is about to become possible).
 */
enum class SeedWarning { RESTORED, OFFLINE }

data class SeedWarningFlags(val restored: Boolean = false, val ackRestore: Boolean = false, val ackOffline: Boolean = false)

/** Which warning to show now, if any. */
fun seedWarning(f: SeedWarningFlags, online: Boolean): SeedWarning? = when {
    !f.restored -> null
    !f.ackRestore -> SeedWarning.RESTORED
    !f.ackOffline && !online -> SeedWarning.OFFLINE
    else -> null
}

class SeedWarningStore(context: Context) {
    private val prefs = context.getSharedPreferences("seed_warning", Context.MODE_PRIVATE)

    fun get() = SeedWarningFlags(prefs.getBoolean(RESTORED, false), prefs.getBoolean(ACK_RESTORE, false), prefs.getBoolean(ACK_OFFLINE, false))

    /** A new restore starts both warnings over. */
    fun restored() {
        prefs.edit().putBoolean(RESTORED, true).putBoolean(ACK_RESTORE, false).putBoolean(ACK_OFFLINE, false).commit()
    }

    /** A new wallet of our own: nothing to warn about. */
    fun clear() {
        prefs.edit().clear().commit()
    }

    fun acknowledge(w: SeedWarning) {
        prefs.edit().putBoolean(if (w == SeedWarning.RESTORED) ACK_RESTORE else ACK_OFFLINE, true).commit()
    }

    private companion object {
        const val RESTORED = "restored"
        const val ACK_RESTORE = "ack_restore"
        const val ACK_OFFLINE = "ack_offline"
    }
}
