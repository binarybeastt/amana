package dev.offlinezec.wallet

import android.app.Application

class App : Application() {
    /** Null in the `:core` process, which only serves offline-core and must not open the SDK. */
    var controller: AppController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) {
            controller = AppController(this).also { it.start() }
        }
    }

    private fun isMainProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }
        return name == packageName
    }

    companion object {
        fun controller(app: android.content.Context): AppController =
            (app.applicationContext as App).controller ?: error("no controller in this process")
    }
}
