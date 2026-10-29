package cn.coldlake.stellar.opensource.medialibrary.catalog

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.IOException

/** Standard NFO plus the documented Infuse movie XML. No arbitrary backup/database schema inference. */
internal object LocalNfo {
    const val VERSION = 5
    const val MAX_BYTES = 2 * 1024 * 1024
    class InvalidContent : IOException("Unsupported or oversized NFO content")
    private val artworkNames = setOf("thumb", "poster", "fanart", "backdrop", "clearlogo", "logo")
    fun read(input: InputStream): NfoData? {
        val bytes = input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= MAX_BYTES) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        if (bytes.size > MAX_BYTES) throw InvalidContent()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), null)
        // Parse a bounded tree so nested actors, ratings and artwork never leak into title/plot.
        data class Node(val name: String, val attrs: Map<String, String>, val children: MutableList<Node> = mutableListOf(), var text: String = "") {
            fun all(name: String) = children.filter { it.name == name }
            fun value(name: String) = all(name).firstOrNull()?.text?.trim()?.takeIf(String::isNotBlank)
        }
        val stack = java.util.ArrayDeque<Node>()
        var document: Node? = null
        var nodes = 0
        while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> throw InvalidContent()
                XmlPullParser.START_TAG -> {
                    if (++nodes > 8192 || stack.size >= 32) throw InvalidContent()
                    val node = Node(parser.name.lowercase(java.util.Locale.ROOT), (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                    stack.peekLast()?.children?.add(node) ?: run { document = node }
                    stack.addLast(node)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> stack.peekLast()?.let {
                    val text = it.text + parser.text.orEmpty()
                    // Artwork URLs are opaque, including their complete signed query. The
                    // document byte budget still bounds their size; prose keeps its own cap.
                    it.text = if (it.name in artworkNames) text else text.take(16_384)
                }
                XmlPullParser.END_TAG -> stack.removeLast()
            }
        }
        if (stack.isNotEmpty()) throw InvalidContent()
        val doc = document ?: return null
        val root = if (doc.name == "media" && doc.attrs["type"].equals("Movie", true)) "movie" else doc.name
        val fields = doc.children.associate { it.name to it.text.trim() }.toMutableMap()
        doc.value("description")?.let { fields["plot"] = it }
        doc.value("published")?.let { fields["year"] = it.take(4) }
        val ids = sortedMapOf<String, String>()
        for (node in doc.children) {
            val type = if (node.name == "uniqueid") node.attrs["type"] else node.name.removeSuffix("id")
            val id = node.text.trim().take(128)
            if (node.name in setOf("uniqueid", "tmdbid", "imdbid", "tvdbid") && type in setOf("tmdb", "imdb", "tvdb") && id.isNotBlank()) {
                if (ids.containsKey(type) && ids[type] != id) return null
                ids[requireNotNull(type)] = id
            }
        }
        if (root !in setOf("movie", "tvshow", "episodedetails", "season")) return null
        fun number(key: String) = fields[key]?.toIntOrNull()?.takeIf { it >= 0 }
        val kind = when (root) { "movie" -> "movie"; "tvshow" -> "series"; "season" -> "season"; else -> "episode" }
        if (kind == "episode" && (number("season") == null || number("episode") == null)) return null
        fun art(value: String?) = value?.trim()?.takeIf(String::isNotBlank)?.let { LibraryArtwork(it) }
        val art = doc.all("art").firstOrNull()
        val thumbs = doc.all("thumb")
        val ratings = doc.all("ratings").flatMap { it.all("rating") }.mapNotNull { r ->
            val value = r.value("value")?.toDoubleOrNull() ?: return@mapNotNull null
            val max = r.attrs["max"]?.toDoubleOrNull() ?: 10.0
            LibraryRating(r.attrs["name"].orEmpty().take(128), value, max, r.value("votes")?.toLongOrNull())
        }.ifEmpty { doc.value("rating")?.toDoubleOrNull()?.let { listOf(LibraryRating("", it, votes = doc.value("votes")?.toLongOrNull())) }.orEmpty() }
        val people = doc.all("actor").mapNotNull { p -> p.value("name")?.let { name ->
            LibraryCredit(p.value("id").orEmpty(), name.take(512), "Actor", p.value("role")?.take(1024), art(p.value("thumb")))
        } } + listOf("director" to "Director", "credits" to "Writer").flatMap { (tag, type) ->
            doc.all(tag).mapNotNull { p -> p.text.trim().takeIf(String::isNotBlank)?.let { LibraryCredit("", it.take(512), type) } }
        }
        val details = LibraryDetails(
            poster = art(art?.value("poster") ?: thumbs.firstOrNull { it.attrs["aspect"] in setOf(null, "poster") }?.text),
            backdrop = art(art?.value("fanart") ?: art?.value("backdrop") ?: doc.all("fanart").firstOrNull()?.value("thumb") ?: thumbs.firstOrNull { it.attrs["aspect"] == "fanart" }?.text),
            logo = art(art?.value("clearlogo") ?: art?.value("logo") ?: doc.value("clearlogo") ?: thumbs.firstOrNull { it.attrs["aspect"] in setOf("clearlogo", "logo") }?.text),
            ratings = ratings, people = people, genres = doc.all("genre").map { it.text.trim() }.filter(String::isNotBlank).distinct(),
            tagline = doc.value("tagline"), premiere = doc.value("premiered") ?: doc.value("releasedate"),
            runtimeMinutes = doc.value("runtime")?.toIntOrNull(), officialRating = doc.value("mpaa"))
        return NfoData(kind, fields["title"]?.takeIf { it.isNotBlank() }, number("year"), fields["plot"],
            fields["showtitle"], number("season"), number("episode"), ids.entries.joinToString(";") { "${it.key}:${it.value}" },
            details.copy(ratings = ratings.filter { it.value.isFinite() && it.max.isFinite() && it.max > 0 && it.value in 0.0..it.max }.take(8),
                people = people.distinctBy { listOf(it.id, it.name, it.type, it.role) }.take(LibraryDetails.MAX_PEOPLE),
                peopleTruncated = people.size > LibraryDetails.MAX_PEOPLE, genres = details.genres.take(64),
                runtimeMinutes = details.runtimeMinutes?.takeIf { it > 0 }))
    }
}
