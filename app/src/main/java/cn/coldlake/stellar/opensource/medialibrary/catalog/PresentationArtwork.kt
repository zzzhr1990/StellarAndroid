package cn.coldlake.stellar.opensource.medialibrary.catalog

import java.net.URI

/** Only official TMDB image references are resized; user media URLs are untouched. */
internal data class PresentationArtwork private constructor(
    val id: String, val kind: String, val version: String, private val sizes: List<Int>,
) {
    fun widthBucket(pixels: Double): Int = sizes.firstOrNull { it >= pixels } ?: sizes.last()
    fun url(width: Int, height: Int, sourceAspectRatio: Double? = null, fit: Boolean = false): String? {
        if (width <= 0 || height <= 0) return null
        val ratio = sourceAspectRatio?.takeIf { it.isFinite() && it > 0 }
        val needed = if (ratio == null) width.toDouble() else if (fit)
            minOf(width.toDouble(), height * ratio) else maxOf(width.toDouble(), height * ratio)
        return "https://image.tmdb.org/t/p/w${widthBucket(needed)}$id"
    }
    companion object {
        fun parse(value: String): PresentationArtwork? = runCatching {
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host != "image.tmdb.org" || uri.port != -1 || uri.rawQuery != null || uri.fragment != null) return null
            val match = Regex("/t/p/w(185|300|342|500|780|1280)(/[A-Za-z0-9._-]+)").matchEntire(uri.path) ?: return null
            val size = match.groupValues[1].toInt(); val id = match.groupValues[2]
            val kind = when (size) { 185 -> "profile"; 300 -> "still"; 1280 -> "backdrop"; else -> "poster" }
            val sizes = when (kind) { "profile" -> listOf(185); "still" -> listOf(300); "backdrop" -> listOf(780, 1280); else -> listOf(342, 500, 780) }
            PresentationArtwork(id, kind, id, sizes)
        }.getOrNull()
    }
}
