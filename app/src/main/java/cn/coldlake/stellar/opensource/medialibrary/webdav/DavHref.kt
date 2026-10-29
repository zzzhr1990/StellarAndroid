package cn.coldlake.stellar.opensource.medialibrary.webdav

import java.net.URI

private val DAV_AUTHORITY = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*:)?//[^/]*")

/** Repair unescaped path characters only. Existing escapes and URI delimiters keep their meaning. */
internal fun davHref(value: String): URI {
    fun fail(): Nothing = throw WebDavFailure(WebDavError.PROTOCOL)
    if (value.length > 65536 || value.any { it.isISOControl() } || '?' in value || '#' in value) fail()
    // IPv6 brackets belong to the authority, whereas brackets in file names need escaping.
    val authorityEnd = DAV_AUTHORITY.find(value)?.range?.last?.plus(1) ?: 0
    val encoded = buildString {
        append(value.take(authorityEnd))
        var i = authorityEnd
        while (i < value.length) {
            val code = value.codePointAt(i)
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length || value[i + 1].digitToIntOrNull(16) == null ||
                    value[i + 2].digitToIntOrNull(16) == null) fail()
                append('%'); append(value[i + 1].uppercaseChar()); append(value[i + 2].uppercaseChar())
                i += 3; continue
            }
            if (code < 128 && (c.isLetterOrDigit() || c in "-._~!$&'()*+,;=:@/")) append(c)
            else {
                if (code in 0xD800..0xDFFF) fail()
                String(Character.toChars(code)).toByteArray(Charsets.UTF_8).forEach { byte ->
                    val n = byte.toInt() and 255
                    append('%'); append("0123456789ABCDEF"[n ushr 4]); append("0123456789ABCDEF"[n and 15])
                }
            }
            i += Character.charCount(code)
        }
    }
    val uri = try { URI(encoded) } catch (_: java.net.URISyntaxException) { fail() }
    if (uri.isOpaque || uri.rawUserInfo != null) fail()
    // Check before resolution: neither literal nor encoded traversal/separators can be normalized away.
    for (segment in uri.rawPath.orEmpty().split('/')) {
        val decoded = URI("http://placeholder/$segment").path.removePrefix("/")
        if (decoded in listOf(".", "..") || decoded.any { it == '/' || it == '\\' || it.isISOControl() || it == '\uFFFD' }) fail()
    }
    return uri
}
