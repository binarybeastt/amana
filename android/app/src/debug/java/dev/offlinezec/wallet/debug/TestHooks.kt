package dev.offlinezec.wallet.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.offlinezec.wallet.App
import dev.offlinezec.wallet.core.hexToBytes
import dev.offlinezec.wallet.wallet.Caps
import dev.offlinezec.wallet.wallet.WalletState
import dev.offlinezec.wallet.wallet.toJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only: lets adb test scripts drive the app and read results back.
 *
 *   adb shell am broadcast -n dev.offlinezec.wallet/.debug.TestHooks -a <ACTION> [--es k v ...]
 *
 * Results land in the app's files dir (state.json is refreshed every 3 s), read with
 * `adb exec-out run-as dev.offlinezec.wallet cat files/<name>`.
 */
class TestHooks : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = App.controller(context)
        val files = context.filesDir
        Log.i(TAG, "hook ${intent.action}")
        // Return at once and work in the background: a broadcast held open longer than ~10 s
        // is an ANR. Results go to files the scripts poll.
        c.scope.launch {
            try {
                when (intent.action) {
                    "CREATE" -> c.createWallet().join()
                    "RESTORE" -> c.restoreWallet(intent.getStringExtra("words")!!, intent.getStringExtra("birthday")!!.toLong()).join()
                    "DUMP_SEED" -> File(files, "mnemonic.txt").writeText(c.wallet.mnemonicForTests() ?: "")
                    "PREPARE" -> c.prepareForOffline()
                    "PAY" -> c.payOffline(intent.getStringExtra("to")!!, intent.getStringExtra("zats")!!.toLong(), intent.getStringExtra("memo"))
                    "CLEAR_PAY" -> c.clearPay()
                    // P4.G3: --es on true|false [--es per_payment zats] [--es per_session zats]
                    "CAPS" -> c.capsState.value.let { old ->
                        c.setCaps(Caps(
                            enabled = intent.getStringExtra("on")?.toBoolean() ?: old.enabled,
                            perPayment = intent.getStringExtra("per_payment")?.toLong() ?: old.perPayment,
                            perSession = intent.getStringExtra("per_session")?.toLong() ?: old.perSession,
                        ))
                    }
                    // P4.G1: stored, unmined payments with raw bytes to files/pending.json; show one's QR again.
                    "DUMP_PENDING" -> c.dumpPending()
                    "SHOW_PENDING" -> c.showPending(intent.getStringExtra("txid")!!).join()
                    "DUMP_FRAMES" -> c.dumpFrames(intent.getStringExtra("count")?.toInt() ?: 0)
                    "RESET_RECEIVE" -> c.resetReceive().join()
                    // Frames pushed into files/in_frames.txt, one per line, fed as if scanned.
                    "FEED_FRAMES" -> {
                        val skip = intent.getStringExtra("skip")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
                        File(files, "in_frames.txt").readLines().filter { it.isNotBlank() }.forEachIndexed { i, f ->
                            if (i !in skip) c.onFrame(f).join()
                        }
                    }
                    "BROADCAST" -> c.broadcastNow().join()
                    "REFRESH" -> c.refresh()
                    "CORE_STATUS" -> {
                        val w = (c.wallet.state.first { it is WalletState.Ready } as WalletState.Ready).wallet
                        File(files, "core_status.json").writeText(c.wallet.core.statusJson(w.dbPath).toString(2))
                        File(files, "core_notes.json").writeText(c.wallet.core.notes(w.dbPath).toJson().toString(2))
                    }
                    // Mesh: a tx from files/in_tx.hex enters the relay exactly as one from a peer
                    // would (verified first). Result in files/inject_result.json.
                    "RELAY_INJECT" -> {
                        val raw = File(files, intent.getStringExtra("file") ?: "in_tx.hex").readText().trim().hexToBytes()
                        val hops = intent.getStringExtra("hops")?.toInt() ?: 1
                        val r = c.mesh.relay.ingest(intent.getStringExtra("txid"), hops, raw, intent.getStringExtra("from") ?: "test")
                        val out = JSONObject().put("status", r.wire).put("txid", r.txid).put("reason", r.reason ?: JSONObject.NULL)
                            .put("at", System.currentTimeMillis())
                        val tmp = File(files, "inject_result.json.tmp")
                        tmp.writeText(out.toString())
                        tmp.renameTo(File(files, "inject_result.json"))
                    }
                    "RELAY_CLEAR" -> c.mesh.queue.clear()
                    // P4.G4: a simulated chain fact, "lightwalletd says <txid> was mined at <height>",
                    // applied exactly as the broadcaster applies a real one (queue settled → nullifier set).
                    "RELAY_SETTLE" -> {
                        c.mesh.queue.markSettled(intent.getStringExtra("txid")!!, intent.getStringExtra("height")?.toLong())
                        c.refresh()
                    }
                    "RELAY_REMOVE" -> c.mesh.queue.forget(intent.getStringExtra("txid")!!)
                    // P4.G4: forget a received payment (test clean-up: a fixture must never be carried online).
                    "RECEIVED_DELETE" -> File(files, "received/${intent.getStringExtra("txid")!!}.json").delete()
                    // P4.G2: forget nullifier claims and conflict alerts (start a test clean).
                    "CONFLICTS_CLEAR" -> c.mesh.nullifiers.clear()
                    "MESH_STOP" -> c.mesh.ble.stop("test hook")
                    "MESH_START" -> c.mesh.permissionsChanged()
                    // P3.G2.T3 on devices: speak another protocol version (restarts Bluetooth).
                    "MESH_VERSION" -> {
                        c.mesh.ble.stop("version change")
                        c.mesh.ble.protoVersion = intent.getStringExtra("v")!!.toInt()
                        c.mesh.permissionsChanged()
                    }
                    "CORE_VERSION" -> File(files, "core_version.txt").writeText(c.wallet.core.version())
                    // P2.G1.T3: 0 = Rust error, 1 = Rust panic, 2 = core process killed.
                    "CORE_FAIL" -> {
                        val kind = intent.getStringExtra("kind")!!.toInt()
                        val out = try {
                            "no exception: " + c.wallet.core.testFailure(kind)
                        } catch (e: Exception) {
                            "exception ${e.javaClass.simpleName}: ${e.message}"
                        }
                        File(files, "core_fail_$kind.txt").writeText(out)
                    }
                    else -> Log.w(TAG, "unknown hook ${intent.action}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "hook ${intent.action} failed", e)
                File(files, "hook_error.txt").writeText("${intent.action}: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "TestHooks"
    }
}
