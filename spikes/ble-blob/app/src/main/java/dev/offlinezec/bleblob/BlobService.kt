package dev.offlinezec.bleblob

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Foreground service that plays one role, chosen by intent extras:
 *   role=sink  node=<id>                      advertise and receive blobs
 *   role=relay node=<id> target=<id>          receive, then forward each good blob to target
 *   role=send  node=<id> target=<id> count=<n> size=<bytes>   send n random blobs
 * Results go to logcat under tag BLEBLOB as RESULT / RECV / FWD / SUMMARY lines.
 */
@SuppressLint("MissingPermission")
class BlobService : Service() {
    private lateinit var manager: BluetoothManager
    private val worker = Executors.newSingleThreadExecutor()
    private val reassemblers = ConcurrentHashMap<String, Proto.Reassembler>()
    private var gattServer: BluetoothGattServer? = null
    private var ackChar: BluetoothGattCharacteristic? = null
    private var advertiseCb: AdvertiseCallback? = null
    private var forwarder: BlobClient? = null
    private var forwardTo: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var node = "X"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        manager = getSystemService(BluetoothManager::class.java)
        node = intent?.getStringExtra("node") ?: "X"
        startInForeground()
        val role = intent?.getStringExtra("role") ?: "sink"
        Log.i(TAG, "START role=$role node=$node")
        when (role) {
            "sink" -> startServer()
            "relay" -> {
                forwardTo = intent?.getStringExtra("target") ?: error("relay needs target")
                startServer()
            }
            "send" -> {
                val target = intent?.getStringExtra("target") ?: "B"
                val count = intent?.getIntExtra("count", 100) ?: 100
                val size = intent?.getIntExtra("size", 20 * 1024) ?: (20 * 1024)
                worker.execute { runSender(target, count, size) }
            }
            else -> Log.e(TAG, "unknown role $role")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        advertiseCb?.let { manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(it) }
        gattServer?.close()
        forwarder?.close()
        worker.shutdownNow()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun runSender(target: String, count: Int, size: Int) {
        val client = BlobClient(this, manager.adapter, target)
        val rnd = SecureRandom()
        val times = ArrayList<Long>()
        for (i in 1..count) {
            val blob = ByteArray(size).also(rnd::nextBytes)
            val r = client.sendBlob(blob, 60_000)
            if (r.ok) times += r.ms
            Log.i(TAG, "RESULT node=$node i=$i ok=${r.ok} ms=${r.ms} sha=${Proto.shortSha(blob)} err=${r.error ?: "-"}")
        }
        client.close()
        times.sort()
        fun pct(p: Double) = if (times.isEmpty()) -1 else times[((times.size - 1) * p).toInt()]
        Log.i(TAG, "SUMMARY node=$node sent=$count ok=${times.size} p50=${pct(0.5)} p95=${pct(0.95)} max=${times.lastOrNull() ?: -1}")
    }

    private fun forward(data: ByteArray) {
        val target = forwardTo ?: return
        val client = forwarder ?: BlobClient(this, manager.adapter, target).also { forwarder = it }
        val r = client.sendBlob(data, 60_000)
        Log.i(TAG, "FWD node=$node to=$target ok=${r.ok} ms=${r.ms} sha=${Proto.shortSha(data)} err=${r.error ?: "-"}")
    }

    private fun startServer() {
        if (gattServer != null) return
        val server = manager.openGattServer(this, serverCallback)
        val service = BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val data = BluetoothGattCharacteristic(
            Proto.DATA,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val ack = BluetoothGattCharacteristic(
            Proto.ACK,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        ack.addDescriptor(
            BluetoothGattDescriptor(
                Proto.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(data)
        service.addCharacteristic(ack)
        gattServer = server
        ackChar = ack
        // Advertising starts in onServiceAdded, once the service is live.
        server.addService(service)
    }

    private fun startAdvertising() {
        val adv = manager.adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "ADVERTISE_FAILED no advertiser (bluetooth off?)")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(Proto.SERVICE)).build()
        // The node id rides in the scan response: 128-bit UUID + id would overflow 31 bytes.
        val resp = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(Proto.SERVICE), node.toByteArray())
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "ADVERTISING node=$node")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "ADVERTISE_FAILED code=$errorCode")
            }
        }
        advertiseCb = cb
        adv.startAdvertising(settings, data, resp, cb)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
            else Log.e(TAG, "SERVICE_ADD_FAILED status=$status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val up = newState == BluetoothProfile.STATE_CONNECTED
            Log.i(TAG, "PEER ${if (up) "up" else "down"} addr=${device.address} status=$status")
            if (!up) reassemblers.remove(device.address)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "PEER_MTU addr=${device.address} mtu=$mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val done = reassemblers.getOrPut(device.address) { Proto.Reassembler() }.accept(value) ?: return
            Log.i(TAG, "RECV node=$node from=${device.address} ok=${done.ok} len=${done.data.size} sha=${Proto.shortSha(done.data)}")
            gattServer?.notifyCharacteristicChanged(device, ackChar!!, false, Proto.ack(done.blobId, done.ok))
            if (done.ok && forwardTo != null) worker.execute { forward(done.data) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("blob", "BLE blob spike", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "blob")
            .setContentTitle("BLE blob spike")
            .setContentText("node $node")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bleblob:relay")
                .apply { acquire() }
        }
    }
}
