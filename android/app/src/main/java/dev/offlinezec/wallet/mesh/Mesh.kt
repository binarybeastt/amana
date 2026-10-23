package dev.offlinezec.wallet.mesh

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.offlinezec.wallet.core.toHex
import dev.offlinezec.wallet.wallet.Net
import dev.offlinezec.wallet.wallet.Received
import dev.offlinezec.wallet.wallet.ReceivedStore
import dev.offlinezec.wallet.wallet.WalletManager
import dev.offlinezec.wallet.wallet.WalletState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom

/**
 * The mesh as the app sees it: the relay queue, Bluetooth (on whenever the radio is), the
 * connectivity watcher that broadcasts the queue on any connection, and the foreground service
 * that keeps it all running with the screen off. Lives as long as the main process.
 */
class Mesh(
    private val app: Application,
    private val scope: CoroutineScope,
    private val wallet: WalletManager,
    private val received: ReceivedStore,
) {
    val nodeId: String = loadNodeId(app)
    val queue = RelayQueue(File(app.filesDir, "relay"))
    val relay = Relay(queue, verify = ::verify, onForUs = ::fileReceived)
    val nullifiers: Nullifiers = Nullifiers(File(app.filesDir, "conflicts.json"), onConflict = { c ->
        Log.w(TAG, "CONFLICT nullifier=${c.nullifier} txids=${c.txids.joinToString(",")} verified=${c.verified} settled=${c.settled ?: "-"}")
        ble.poke()
    })
    val ble: BleMesh = BleMesh(app, nodeId, Net.CORE_NETWORK, queue, relay, nullifiers)
    val broadcaster = Broadcaster(queue, node = { currentNode() }, log = { Log.i(TAG, it) })

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    @Volatile var connectivityChanges = 0
        private set

    private var node: LwdNode? = null
    @Volatile private var started = false

    init {
        // The gossip set starts from the queue: unsettled txs claim their nullifiers, settled ones are spent.
        for (e in queue.all()) if (e.state == RelayQueue.State.SETTLED) nullifiers.settle(e.txid, e.nullifiers) else nullifiers.local(e.txid, e.nullifiers)
        queue.onSettled = { nullifiers.settle(it.txid, it.nullifiers) }
        queue.onAdded = {
            nullifiers.local(it.txid, it.nullifiers)
            ble.poke()
            broadcaster.wake()
        }
    }

    /** Starts once the wallet is open (relays verify against it). Safe to call repeatedly. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            wallet.state.first { it is WalletState.Ready }
            backfillNullifiers()
            watchBluetooth()
            watchConnectivity()
            startBle()
            MeshService.start(app)
        }
    }

    /** Bluetooth permission was just granted: try again. */
    fun permissionsChanged() {
        startBle()
        MeshService.start(app)
    }

    private fun startBle() {
        if (wallet.wallet == null) return // start() does it once the wallet is open
        if (!hasBluetoothPermissions(app)) {
            Log.w(TAG, "no Bluetooth permission; mesh off")
            return
        }
        if (bluetoothOn()) ble.start()
    }

    fun bluetoothOn(): Boolean = runCatching {
        app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    /** Our own payment, signed and stored: carry it to every peer. */
    fun addOwn(txid: String, raw: ByteArray, nfs: List<String> = emptyList()) {
        val added = queue.add(
            RelayQueue.Entry(txid, raw, hops = 0, from = "self", firstSeen = System.currentTimeMillis(), mine = true, nullifiers = nfs),
            force = true,
        )
        if (added == RelayQueue.AddResult.ADDED && nfs.isEmpty()) scope.launch { backfillNullifiers() }
    }

    private val backfill = Mutex()

    /** Own payments enter the queue without their nullifiers: read them from the bytes (in `:core`). */
    private suspend fun backfillNullifiers() = backfill.withLock {
        for (e in queue.unsettled().filter { it.nullifiers.isEmpty() }) {
            val v = try {
                verify(e.raw)
            } catch (x: Exception) {
                Log.w(TAG, "could not read nullifiers of ${e.txid}: ${x.message}")
                continue
            }
            queue.update(e.txid) { it.copy(nullifiers = v.nullifiers) }?.let { nullifiers.local(it.txid, it.nullifiers) }
            Log.i(TAG, "NULLIFIERS txid=${e.txid} n=${v.nullifiers.size}")
        }
    }

    /** A payment to us that arrived some other way (QR): carry it too. */
    fun addReceived(txid: String, raw: ByteArray, nullifiers: List<String>) {
        queue.add(
            RelayQueue.Entry(txid, raw, hops = 1, from = "qr", firstSeen = System.currentTimeMillis(), forUs = true, nullifiers = nullifiers),
            force = true,
        )
    }

    private suspend fun verify(raw: ByteArray): Verdict {
        val w = wallet.wallet ?: error("no wallet open")
        val v = wallet.core.verifyPayment(w.dbPath, raw, w.accountUuid)
        return Verdict(v.txid, v.proofsOk, v.forUs, v.anchorKnown, v.nullifiers, v.amount, v.memo)
    }

    private suspend fun fileReceived(raw: ByteArray, v: Verdict) {
        if (!v.anchorKnown) {
            // Proofs are good, so we relay it, but we cannot vouch for it until this phone syncs.
            Log.w(TAG, "payment ${v.txid} for us has an anchor we have not synced; relaying only")
            return
        }
        if (received.get(v.txid) == null) {
            received.put(Received(v.txid, v.amount, v.memo, System.currentTimeMillis(), raw))
            Log.i(TAG, "RECEIVED txid=${v.txid} amount=${v.amount} via=bluetooth")
        }
    }

    // ---- Bluetooth on/off

    private fun watchBluetooth() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> startBle()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> ble.stop("bluetooth off")
                }
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- Connectivity: broadcast the queue whenever there is a network

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchConnectivity() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        // Only the current default network counts. When the default switches (cell → Wi-Fi)
        // the callback gets the new one but no onLost for the old, so a set of networks would
        // keep a stale entry and stay "online" in airplane mode (found by P3.G4.T1).
        val lock = Any()
        var current: Network? = null
        fun publish(up: Boolean) {
            if (up != _online.value) {
                connectivityChanges++
                Log.i(TAG, "CONNECTIVITY online=$up")
                _online.value = up
            }
        }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                synchronized(lock) {
                    current = n
                    publish(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }

            override fun onLost(n: Network) {
                synchronized(lock) {
                    if (n == current) {
                        current = null
                        publish(false)
                    }
                }
            }
        })
        // One broadcaster run per connection: collectLatest cancels it when the network goes.
        scope.launch {
            _online.collectLatest { up ->
                if (!up) return@collectLatest
                freshNode()
                broadcaster.run { _online.value }
            }
        }
    }

    @Synchronized
    private fun freshNode(): LwdNode {
        node?.dispose()
        return LwdNode(app, Net.endpoint).also { node = it }
    }

    @Synchronized
    private fun currentNode(): ChainNode = node ?: freshNode()

    companion object {
        private const val TAG = "MESH"

        fun hasBluetoothPermissions(c: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
        }

        val PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        private fun loadNodeId(c: Context): String {
            val prefs = c.getSharedPreferences("mesh", Context.MODE_PRIVATE)
            prefs.getString("node_id", null)?.let { return it }
            val id = ByteArray(Proto.NODE_ID_BYTES).also { SecureRandom().nextBytes(it) }.toHex()
            prefs.edit().putString("node_id", id).commit()
            return id
        }
    }
}
