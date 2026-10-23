package dev.offlinezec.wallet.wallet

import android.content.Context
import dev.offlinezec.wallet.core.PendingPayment
import dev.offlinezec.wallet.zec

/**
 * P4.G3 offline caps, chosen by the user; off by default (user's call, 2026-10-23). Amounts in
 * zatoshis, as entered on the Pay screen (fees not counted).
 */
data class Caps(
    val enabled: Boolean = false,
    val perPayment: Long = DEFAULT_PER_PAYMENT,
    val perSession: Long = DEFAULT_PER_SESSION,
) {
    companion object {
        const val DEFAULT_PER_PAYMENT = 10_000_000L // 0.1 ZEC
        const val DEFAULT_PER_SESSION = 50_000_000L // 0.5 ZEC
    }
}

/** Thrown instead of signing a payment the caps forbid. Nothing is built or stored. */
class LimitException(message: String) : Exception(message)

/**
 * The cap rules, kept pure so they can be tested on the JVM. The session total is derived from
 * the wallet DB every time (our stored, unmined offline payments), never from a counter: a crash
 * cannot reset it, coming online does not reset it, and each payment drops out only once mined.
 */
object Limits {
    /** What this phone has paid offline that the network has not settled yet. */
    fun unsettled(pending: List<PendingPayment>): Long = pending.filter { it.offline }.sumOf { it.amount }

    /** Why the sender must not sign [amount], or null if the caps allow it (or are off). */
    fun refusal(caps: Caps, pending: List<PendingPayment>, amount: Long): String? {
        if (!caps.enabled) return null
        if (amount > caps.perPayment) {
            return "Over your offline limit of ${zec(caps.perPayment)} ZEC per payment. Change it under Offline limits."
        }
        val used = unsettled(pending)
        if (used + amount > caps.perSession) {
            return "Over your offline session limit: ${zec(used)} of ${zec(caps.perSession)} ZEC already paid offline and " +
                "not yet settled, so at most ${zec((caps.perSession - used).coerceAtLeast(0))} more. " +
                "It frees up as those payments are mined."
        }
        return null
    }

    /** The receiver's warning for an incoming [amount] above its own per-payment cap, or null. */
    fun warning(caps: Caps, amount: Long): String? =
        if (caps.enabled && amount > caps.perPayment) {
            "Above your offline limit of ${zec(caps.perPayment)} ZEC per payment. An offline payment is not final " +
                "until it settles; a large one is more to lose if the payer double spends."
        } else {
            null
        }
}

/** The user's caps, in shared preferences. */
class CapsStore(context: Context) {
    private val prefs = context.getSharedPreferences("limits", Context.MODE_PRIVATE)

    fun get() = Caps(
        enabled = prefs.getBoolean("enabled", false),
        perPayment = prefs.getLong("per_payment", Caps.DEFAULT_PER_PAYMENT),
        perSession = prefs.getLong("per_session", Caps.DEFAULT_PER_SESSION),
    )

    fun set(c: Caps) {
        prefs.edit().putBoolean("enabled", c.enabled).putLong("per_payment", c.perPayment)
            .putLong("per_session", c.perSession).apply()
    }
}
