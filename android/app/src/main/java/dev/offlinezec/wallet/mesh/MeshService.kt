package dev.offlinezec.wallet.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.offlinezec.wallet.App
import dev.offlinezec.wallet.ui.MainActivity

/**
 * Foreground service that keeps the main process (and so the mesh) alive with the screen off
 * or the app in the background: Bluetooth relaying and broadcasting go on until the user stops
 * the app. Holds a partial wake lock; a foreground-service process keeps it in Doze.
 */
class MeshService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("Relaying payments over Bluetooth")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // No Bluetooth permission yet (required for this service type on Android 14+).
            Log.w(TAG, "cannot run in the foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offlinezec:mesh")
                .apply { setReferenceCounted(false); acquire() }
        }
        Log.i(TAG, "FOREGROUND started")
        (applicationContext as App).controller?.mesh?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        Log.i(TAG, "FOREGROUND stopped")
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Payment relay", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Offline ZEC")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MESH"
        private const val CHANNEL = "mesh"
        private const val ID = 7

        fun start(c: Context) {
            if (!Mesh.hasBluetoothPermissions(c)) return
            try {
                c.startForegroundService(Intent(c, MeshService::class.java))
            } catch (e: Exception) {
                // Background start not allowed right now; the next app start or boot retries.
                Log.w(TAG, "could not start the mesh service: ${e.message}")
            }
        }
    }
}

/** Restarts relaying after a reboot, before anyone opens the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("MESH", "BOOT ${intent.action}")
        MeshService.start(context)
    }
}
