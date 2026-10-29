package com.stellar.player.disc

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable

enum class DiscMenuKey { UP, DOWN, LEFT, RIGHT, ACTIVATE, TOP, RETURN, TOUCH, POPUP }
data class DiscMenuState(val epoch: Int = 1, val revision: Int = -1,
    val image: Bitmap? = null, val width: Int = 0, val height: Int = 0,
    val aspect: Float = 16f / 9, val interactive: Boolean = false,
    val selected: Int = 0, val buttons: Int = 0, val still: Int = -1,
    val error: Int = 0, val audio: Int = -1, val subtitle: Int = -1, val title: Int = 0)

/** IO-thread API. Native navigation survives decoder replacement and has bounded read-ahead. */
class DiscMenu private constructor(private val token: String, private val handle: Long) : Closeable {
    private var previous = DiscMenuState()
    fun address(epoch: Int) = "$token/menu/$handle/$epoch"
    fun poll(positionMs: Long, ended: Boolean): DiscMenuState {
        val j = JSONObject(MenuNative.poll(handle, positionMs, ended, SystemClock.elapsedRealtime()))
        if (!j.has("epoch")) return previous
        var revision = j.getInt("revision")
        val width = j.getInt("width"); val height = j.getInt("height")
        val image = if (revision == previous.revision) previous.image else {
            val pixels=MenuNative.pixels(handle, revision)
            if(pixels==null) { revision=previous.revision;previous.image }
            else pixels.takeIf { width > 0 && height > 0 && it.size == width * height }
                ?.let { Bitmap.createBitmap(it, width, height, Bitmap.Config.ARGB_8888) }
        }
        return DiscMenuState(j.getInt("epoch"), revision, image, width, height, j.getDouble("aspect").toFloat(),
            j.getBoolean("interactive"), j.getInt("selected"), j.getInt("buttons"), j.getInt("still"), j.getInt("error"),
            j.getInt("audio"), j.getInt("subtitle"), j.getInt("title")).also { previous = it }
    }
    fun input(key: DiscMenuKey, x: Int = 0, y: Int = 0) = MenuNative.input(handle, key.ordinal, x, y)
    override fun close() = MenuNative.close(handle)
    companion object {
        internal fun open(token: String, bluray: Boolean): DiscMenu? = MenuNative.open(token, bluray)
            .takeIf { it != 0L }?.let { DiscMenu(token, it) }
    }
}
private object MenuNative {
    init { System.loadLibrary("stellar_disc") }
    external fun open(token: String, bluray: Boolean): Long
    external fun poll(handle: Long, positionMs: Long, ended: Boolean, nowMs: Long): String
    external fun pixels(handle: Long, revision: Int): IntArray?
    external fun input(handle: Long, key: Int, x: Int, y: Int): Boolean
    external fun close(handle: Long)
}
