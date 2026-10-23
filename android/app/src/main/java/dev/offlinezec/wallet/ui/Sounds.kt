package dev.offlinezec.wallet.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.offlinezec.wallet.AppController
import dev.offlinezec.wallet.PayState
import dev.offlinezec.wallet.R
import dev.offlinezec.wallet.wallet.PrepareStatus

/** The three cues (synthesized by `stress/p5/make_sounds.py`). */
enum class Cue(val res: Int) { RECEIVED(R.raw.cue_received), SENT(R.raw.cue_sent), PREPARED(R.raw.cue_prepared) }

/** Short notification-style sounds. Silent and vibrate modes play nothing. */
class Sounds(private val ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
    ).build()
    private val ids = Cue.entries.associateWith { pool.load(ctx, it.res, 1) }

    fun play(c: Cue) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(ids.getValue(c), 0.9f, 0.9f, 1, 0, 1f)
    }

    fun release() = pool.release()
}

/**
 * Plays a cue on the moments that matter: a payment signed (sent), a new payment to us (by QR or
 * Bluetooth), and a split mined into offline cash. Changes seen at first composition stay quiet.
 */
@Composable
fun PaymentSounds(c: AppController) {
    val ctx = LocalContext.current
    val sounds = remember { Sounds(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { sounds.release() } }

    val pay by c.pay.collectAsState()
    var lastPay by remember { mutableStateOf(pay) }
    LaunchedEffect(pay) {
        if (lastPay is PayState.Building && pay is PayState.Ready) sounds.play(Cue.SENT)
        lastPay = pay
    }

    val received by c.receivedPayments.collectAsState()
    var known by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(received) {
        val now = received.map { it.txid }.toSet()
        // The list loads from disk after start: only payments that just arrived count.
        val fresh = received.filter { it.txid !in (known ?: now) && it.receivedAt > c.now() - 60_000 }
        if (fresh.isNotEmpty()) sounds.play(Cue.RECEIVED)
        known = now
    }

    val prep by c.prepare.collectAsState()
    var lastPrep by remember { mutableStateOf(prep) }
    LaunchedEffect(prep) {
        if (lastPrep is PrepareStatus.Waiting && prep is PrepareStatus.Idle) sounds.play(Cue.PREPARED)
        lastPrep = prep
    }
}
