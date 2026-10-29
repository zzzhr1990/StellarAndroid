package cn.coldlake.stellar.opensource.medialibrary.webdav

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal data class DavScanEntry(val address: String, val name: String, val directory: Boolean,
    val size: Long?, val modified: Long?, val version: String?)

/** A response is provisional until the complete multistatus and self collection have been read. */
internal suspend fun readDavScan(input: InputStream, address: String, consume: suspend (DavScanEntry) -> Unit) {
    val base = davHref(address.trimEnd('/') + "/")
    fun fail(): Nothing = throw WebDavFailure(WebDavError.PROTOCOL)
    val xml = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    xml.setInput(input, null)
    var self = false
    var rootEnded = false
    if (xml.nextTag() != XmlPullParser.START_TAG || xml.name != "multistatus" || xml.namespace != "DAV:") fail()
    while (xml.next() != XmlPullParser.END_DOCUMENT) {
        currentCoroutineContext().ensureActive()
        if (xml.eventType == XmlPullParser.END_TAG && xml.depth == 1) { rootEnded = true; continue }
        if (xml.eventType != XmlPullParser.START_TAG || xml.depth != 2 || xml.name != "response" || xml.namespace != "DAV:") continue
        var href: String? = null; var responseStatus: String? = null
        var hasType = false; var directory = false; var size: Long? = null; var modified: Long? = null; var version: String? = null
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.END_TAG && xml.depth == 2) break
            if (xml.eventType != XmlPullParser.START_TAG || xml.namespace != "DAV:") continue
            when {
                xml.depth == 3 && xml.name == "href" -> href = xml.nextText().also { if (it.length > 65536) fail() }
                xml.depth == 3 && xml.name == "status" -> responseStatus = xml.nextText()
                xml.depth == 3 && xml.name == "propstat" -> {
                    var status: String? = null; var type = false; var dir = false
                    var length: Long? = null; var time: Long? = null; var tag: String? = null
                    while (xml.next() != XmlPullParser.END_DOCUMENT) {
                        if (xml.eventType == XmlPullParser.END_TAG && xml.depth == 3) break
                        if (xml.eventType != XmlPullParser.START_TAG || xml.namespace != "DAV:") continue
                        when {
                            xml.depth == 4 && xml.name == "status" -> status = xml.nextText()
                            xml.depth == 5 && xml.name == "resourcetype" -> type = true
                            xml.depth == 6 && xml.name == "collection" -> dir = true
                            xml.depth == 5 && xml.name == "getcontentlength" -> length = xml.nextText().toLongOrNull()?.takeIf { it >= 0 }
                            xml.depth == 5 && xml.name == "getlastmodified" -> time = runCatching {
                                ZonedDateTime.parse(xml.nextText(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                            }.getOrNull()
                            xml.depth == 5 && xml.name == "getetag" -> tag = xml.nextText().also { if (it.length > 65536) fail() }
                        }
                    }
                    val code = status?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: fail()
                    if (code in 200..299) {
                        if (type) { hasType = true; directory = dir }
                        size = length ?: size; modified = time ?: modified; version = tag ?: version
                    } else if (code != 404) fail()
                }
            }
        }
        if (responseStatus != null && responseStatus.trim().split(' ').getOrNull(1)?.toIntOrNull() !in 200..299) fail()
        if (!hasType || href == null) fail()
        val resolved = base.resolve(davHref(href))
        fun port(uri: URI) = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
        if (!resolved.scheme.equals(base.scheme, true) || !resolved.host.equals(base.host, true) || port(resolved) != port(base) ||
            resolved.rawUserInfo != null || resolved.rawQuery != null || resolved.rawFragment != null) fail()
        val path = resolved.rawPath.trimEnd('/')
        if (path == base.rawPath.trimEnd('/')) { if (!directory) fail(); self = true; continue }
        if (!path.startsWith(base.rawPath)) fail()
        val child = path.removePrefix(base.rawPath)
        if (child.isEmpty() || '/' in child) fail()
        val name = URI("http://placeholder/$child").path.removePrefix("/")
        if (name in listOf(".", "..") || name.any { it == '/' || it == '\\' || it.isISOControl() }) fail()
        consume(DavScanEntry(resolved.toASCIIString().trimEnd('/') + if (directory) "/" else "", name, directory, size, modified, version))
    }
    if (!self || !rootEnded) fail()
}
