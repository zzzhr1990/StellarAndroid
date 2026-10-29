package com.stellar.player.nativeio

import android.graphics.Bitmap
import java.io.Closeable

/** Independent encoder; never calls the playback engine. Confined to the export worker. */
class GifCaptureEncoder(path: String, width: Int, height: Int) : Closeable {
    private var handle = create(path, width, height).also { check(it != 0L) }
    fun add(bitmap: Bitmap, timestampMs: Long) { check(handle != 0L); check(add(handle, bitmap, timestampMs)) }
    fun finish(durationMs: Long) { check(handle != 0L); check(finish(handle, durationMs)) }
    override fun close() { if (handle != 0L) { release(handle); handle = 0 } }
    private external fun create(path: String, width: Int, height: Int): Long
    private external fun add(handle: Long, bitmap: Bitmap, timestampMs: Long): Boolean
    private external fun finish(handle: Long, durationMs: Long): Boolean
    private external fun release(handle: Long)
    companion object { init { System.loadLibrary("stellar_native") } }
}
