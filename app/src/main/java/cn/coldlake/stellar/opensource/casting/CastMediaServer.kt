package cn.coldlake.stellar.opensource.casting

import android.content.Context
import android.net.Uri
import cn.coldlake.stellar.opensource.network.ignoreMediaCertificateErrors
import com.stellar.player.source.HttpSource
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class ByteRange(val start: Long, val end: Long) {
    val length get() = end - start + 1
    companion object {
        fun parse(value: String, length: Long): ByteRange? {
            if (length <= 0 || !value.startsWith("bytes=")) return null
            val parts = value.removePrefix("bytes=").split('-')
            if (parts.size != 2) return null
            if (parts[0].isEmpty()) {
                val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((length - suffix).coerceAtLeast(0), length - 1)
            }
            val start = parts[0].toLongOrNull()?.takeIf { it >= 0 && it < length } ?: return null
            val end = if (parts[1].isEmpty()) length - 1 else parts[1].toLongOrNull()?.takeIf { it >= start } ?: return null
            return ByteRange(start, end.coerceAtMost(length - 1))
        }
    }
}
internal class CastResponse(val code: Int, val headers: Map<String, String>, val input: InputStream? = null,
    private val release: () -> Unit = {}) : AutoCloseable {
    override fun close() { try { input?.close() } finally { release() } }
}
internal interface CastByteSource : AutoCloseable {
    fun open(head: Boolean, range: String?): CastResponse
    override fun close() = Unit
}

