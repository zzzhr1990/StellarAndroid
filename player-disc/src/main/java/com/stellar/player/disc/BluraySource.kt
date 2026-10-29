package com.stellar.player.disc

import org.json.JSONObject
import java.util.UUID

class EncryptedDiscException : Exception()

class BluraySource private constructor(private val token: String, private val reader: DiscReader,
    override val titles: List<DiscTitle>, val structure: String) : DiscSource {
    override val bluray = true
    override fun address(title: Int) = "$token/$title"
    override fun openMenu() = DiscMenu.open(token, true)
    override fun close() { BlurayNative.remove(token); reader.close() }

    companion object {
        /** Null only when no Blu-ray structure is detected; the caller may then probe DVD. */
        fun tryOpen(reader: DiscReader, files: Map<String, Long>, iso: Boolean): BluraySource? {
            val token = "stellarbluray://${UUID.randomUUID()}"
            try {
                val raw = BlurayNative.register(token, reader, files.keys.toTypedArray(), files.values.toLongArray(), iso)
                val json = JSONObject(raw)
                if (json.optString("error") == "not_bluray") return null
                if (json.optString("error") == "encrypted") throw EncryptedDiscException()
                check(!json.has("error")) { "Blu-ray structure unavailable" }
                val array = json.getJSONArray("titles")
                val titles = (0 until array.length()).map { i ->
                    val t = array.getJSONObject(i); val c = t.getJSONArray("chapters")
                    DiscTitle(t.getInt("number"), t.getLong("duration"), (0 until c.length()).map(c::getLong))
                }
                check(titles.isNotEmpty())
                return BluraySource(token, reader, titles, raw)
            } catch (e: Exception) { BlurayNative.remove(token); reader.close(); throw e }
        }
    }
}

private object BlurayNative {
    init { System.loadLibrary("stellar_disc") }
    external fun register(token: String, reader: DiscReader, names: Array<String>, sizes: LongArray, iso: Boolean): String
    external fun remove(token: String)
}
