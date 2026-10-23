package dev.offlinezec.wallet

import android.app.Application
import android.util.Log
import dev.offlinezec.wallet.core.BuiltPayment
import dev.offlinezec.wallet.core.PendingPayment
import dev.offlinezec.wallet.core.toHex
import dev.offlinezec.wallet.mesh.Mesh
import dev.offlinezec.wallet.mesh.Nullifiers
import dev.offlinezec.wallet.mesh.RelayQueue
import dev.offlinezec.wallet.qr.FrameDecoder
import dev.offlinezec.wallet.wallet.Payer
import dev.offlinezec.wallet.wallet.PrepareStatus
import dev.offlinezec.wallet.wallet.Preparer
import dev.offlinezec.wallet.wallet.ReceiveResult
import dev.offlinezec.wallet.wallet.Received
import dev.offlinezec.wallet.wallet.ReceivedStore
import dev.offlinezec.wallet.wallet.Receiver
import dev.offlinezec.wallet.wallet.Settler
import dev.offlinezec.wallet.wallet.WalletManager
import dev.offlinezec.wallet.wallet.WalletState
import dev.offlinezec.wallet.wallet.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the home screen shows about the Bluetooth relay. */
data class MeshInfo(
    val bluetooth: Boolean = false,
    val permitted: Boolean = false,
    val running: Boolean = false,
    val online: Boolean = false,
    val peers: Int = 0,
    val queue: List<RelayQueue.Entry> = emptyList(),
    /** Double spends seen (P4.G2): two txids spending one nullifier. */
    val conflicts: List<Nullifiers.Conflict> = emptyList(),
)

/** What the Pay flow is doing. */
sealed interface PayState {
    data object Idle : PayState
    data object Building : PayState
    /** Stored (notes spent-offline); now safe to show as an animated QR. */
    data class Ready(val payment: BuiltPayment) : PayState
    data class Failed(val error: String) : PayState
}

/** What the Receive flow is doing. */
sealed interface ReceiveState {
    data class Scanning(val progress: Double) : ReceiveState
    data object Verifying : ReceiveState
    data class Accepted(val payment: Received) : ReceiveState
    data class Rejected(val reason: String) : ReceiveState
}

/**
 * The app's single owner of wallet work, shared by the UI and the debug test hooks. Lives as
 * long as the process.
 */
