package com.stellar.player.mpv

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Video and native subtitles remain in a compositor Surface separate from Compose controls. */
class MpvSurfaceView(context: Context, private val port: MpvPlaybackPort) : SurfaceView(context), SurfaceHolder.Callback {
    private var released = false
    init { holder.addCallback(this) }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!released && width > 0 && height > 0 && holder.surface.isValid) port.attachSurface(this, holder.surface, width, height)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { port.detachSurface(this) }
    fun release() {
        if (released) return
        released = true; port.detachSurface(this); holder.removeCallback(this)
    }
}
