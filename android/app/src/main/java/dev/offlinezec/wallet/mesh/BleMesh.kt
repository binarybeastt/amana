package dev.offlinezec.wallet.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The Bluetooth LE side of the mesh. Every phone is both:
 *  - a GATT **server** that advertises [Proto.SERVICE] (with its protocol version and node id
 *    in the scan response) and answers HELLO / INV_REQ / TX requests, and
 *  - a **client** that scans for that service only (other BLE devices are never seen), and
 *    connects to each peer it finds to push the transactions the peer lacks.
 * Data only flows client → server, so each side pushes with its own client.
 *
 * Log lines under tag MESH (PEER_FOUND, HELLO, REFUSED, FWD, RECV, SESSION) are read by tests.
 */
@SuppressLint("MissingPermission")
class BleMesh(
    private val ctx: Context,
    private val nodeId: String,
    private val network: String,
    private val queue: RelayQueue,
    private val relay: Relay,
) {
    data class PeerInfo(
        val node: String,
        val version: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val lastSync: Long?,
        val lastResult: String?,
    )

    private class Peer(val node: String, @Volatile var device: BluetoothDevice, @Volatile var version: Int) {
        val firstSeen = System.currentTimeMillis()
        @Volatile var lastSeen = firstSeen
        @Volatile var syncedVersion = -1L
        @Volatile var syncedAt = 0L
        @Volatile var nextTry = 0L
        @Volatile var failures = 0
        @Volatile var lastResult: String? = null
        /** False after a failed session until the peer is heard again: its address may have rotated or it left. */
        @Volatile var fresh = true
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    /** Protocol version we speak; debug builds can change it to test refusals. */
    @Volatile var protoVersion = Proto.VERSION
    private val hello get() = Proto.Hello(Proto.APP_ID, protoVersion, network, nodeId)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val wake = LinkedBlockingQueue<Unit>()

    @Volatile var running = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var stats = Stats()
        private set

    data class Stats(val sent: Int = 0, val received: Int = 0, val refused: Int = 0, val sessions: Int = 0, val failedSessions: Int = 0)

    fun peers(): List<PeerInfo> = peers.values.map {
        PeerInfo(it.node, it.version, it.firstSeen, it.lastSeen, it.syncedAt.takeIf { t -> t > 0 }, it.lastResult)
    }

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            lastError = "bluetooth off"
            return
        }
        running = true
        lastError = null
        startServer()
        startScan()
        clientThread = Thread(::clientLoop, "mesh-client").also { it.start() }
        log("START node=$nodeId v=$protoVersion net=$network")
    }

    @Synchronized
    fun stop(reason: String) {
        if (!running) return
        running = false
        wake.offer(Unit)
        runCatching { scanCb?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        runCatching { advertiseCb?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        runCatching { server?.close() }
        scanCb = null
        advertiseCb = null
        server = null
        links.values.forEach { it.exec.shutdownNow() }
        links.clear()
        session?.abort()
        clientThread?.interrupt()
        clientThread = null
        peers.clear()
        log("STOP reason=$reason")
    }

    /** The queue changed: look for peers to update now. */
    fun poke() {
        wake.offer(Unit)
    }

    // ---------------------------------------------------------------- server

    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var advertiseCb: AdvertiseCallback? = null

    /** One connected client: its frames are reassembled and handled on its own thread. */
    private inner class Link(val device: BluetoothDevice) {
        val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-link") }
        val reassembler = Wire.Reassembler()
        val notifySent = Semaphore(0)
        @Volatile var mtu = 23
        @Volatile var peer: Proto.Hello? = null
    }

    private val links = ConcurrentHashMap<String, Link>()

    private fun startServer() {
        val s = manager.openGattServer(ctx, serverCallback) ?: run {
            lastError = "could not open GATT server"
            return
        }
        val service = BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            Proto.RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            Proto.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(Proto.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server = s
        txChar = tx
        s.addService(service) // advertising starts in onServiceAdded
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            lastError = "no advertiser"
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(Proto.SERVICE)).setIncludeDeviceName(false).build()
        val resp = AdvertiseData.Builder().addServiceData(ParcelUuid(Proto.SERVICE), Proto.advert(nodeId, protoVersion)).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log("ADVERTISING node=$nodeId")
            }
            override fun onStartFailure(errorCode: Int) {
                lastError = "advertise failed: $errorCode"
                log("ADVERTISE_FAILED code=$errorCode")
            }
        }
        advertiseCb = cb
        adv.startAdvertising(settings, data, resp, cb)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else log("SERVICE_ADD_FAILED status=$status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                links[device.address] = Link(device)
            } else {
                links.remove(device.address)?.exec?.shutdownNow()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            links[device.address]?.mtu = mtu
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            links[device.address]?.notifySent?.release()
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid != Proto.RX) return
            val link = links.getOrPut(device.address) { Link(device) }
            val frame = value.copyOf()
            runCatching { link.exec.execute { onFrame(link, frame) } }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    /** On the link's thread. */
    private fun onFrame(link: Link, frame: ByteArray) {
        when (val ev = link.reassembler.accept(frame)) {
            null -> link.reassembler.expire().forEach { reply(link, Proto.nack(it.reason)) }
            is Wire.Event.Failed -> {
                log("CORRUPT from=${link.device.address} reason=${ev.reason.replace(' ', '_')}")
                reply(link, Proto.nack(ev.reason))
            }
            is Wire.Event.Done -> {
                val out = try {
                    handle(link, Proto.decode(ev.data))
                } catch (e: Exception) {
                    Proto.nack("bad request: ${e.message}")
                }
                reply(link, out)
            }
        }
    }

    private fun handle(link: Link, m: Proto.Msg): ByteArray = when (m) {
        is Proto.MHello -> {
            val why = Proto.refusal(hello, m.hello)
            if (why == null) {
                link.peer = m.hello
                log("HELLO role=server peer=${m.hello.node} v=${m.hello.version}")
                Proto.HelloReply(true, null, hello).encode()
            } else {
                stats = stats.copy(refused = stats.refused + 1)
                log("REFUSED role=server peer=${m.hello.node} reason=\"$why\"")
                Proto.HelloReply(false, why, hello).encode()
            }
        }
        is Proto.MInvReq -> if (link.peer == null) Proto.nack("say HELLO first") else Proto.inv(queue.txids())
        is Proto.MTx -> {
            val peer = link.peer
            if (peer == null) {
                Proto.nack("say HELLO first")
            } else {
                val t0 = SystemClock.elapsedRealtime()
                val r = runBlocking { relay.ingest(m.tx.txid, m.tx.hops, m.tx.raw, peer.node) }
                if (r.status == Relay.Status.STORED) {
                    stats = stats.copy(received = stats.received + 1)
                    poke() // pass it on
                }
                log("RECV txid=${m.tx.txid} from=${peer.node} hops=${m.tx.hops} status=${r.wire} ms=${SystemClock.elapsedRealtime() - t0} reason=\"${r.reason ?: ""}\"")
                Proto.TxResult(r.txid, r.wire, r.reason).encode()
            }
        }
        else -> Proto.nack("unexpected ${m.javaClass.simpleName}")
    }

    /** Notifies [msg] to the link's client, one frame at a time (each waits for onNotificationSent). */
    private fun reply(link: Link, msg: ByteArray) {
        val s = server ?: return
        val c = txChar ?: return
        val frames = Wire.frames(Random.nextInt(), msg, maxOf(Wire.MIN_FRAME, Wire.maxFrame(link.mtu)))
        link.notifySent.drainPermits()
        for (f in frames) {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(link.device, c, false, f) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = f
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(link.device, c, false)
            }
            if (!ok || !link.notifySent.tryAcquire(5, TimeUnit.SECONDS)) {
                log("NOTIFY_FAILED to=${link.device.address}")
                return
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    private var scanCb: ScanCallback? = null

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            lastError = "no scanner"
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = seen(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::seen)
            override fun onScanFailed(errorCode: Int) {
                lastError = "scan failed: $errorCode"
                log("SCAN_FAILED code=$errorCode")
            }
        }
        scanCb = cb
        // Filtered on our service UUID: headphones, watches and other apps never reach us, and
        // Android keeps filtered scans running with the screen off.
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
    }

    private fun seen(r: ScanResult) {
        if (!running) return // late callback after stopScan
        val (ver, node) = Proto.parseAdvert(r.scanRecord?.getServiceData(ParcelUuid(Proto.SERVICE))) ?: return
        if (node == nodeId) return
        val p = peers[node]
        if (p == null) {
            peers[node] = Peer(node, r.device, ver)
            log("PEER_FOUND node=$node v=$ver addr=${r.device.address}")
            wake.offer(Unit)
        } else {
            if (p.device.address != r.device.address) log("PEER_ADDR node=$node ${p.device.address} -> ${r.device.address}")
            p.device = r.device
            p.version = ver
            p.lastSeen = System.currentTimeMillis()
            p.fresh = true
        }
    }

    // ---------------------------------------------------------------- client

    private var clientThread: Thread? = null
    @Volatile private var session: Session? = null

    private fun clientLoop() {
        while (running) {
            try {
                wake.poll(1, TimeUnit.SECONDS)
                val now = System.currentTimeMillis()
                peers.values.removeIf { now - it.lastSeen > PEER_GONE_MS }
                if (!running) break
                val v = queue.version
                if (queue.forwardable().isEmpty()) continue
                val due = peers.values.filter {
                    it.fresh && now >= it.nextTry && now - it.lastSeen < PEER_GONE_MS &&
                        (it.syncedVersion != v || now - it.syncedAt > RESYNC_MS)
                }.minByOrNull { it.syncedAt }
                if (due != null && !sync(due, v)) {
                    // Watchdog: a run of failures with peers in sight means our side of the
                    // stack is stuck (seen on emulators after rapid Bluetooth toggling).
                    if (++failStreak >= WATCHDOG_FAILURES && now - lastRestart > WATCHDOG_MS) {
                        lastRestart = now
                        failStreak = 0
                        log("WATCHDOG restarting Bluetooth mesh after $WATCHDOG_FAILURES failed sessions")
                        Thread { restart("watchdog") }.start()
                        break
                    }
                } else if (due != null) {
                    failStreak = 0
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log("CLIENT_ERROR ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private var failStreak = 0
    @Volatile private var lastRestart = 0L

    @Synchronized
    fun restart(reason: String) {
        stop(reason)
        start()
    }

    /** One session with [p]. Returns false if it failed (refusals count as success). */
    private fun sync(p: Peer, version: Long): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val s = Session(p.device)
        session = s
        var pushed = 0
        val result = try {
            s.connect()
            val reply = s.request(hello.encode(), HELLO_TIMEOUT_MS) as? Proto.MHelloReply ?: error("no HELLO reply")
            // Our own reason (from the peer's HELLO) reads right on this phone; else the peer's.
            val why = reply.reply.hello?.let { Proto.refusal(hello, it) }
                ?: if (!reply.reply.ok) reply.reply.reason ?: "refused" else null
            if (why != null) {
                stats = stats.copy(refused = stats.refused + 1)
                log("REFUSED role=client peer=${p.node} reason=\"$why\"")
                p.nextTry = System.currentTimeMillis() + REFUSED_RETRY_MS
                p.lastResult = "refused: $why"
                return true
            }
            log("HELLO role=client peer=${p.node} v=${reply.reply.hello?.version}")
            val inv = s.request(Proto.invReq(), REQUEST_TIMEOUT_MS) as? Proto.MInv ?: error("no INV reply")
            val have = inv.txids.toHashSet()
            for (e in queue.forwardable().filter { it.txid !in have }) {
                val tf = SystemClock.elapsedRealtime()
                val r = s.request(Proto.Tx(e.hops + 1, e.txid, e.raw).encode(), TX_TIMEOUT_MS) as? Proto.MTxResult
                    ?: error("no TX_RESULT for ${e.txid}")
                log("FWD txid=${e.txid} to=${p.node} hops=${e.hops + 1} status=${r.result.status} ms=${SystemClock.elapsedRealtime() - tf} reason=\"${r.result.reason ?: ""}\"")
                if (r.result.status == "stored") {
                    pushed++
                    stats = stats.copy(sent = stats.sent + 1)
                }
            }
            p.syncedVersion = version
            p.syncedAt = System.currentTimeMillis()
            p.failures = 0
            "ok pushed=$pushed"
        } catch (e: Exception) {
            p.failures++
            p.fresh = false
            p.nextTry = System.currentTimeMillis() + minOf(RETRY_MS shl minOf(p.failures - 1, 4), 30_000L)
            stats = stats.copy(failedSessions = stats.failedSessions + 1)
            "failed: ${e.message}"
        } finally {
            s.close()
            session = null
        }
        stats = stats.copy(sessions = stats.sessions + 1)
        p.lastResult = result
        log("SESSION peer=${p.node} result=\"$result\" ms=${SystemClock.elapsedRealtime() - t0}")
        return result.startsWith("ok")
    }

    /**
     * One client connection. GATT callbacks become events on a queue so each step waits in
     * turn; the server's notified frames go to their own queue, because a notification can
     * overtake the callback of the write that caused it (found in the Phase 0 spike).
     */
    private inner class Session(private val device: BluetoothDevice) {
        private val events = LinkedBlockingQueue<Any>()
        private val replies = LinkedBlockingQueue<Wire.Event>()
        private val reassembler = Wire.Reassembler()
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var mtu = 23
        @Volatile private var up = false

        private inner class Conn(val connected: Boolean, val status: Int)
        private inner class Mtu(val mtu: Int)
        private inner class Discovered(val status: Int)
        private inner class DescWritten(val status: Int)
        private inner class Written(val status: Int)

        fun connect() {
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            val g = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE) ?: error("connectGatt returned null")
            gatt = g
            val c = await<Conn>(deadline)
            check(c.connected) { "connect failed: ${c.status}" }
            up = true
            g.requestMtu(517)
            mtu = await<Mtu>(deadline).mtu
            check(Wire.maxFrame(mtu) >= Wire.MIN_FRAME) { "mtu too small: $mtu" }
            g.discoverServices()
            await<Discovered>(deadline)
            val svc = g.getService(Proto.SERVICE) ?: error("service missing")
            rx = svc.getCharacteristic(Proto.RX) ?: error("RX missing")
            val tx = svc.getCharacteristic(Proto.TX) ?: error("TX missing")
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(Proto.CCCD) ?: error("CCCD missing")
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            await<DescWritten>(deadline)
        }

        /** Sends one request and waits for the reply message. A NACK is retried twice. */
        fun request(msg: ByteArray, timeoutMs: Long): Proto.Msg {
            repeat(3) { attempt ->
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                replies.clear()
                for (f in Wire.frames(Random.nextInt(), msg, Wire.maxFrame(mtu))) {
                    val g = gatt ?: error("closed")
                    val st = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(rx!!, f, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        rx!!.value = f
                        rx!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        if (g.writeCharacteristic(rx!!)) BluetoothStatusCodes.SUCCESS else -1
                    }
                    check(st == BluetoothStatusCodes.SUCCESS) { "write rejected: $st" }
                    val w = await<Written>(deadline)
                    check(w.status == BluetoothGatt.GATT_SUCCESS) { "write failed: ${w.status}" }
                }
                while (true) {
                    val left = deadline - SystemClock.elapsedRealtime()
                    check(left > 0) { "timeout waiting for reply" }
                    check(up) { "disconnected" }
                    when (val ev = replies.poll(minOf(left, 200), TimeUnit.MILLISECONDS) ?: continue) {
                        is Wire.Event.Failed -> break // corrupt reply: ask again
                        is Wire.Event.Done -> {
                            val m = Proto.decode(ev.data)
                            if (m is Proto.MNack) {
                                log("NACK attempt=${attempt + 1} reason=\"${m.reason}\"")
                                break
                            }
                            return m
                        }
                    }
                }
            }
            error("request failed after 3 attempts")
        }

        fun abort() {
            up = false
            events.offer(Conn(false, -1))
        }

        fun close() {
            up = false
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }

        private inline fun <reified T> await(deadline: Long): T {
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                check(left > 0) { "timeout waiting for ${T::class.simpleName}" }
                val ev = events.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (ev is T) return ev
                if (ev is Conn && !ev.connected) error("disconnected: ${ev.status}")
            }
        }

        private fun onNotified(value: ByteArray) {
            val ev = synchronized(reassembler) { reassembler.accept(value) } ?: return
            replies.offer(ev)
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                val connected = newState == BluetoothProfile.STATE_CONNECTED
                if (!connected) up = false
                events.offer(Conn(connected, status))
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                events.offer(Mtu(mtu))
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                events.offer(Discovered(status))
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                events.offer(DescWritten(status))
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                events.offer(Written(status))
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onNotified(value)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 33) onNotified(c.value ?: return)
            }
        }
    }

    private fun log(m: String) = Log.i(TAG, m)

    companion object {
        const val TAG = "MESH"
        const val PEER_GONE_MS = 30_000L
        /** Re-check a peer this often even if our queue has not changed. */
        const val RESYNC_MS = 60_000L
        const val RETRY_MS = 2_000L
        const val WATCHDOG_FAILURES = 5
        const val WATCHDOG_MS = 2 * 60_000L
        const val REFUSED_RETRY_MS = 5 * 60_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val HELLO_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        /** The server verifies proofs before answering; slow phones take a while. */
        const val TX_TIMEOUT_MS = 180_000L
    }
}