class AppController(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val wallet = WalletManager(app, scope)
    val received = ReceivedStore(app)
    val preparer = Preparer(wallet)
    val payer = Payer(wallet)
    val receiver = Receiver(wallet, received)
    val settler = Settler(wallet, received)
    val mesh = Mesh(app, scope, wallet, received)

    private val _prepare = MutableStateFlow<PrepareStatus?>(null)
    val prepare: StateFlow<PrepareStatus?> = _prepare
    private val _prepareBusy = MutableStateFlow(false)
    val prepareBusy: StateFlow<Boolean> = _prepareBusy
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _pay = MutableStateFlow<PayState>(PayState.Idle)
    val pay: StateFlow<PayState> = _pay

    private val decoder = FrameDecoder()
    private val decodeLock = Mutex()
    private val _receive = MutableStateFlow<ReceiveState>(ReceiveState.Scanning(0.0))
    val receive: StateFlow<ReceiveState> = _receive

    private val _payments = MutableStateFlow<List<Received>>(emptyList())
    val receivedPayments: StateFlow<List<Received>> = _payments
    private val _pendingSent = MutableStateFlow<List<PendingPayment>>(emptyList())
    val pendingSent: StateFlow<List<PendingPayment>> = _pendingSent

    private val _meshInfo = MutableStateFlow(MeshInfo())
    val meshInfo: StateFlow<MeshInfo> = _meshInfo

    /** Debug: what the notes looked like the moment the payment QR was first shown. */
    @Volatile var notesAtQr: JSONObject? = null

    fun start() {
        // A state file from an earlier process would look current to the test scripts.
        File(app.filesDir, "state.json").delete()
        mesh.start()
        scope.launch {
            wallet.start()
            refreshLoop()
        }
    }

    /** Refreshes derived state every few seconds; broadcasts pending txs whenever online. */
    private suspend fun refreshLoop() {
        var lastBroadcast = 0L
        while (true) {
            try {
                if (wallet.wallet != null) {
                    refresh()
                    val online = wallet.sync.value.networkHeight != null &&
                        wallet.sync.value.status in setOf("SYNCING", "SYNCED")
                    val now = System.currentTimeMillis()
                    if (online && now - lastBroadcast > BROADCAST_EVERY_MS && settler.outgoing().isNotEmpty()) {
                        lastBroadcast = now
                        settler.broadcastAll()
                        refresh()
                    }
                }
                _meshInfo.value = MeshInfo(
                    bluetooth = mesh.bluetoothOn(), permitted = Mesh.hasBluetoothPermissions(app), running = mesh.ble.running,
                    online = mesh.online.value, peers = mesh.ble.peers().size, queue = mesh.queue.all(),
                    conflicts = mesh.nullifiers.conflicts(),
                )
                dumpState()
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed: ${e.message}")
            }
            delay(REFRESH_MS)
        }
    }

    suspend fun refresh() {
        val w = wallet.wallet ?: return
        _prepare.value = preparer.status()
        settler.refreshSettled()
        _payments.value = received.all()
        val pending = wallet.core.pendingPayments(w.dbPath)
        _pendingSent.value = pending
        // A payment stored just before a crash never reached the relay queue: carry it now.
        for (p in pending.filter { it.offline && !mesh.queue.contains(it.txid) }) {
            mesh.addOwn(p.txid, wallet.core.pendingRaw(w.dbPath, p.txid))
            Log.i(TAG, "RECOVERED txid=${p.txid}: stored payment added to the relay queue")
        }
        // Our own and received payments: the wallet sees them mined when it syncs.
        for (e in mesh.queue.unsettled().filter { it.mine || it.forUs }) {
            wallet.core.minedHeight(w.dbPath, e.txid)?.let { mesh.queue.markSettled(e.txid, it.toLong()) }
        }
    }

    // ---- Wallet ----

    fun createWallet() = scope.launch { runCatching { wallet.create() }.onFailure { say("Create failed: ${it.message}") } }

    fun restoreWallet(mnemonic: String, birthday: Long) =
        scope.launch { runCatching { wallet.restore(mnemonic, birthday) }.onFailure { say("Restore failed: ${it.message}") } }

    // ---- Prepare ----

    /** Safe to call any number of times: at most one split is ever in flight. */
    fun prepareForOffline() {
        if (!_prepareBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val txid = preparer.prepare()
                say("Split sent: ${txid.take(16)}…")
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "prepare failed", e)
                say("Prepare failed: ${e.message}")
            } finally {
                _prepareBusy.value = false
            }
        }
    }

    // ---- Pay ----

    fun payOffline(to: String, amountZats: Long, memo: String?) {
        if (_pay.value is PayState.Building) return
        _pay.value = PayState.Building
        scope.launch {
            _pay.value = try {
                val built = payer.pay(to, amountZats, memo)
                Log.i(TAG, "paid ${built.amount} zats, txid ${built.txid}, notes ${built.spentNotes}")
                // Stored (write-before-send): now hand it to every phone in Bluetooth range.
                mesh.addOwn(built.txid, built.raw)
                PayState.Ready(built)
            } catch (e: Exception) {
                Log.e(TAG, "pay failed", e)
                PayState.Failed(e.message ?: e.javaClass.simpleName)
            }
            refresh()
        }
    }

    /** Shows a stored, unmined payment's QR again (e.g. the app died before showing it). */
    fun showPending(txid: String) = scope.launch {
        val w = wallet.wallet ?: return@launch
        val p = wallet.core.pendingPayments(w.dbPath).firstOrNull { it.txid == txid && it.offline } ?: run {
            say("Payment ${txid.take(12)}… is no longer pending")
            return@launch
        }
        val spent = wallet.core.notes(w.dbPath).filter { it.spentBy == txid }.map { it.key }
        notesAtQr = null
        val raw = wallet.core.pendingRaw(w.dbPath, txid)
        _pay.value = PayState.Ready(BuiltPayment(p.txid, p.amount, p.fee ?: 0, change = 0, spentNotes = spent, raw = raw))
    }

    fun clearPay() {
        _pay.value = PayState.Idle
        notesAtQr = null
    }

    /** Called by the QR screen once it is first on screen: records the spent notes' statuses. */
    fun onPaymentQrShown(p: BuiltPayment) = scope.launch {
        val w = wallet.wallet ?: return@launch
        val notes = wallet.core.notes(w.dbPath).filter { it.key in p.spentNotes }
        notesAtQr = JSONObject().put("txid", p.txid).put("shown_at", System.currentTimeMillis())
            .put("spent_notes", notes.toJson())
        Log.i(TAG, "QR_SHOWN txid=${p.txid} spent_notes=${notes.map { "${it.key}=${it.status}" }}")
    }

    // ---- Receive ----

    fun resetReceive() = scope.launch {
        decodeLock.withLock {
            decoder.reset()
            _receive.value = ReceiveState.Scanning(0.0)
        }
    }

    /** One scanned QR frame (camera or test hook). */
    fun onFrame(text: String) = scope.launch {
        decodeLock.withLock {
            if (_receive.value !is ReceiveState.Scanning) return@withLock
            when (val st = decoder.receive(text)) {
                is FrameDecoder.State.Collecting -> _receive.value = ReceiveState.Scanning(decoder.progress)
                is FrameDecoder.State.Failed -> _receive.value = ReceiveState.Rejected(st.error)
                is FrameDecoder.State.Done -> {
                    _receive.value = ReceiveState.Verifying
                    _receive.value = when (val r = receiver.receive(st.payload)) {
                        is ReceiveResult.Accepted -> {
                            mesh.addReceived(r.payment.txid, r.payment.raw, r.check.nullifiers)
                            ReceiveState.Accepted(r.payment)
                        }
                        is ReceiveResult.Rejected -> ReceiveState.Rejected(r.reason)
                    }
                    decoder.reset()
                    refresh()
                }
            }
        }
    }

    fun broadcastNow() = scope.launch {
        val r = settler.broadcastAll()
        say(if (r.isEmpty()) "Nothing to broadcast" else r.entries.joinToString("\n") { "${it.key.take(12)}…: ${it.value}" })
        refresh()
    }

    fun say(m: String?) {
        _message.value = m
    }

    // ---- Debug state dump (read by the adb test scripts) ----

    private fun dumpState() {
        if (!BuildConfig.DEBUG) return
        val o = JSONObject().put("updated_at", System.currentTimeMillis())
        val st = wallet.state.value
        o.put("wallet", when (st) {
            is WalletState.Ready -> "ready"
            is WalletState.Opening -> "opening"
            WalletState.NoWallet -> "none"
            WalletState.Loading -> "loading"
            is WalletState.Failed -> "failed: ${st.error}"
        })
        wallet.wallet?.let { w ->
            val s = wallet.sync.value
            o.put("account", w.accountUuid).put("address", w.address).put("db", w.dbPath)
                .put("birthday", wallet.birthday() ?: JSONObject.NULL)
                .put("sync", JSONObject().put("status", s.status).put("progress", s.progress.toDouble())
                    .put("scanned", s.scannedHeight ?: JSONObject.NULL).put("network", s.networkHeight ?: JSONObject.NULL)
                    .put("ironwood_available", s.ironwoodAvailable).put("ironwood_total", s.ironwoodTotal)
                    .put("orchard_total", s.orchardTotal).put("sapling_total", s.saplingTotal))
        }
        when (val p = _prepare.value) {
            is PrepareStatus.Waiting -> o.put("prepare", JSONObject().put("state", "waiting").put("txid", p.txid))
            is PrepareStatus.Idle -> o.put("prepare", JSONObject().put("state", if (p.ready) "ready" else "idle")
                .put("notes", p.notes.toJson()))
            null -> {}
        }
        o.put("prepare_busy", _prepareBusy.value)
        when (val p = _pay.value) {
            is PayState.Ready -> o.put("pay", JSONObject().put("state", "ready").put("txid", p.payment.txid)
                .put("amount", p.payment.amount).put("spent_notes", JSONArray(p.payment.spentNotes)))
            is PayState.Failed -> o.put("pay", JSONObject().put("state", "failed").put("error", p.error))
            PayState.Building -> o.put("pay", JSONObject().put("state", "building"))
            PayState.Idle -> o.put("pay", JSONObject().put("state", "idle"))
        }
        notesAtQr?.let { o.put("notes_at_qr", it) }
        o.put("receive", when (val r = _receive.value) {
            is ReceiveState.Scanning -> JSONObject().put("state", "scanning").put("progress", r.progress)
            ReceiveState.Verifying -> JSONObject().put("state", "verifying")
            is ReceiveState.Accepted -> JSONObject().put("state", "accepted").put("txid", r.payment.txid)
                .put("amount", r.payment.amount).put("label", receivedLabel(r.payment))
            is ReceiveState.Rejected -> JSONObject().put("state", "rejected").put("reason", r.reason)
        })
        o.put("received", JSONArray().also { a ->
            _payments.value.forEach { r ->
                a.put(JSONObject().put("txid", r.txid).put("amount", r.amount).put("settled", r.settled)
                    .put("mined_height", r.minedHeight ?: JSONObject.NULL).put("label", receivedLabel(r)))
            }
        })
        o.put("pending_sent", JSONArray(_pendingSent.value.map { it.txid }))
        o.put("mesh", meshJson())
        o.put("conflicts", JSONArray(mesh.nullifiers.conflicts().map(Nullifiers::toJson)))
        val f = File(app.filesDir, "state.json")
        val tmp = File(app.filesDir, "state.json.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(f)
    }

    fun meshJson(): JSONObject {
        val now = System.currentTimeMillis()
        val b = mesh.ble
        return JSONObject().put("node", mesh.nodeId).put("ble_running", b.running).put("bt_on", mesh.bluetoothOn())
            .put("permissions", Mesh.hasBluetoothPermissions(app)).put("online", mesh.online.value)
            .put("connectivity_changes", mesh.connectivityChanges).put("ble_error", b.lastError ?: JSONObject.NULL)
            .put("backoff_ms", mesh.broadcaster.lastBackoffMs)
            .put("stats", JSONObject().put("sent", b.stats.sent).put("received", b.stats.received)
                .put("refused", b.stats.refused).put("sessions", b.stats.sessions).put("failed_sessions", b.stats.failedSessions))
            .put("peers", JSONArray().also { a ->
                b.peers().forEach { p ->
                    a.put(JSONObject().put("node", p.node).put("v", p.version).put("seen_ms_ago", now - p.lastSeen)
                        .put("first_seen", p.firstSeen).put("last_sync", p.lastSync ?: JSONObject.NULL)
                        .put("last_result", p.lastResult ?: JSONObject.NULL))
                }
            })
            .put("nullifiers", mesh.nullifiers.size()).put("settled_nullifiers", mesh.nullifiers.settledCount())
            .put("queue", JSONArray().also { a -> mesh.queue.all().forEach { a.put(RelayQueue.toJson(it, withRaw = false)) } })
    }

    /** Debug only: writes the current payment's first frames for a test to replay elsewhere. */
    fun dumpFrames(count: Int) {
        val p = (_pay.value as? PayState.Ready)?.payment ?: return
        val enc = dev.offlinezec.wallet.qr.FrameEncoder(p.raw)
        // Write then rename, so a reader never sees half a file.
        File(app.filesDir, "qr_raw.hex").writeText(p.raw.toHex())
        val tmp = File(app.filesDir, "qr_frames.txt.tmp")
        tmp.writeText((1..count.coerceAtLeast(enc.seqLen)).joinToString("\n") { enc.next() })
        tmp.renameTo(File(app.filesDir, "qr_frames.txt"))
    }

    /** Debug only: stored, unmined payments with their raw bytes, to files/pending.json. */
    suspend fun dumpPending() {
        val w = wallet.wallet ?: return
        val a = JSONArray()
        wallet.core.pendingPayments(w.dbPath).forEach { p ->
            a.put(JSONObject().put("txid", p.txid).put("expiry_height", p.expiryHeight).put("amount", p.amount)
                .put("fee", p.fee ?: JSONObject.NULL).put("raw_hex", wallet.core.pendingRaw(w.dbPath, p.txid).toHex()))
        }
        val tmp = File(app.filesDir, "pending.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(File(app.filesDir, "pending.json"))
    }

    companion object {
        private const val TAG = "AppController"
        const val REFRESH_MS = 3_000L
        const val BROADCAST_EVERY_MS = 30_000L
    }
}

fun receivedLabel(r: Received): String =
    if (r.settled) "settled" else "received, not yet settled"

fun zec(zats: Long): String {
    val s = "%d.%08d".format(zats / 100_000_000, zats % 100_000_000)
    return s.trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
}
