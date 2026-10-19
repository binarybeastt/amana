package dev.offlinezec.bleblob

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sends blobs to the peer advertising [target] as its node id. Blocking; call from a worker
 * thread. GATT callbacks are turned into events on a queue so each step can wait in turn.
 */
@SuppressLint("MissingPermission")
class BlobClient(
    private val ctx: Context,
    private val adapter: BluetoothAdapter,
    private val target: String,
) {
    data class Result(val ok: Boolean, val ms: Long, val error: String?)

    private sealed interface Ev
    private data class Found(val device: BluetoothDevice) : Ev
    private data class Conn(val connected: Boolean, val status: Int) : Ev
    private data class Mtu(val mtu: Int, val status: Int) : Ev
    private data class Discovered(val status: Int) : Ev
    private data class DescWritten(val status: Int) : Ev
    private data class Written(val status: Int) : Ev

    private val events = LinkedBlockingQueue<Ev>()

    // Acks get their own queue: a notification can land before the last write callback,
    // and the event wait loop would otherwise drop it.
    private val acks = LinkedBlockingQueue<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var mtu = 23

    @Volatile
    private var connected = false

    fun sendBlob(blob: ByteArray, timeoutMs: Long): Result {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + timeoutMs
        fun elapsed() = SystemClock.elapsedRealtime() - start
        return try {
            if (!connected) connect(deadline)
            val id = Random.nextInt()
            for (frame in Proto.frames(id, blob, Proto.maxFrame(mtu))) {
                val st = gatt!!.writeCharacteristic(
                    dataChar!!, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                check(st == BluetoothStatusCodes.SUCCESS) { "write rejected: $st" }
                val w = await<Written>(deadline)
                check(w.status == BluetoothGatt.GATT_SUCCESS) { "write failed: ${w.status}" }
            }
            var result: Result? = null
            while (result == null) {
                val left = deadline - SystemClock.elapsedRealtime()
                check(left > 0) { "timeout waiting for Ack" }
                check(connected) { "disconnected before ack" }
                val raw = acks.poll(minOf(left, 200), TimeUnit.MILLISECONDS) ?: continue
                val (ackId, ok) = Proto.parseAck(raw) ?: continue
                if (ackId == id) result = Result(ok, elapsed(), if (ok) null else "hash_mismatch")
            }
            result
        } catch (e: Exception) {
            // Drop the link so the next blob starts from a clean connection.
            close()
            Result(false, elapsed(), e.message?.replace(' ', '_'))
        }
    }

    fun close() {
        connected = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun connect(deadline: Long) {
        events.clear()
        acks.clear()
        val scanner = adapter.bluetoothLeScanner ?: error("bluetooth off")
        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val node = result.scanRecord?.getServiceData(ParcelUuid(Proto.SERVICE))
                if (node != null && String(node) == target) events.offer(Found(result.device))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "SCAN_FAILED code=$errorCode")
            }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb,
        )
        val device = try {
            await<Found>(deadline).device
        } finally {
            scanner.stopScan(scanCb)
        }

        val g = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        val c = await<Conn>(deadline)
        check(c.connected) { "connect failed: ${c.status}" }
        connected = true

        g.requestMtu(517)
        mtu = await<Mtu>(deadline).mtu
        check(mtu >= Proto.MIN_MTU) { "mtu too small: $mtu" }

        g.discoverServices()
        await<Discovered>(deadline)
        val svc = g.getService(Proto.SERVICE) ?: error("service missing")
        dataChar = svc.getCharacteristic(Proto.DATA)
        val ack = svc.getCharacteristic(Proto.ACK)
        g.setCharacteristicNotification(ack, true)
        g.writeDescriptor(ack.getDescriptor(Proto.CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        await<DescWritten>(deadline)
        Log.i(TAG, "CONNECTED target=$target mtu=$mtu")
    }

    /** Waits for the next event of type [T]; a disconnect while waiting fails the step. */
    private inline fun <reified T : Ev> await(deadline: Long): T {
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            check(left > 0) { "timeout waiting for ${T::class.simpleName}" }
            val ev = events.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (ev is T) return ev
            if (ev is Conn && !ev.connected) error("disconnected: ${ev.status}")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val up = newState == BluetoothProfile.STATE_CONNECTED
            if (!up) connected = false
            events.offer(Conn(up, status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            events.offer(Mtu(mtu, status))
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
            acks.offer(value)
        }
    }
}
