package com.stellar.player.disc

import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** Created on an IO dispatcher. Closing prevents new readers; active native readers retain their source. */
class DvdSource private constructor(private val token: String, private val reader: DiscReader,
    override val titles: List<DiscTitle>) : DiscSource {
    override fun address(title: Int) = "$token/$title"
    override fun openMenu() = DiscMenu.open(token, false)
    override fun close() { DvdNative.remove(token); reader.close() }
    companion object {
        fun open(reader: DiscReader, files: Map<String, Long>, iso: Boolean): DvdSource {
            val token = "stellardvd://${UUID.randomUUID()}"
            try {
                val json = JSONObject(DvdNative.register(token, reader, files.keys.toTypedArray(), files.values.toLongArray(), iso))
                check(!json.has("error")) { "DVD structure unavailable" }
                val array = json.getJSONArray("titles")
                val titles = (0 until array.length()).map { i ->
                    val t = array.getJSONObject(i); val c = t.getJSONArray("chapters")
                    DiscTitle(t.getInt("number"), t.getLong("duration"), (0 until c.length()).map(c::getLong))
                }
                check(titles.isNotEmpty()) { "DVD has no supported titles" }
                return DvdSource(token, reader, titles)
            } catch (e: Exception) { DvdNative.remove(token); reader.close(); throw e }
        }
    }
}

private object DvdNative {
    init { System.loadLibrary("stellar_disc") }
    external fun register(token: String, reader: DiscReader, names: Array<String>, sizes: LongArray, iso: Boolean): String
    external fun remove(token: String)
}