internal class LocalCastSource(context: Context, private val uri: Uri, private val mime: String) : CastByteSource {
    private val resolver = context.contentResolver
    override fun open(head: Boolean, range: String?): CastResponse {
        val afd = checkNotNull(resolver.openAssetFileDescriptor(uri, "r"))
        try {
            val length = afd.length
            require(length >= 0) // Unknown-length/non-random SAF providers are not copied in full.
            val requested = range?.let { ByteRange.parse(it, length) }
            if (range != null && requested == null) { afd.close(); return CastResponse(416, mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0")) }
            val stream = afd.createInputStream()
            stream.channel.position(afd.startOffset + (requested?.start ?: 0)) // Also validates seekability.
            val headers = mutableMapOf("Content-Type" to mime, "Content-Length" to (requested?.length ?: length).toString(), "Accept-Ranges" to "bytes")
            if (requested != null) headers["Content-Range"] = "bytes ${requested.start}-${requested.end}/$length"
            if (head) { stream.close(); afd.close(); return CastResponse(if (requested == null) 200 else 206, headers) }
            return CastResponse(if (requested == null) 200 else 206, headers, stream) { afd.close() }
        } catch (e: Exception) { afd.close(); throw e }
    }
}

internal class HttpCastSource(private val source: HttpSource, private val mime: String? = null) : CastByteSource {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .apply { if (!source.verifyTlsCertificates) ignoreMediaCertificateErrors() }.build()
    override fun open(head: Boolean, range: String?): CastResponse {
        val request = Request.Builder().url(source.address).method(if (head) "HEAD" else "GET", null)
            .header("User-Agent", source.userAgent).header("Accept-Encoding", "identity")
        source.headers.forEach { (key, value) -> request.header(key, value) }
        range?.let { request.header("Range", it) }
        val response = http.newCall(request.build()).execute()
        if (response.code !in setOf(200, 206, 416)) { response.close(); return CastResponse(502, mapOf("Content-Length" to "0")) }
        val headers = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges").mapNotNull { name -> response.header(name)?.let { name to it } }.toMap() +
            (mime?.let { mapOf("Content-Type" to it) } ?: emptyMap())
        return CastResponse(response.code, headers, if (head || response.code == 416) null else response.body.byteStream()) { response.close() }
    }
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}

/** One movie and one selected subtitle, a session URL and at most four active clients. */
internal class CastMediaServer(address: InetAddress, private val source: CastByteSource, extension: String) : AutoCloseable {
    private val path = "/${UUID.randomUUID()}/media.${extension.filter { it.isLetterOrDigit() }.take(8)}"
    private val listener = ServerSocket(0, 4, address)
    val url = "http://${address.hostAddress}:${listener.localPort}$path"
    private val mediaRequest = CompletableDeferred<Unit>()
    suspend fun awaitMediaRequest() = mediaRequest.await()
    private class SubtitleResource(val path: String, val source: CastSubtitle)
    @Volatile private var subtitle: SubtitleResource? = null
    private var subtitleVersion = 0L
    fun setSubtitle(value: CastSubtitle?): String? {
        val resource = value?.let { SubtitleResource("${path.substringBeforeLast('/')}/subtitle-${++subtitleVersion}.srt", it) }
        subtitle = resource
        return resource?.let { url.substringBeforeLast('/') + "/" + it.path.substringAfterLast('/') }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    init {
        scope.launch {
            try {
                while (isActive) {
                    val socket = listener.accept()
                    if (!isActive || sockets.size >= 4) { socket.close(); continue }
                    sockets += socket
                    launch { try { serve(socket) } catch (_: Exception) { /* No media URLs or headers in logs. */ }
                        finally { sockets -= socket; runCatching { socket.close() } } }
                }
            } catch (_: IOException) { /* Session close. */ }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8192) {
                val value = input.read(); if (value < 0) throw EOFException()
                if (value == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                bytes.write(value)
            }
            throw IOException("Header limit")
        }
        val output = socket.getOutputStream().buffered(64 * 1024)
        fun headers(code: Int, fields: Map<String, String>) {
            output.write(buildString {
                append("HTTP/1.1 $code Result\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                fields.forEach { (name, value) -> if ('\r' !in value && '\n' !in value) append("$name: $value\r\n") }
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)); output.flush()
        }
        val request = line().split(' ')
        val caption = subtitle
        if (request.size != 3 || (request[1] != path && request[1] != caption?.path)) { headers(404, mapOf("Content-Length" to "0")); return }
        val video = request[1] == path
        if (request[0] !in setOf("GET", "HEAD", "OPTIONS")) { headers(405, mapOf("Content-Length" to "0", "Allow" to "GET, HEAD, OPTIONS")); return }
        var range: String? = null
        var ended = false
        for (i in 0 until 64) {
            val header = line()
            if (header.isEmpty()) { ended = true; break }
            if (header.substringBefore(':').equals("Range", true)) range = header.substringAfter(':').trim()
        }
        if (!ended) { headers(431, mapOf("Content-Length" to "0")); return }
        if (request[0] == "OPTIONS") {
            headers(204, mapOf("Content-Length" to "0", "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS", "Access-Control-Allow-Headers" to "Range, Content-Type")); return
        }
        if (range != null && !range.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))) { headers(416, mapOf("Content-Length" to "0")); return }
        val response = try { (if (video) source else checkNotNull(caption).source).open(request[0] == "HEAD", range) }
            catch (_: Exception) { headers(502, mapOf("Content-Length" to "0")); return }
        response.use {
            if (video && request[0] == "GET" && it.code in setOf(200, 206)) mediaRequest.complete(Unit)
            val captionHeader = if (video && caption != null && it.code in setOf(200, 206))
                mapOf("CaptionInfo.sec" to (url.substringBeforeLast('/') + "/" + caption.path.substringAfterLast('/'))) else emptyMap()
            headers(it.code, it.headers + mapOf("transferMode.dlna.org" to if (video) "Streaming" else "Interactive") + captionHeader)
            if (request[0] != "HEAD" && it.input != null) {
                val buffer = ByteArray(64 * 1024)
                var left = it.headers["Content-Length"]?.toLongOrNull() ?: Long.MAX_VALUE
                while (left > 0) {
                    val count = it.input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count); left -= count
                }
                output.flush()
            }
        }
    }
    override fun close() {
        subtitle = null
        mediaRequest.cancel()
        scope.cancel(); runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }; sockets.clear(); source.close()
    }
}
