package cn.coldlake.stellar.opensource.platform

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper

/** A 180-degree rotation need not change Configuration.orientation or window dimensions. */
class PlayerRotationMonitor(activity: Activity, private val onRotation: () -> Unit) : AutoCloseable {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    @Suppress("DEPRECATION")
    private val display = activity.windowManager.defaultDisplay
    private var rotation = display.rotation
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display.displayId) return
            val current = manager.getDisplay(displayId)?.rotation ?: return
            if (current != rotation) { rotation = current; onRotation() }
        }
    }
    init { manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }
    override fun close() { manager.unregisterDisplayListener(listener) }
}
