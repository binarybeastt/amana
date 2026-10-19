package dev.offlinezec.bleblob

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Only asks for permissions. The test itself is driven over adb by run.sh. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            1,
        )
        setContentView(
            TextView(this).apply {
                text = "BLE blob spike\n\nDriven over adb by spikes/ble-blob/run.sh."
                setPadding(48, 96, 48, 48)
            },
        )
    }
}
