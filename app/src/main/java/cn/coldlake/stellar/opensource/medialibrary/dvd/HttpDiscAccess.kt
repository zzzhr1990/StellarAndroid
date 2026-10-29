package cn.coldlake.stellar.opensource.medialibrary.dvd

import android.content.Context
import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import cn.coldlake.stellar.opensource.network.ignoreMediaCertificateErrors
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readFully
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP(S) ISO needs Range; directory form uses same-origin direct-child HTML indexes.
 * DAV keeps its established authentication, enumeration and TLS implementation. */
internal class HttpDiscAccess(private val context: Context, private val profile: ServerProfile? = null) : DiscAccess {
    private val dav = linkedMapOf<String, WebDavClient>()
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).apply {
            if (MediaSecuritySettings(context).ignoreCertificateErrors) ignoreMediaCertificateErrors()
        }.build()
    @Volatile private var call: Call? = null
    @Volatile private var pending: Job? = null
    private val sizes = mutableMapOf<String, Long>()
    private fun client(location: String): WebDavClient = dav[location] ?: run {
        if (dav.size >= 4) dav.remove(dav.keys.first())?.close()
        WebDavClient.fromSettings(context, location, profile?.username?.ifEmpty { null },
            profile?.password?.takeIf { profile.username.isNotEmpty() }).also { dav[location] = it }
    }
    private fun request(location: String, range: String? = null, head: Boolean = false): Response {
        val url = location.toHttpUrl()
        val request = Request.Builder().url(url.newBuilder().username("").password("").build()).apply {
            if (url.username.isNotEmpty()) header("Authorization", Credentials.basic(url.username, url.password))
            if (head) head()
            range?.let { header("Range", it) }
            header("Accept-Encoding", "identity")
        }.build()
        val active = http.newCall(request); call = active
        return active.execute()
    }
    override suspend fun size(entry: RemoteEntry): Long {
        val known = entry.size
        val size = if (known != null && known >= 0) known else if (profile != null) {
            var value: Long? = null
            client(entry.location).stream("bytes=0-0", false) { response ->
                if (response.status.value == 206) value = response.headers["Content-Range"]?.substringAfterLast('/')?.toLongOrNull()
            }
            value ?: throw IOException("Disc size unavailable")
        } else request(entry.location, "bytes=0-0").use {
            if (it.code != 206 || !it.header("Content-Range").orEmpty().startsWith("bytes 0-0/")) throw IOException("Disc range unsupported")
            it.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: throw IOException("Disc size unavailable")
        }
        sizes[entry.location] = size
        return size
    }
    override suspend fun list(location: String): List<RemoteEntry> {
        if (profile != null) return ProtocolDirectory(context).list(profile, location)
        val original = location.toHttpUrl()
        val base = original.newBuilder().apply { if (!original.encodedPath.endsWith('/')) addPathSegment("") }.build()
        val text = request(base.toString()).use { response ->
            if (response.code != 200 || !response.header("Content-Type").orEmpty().contains("text/html", true)) throw IOException("HTTP directory index unavailable")
            val source = response.body.source(); source.request(2L * 1024 * 1024 + 1)
            if (source.buffer.size > 2 * 1024 * 1024) throw IOException("HTTP directory index too large")
            source.readUtf8()
        }
        val html: Spanned = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        val result = linkedMapOf<String, RemoteEntry>()
        for (link in html.getSpans(0, html.length, URLSpan::class.java)) {
            val url = base.resolve(link.url) ?: continue
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port || url.fragment != null) continue
            val suffix = url.encodedPath.removePrefix(base.encodedPath)
            if (!url.encodedPath.startsWith(base.encodedPath) || suffix.trimEnd('/').contains('/') || suffix.isEmpty()) continue
            val name = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
            if (!validRemoteName(name)) continue
            val directory = url.encodedPath.endsWith('/')
            val authenticated = url.newBuilder().username(base.username).password(base.password).build().toString()
            result[authenticated] = RemoteEntry(name, authenticated, remoteFileKind(name, directory), null)
            if (result.size > 20_000) throw IOException("Disc directory too large")
        }
        if (result.isEmpty()) throw IOException("HTTP directory index unavailable")
        return result.values.toList()
    }
    override suspend fun parent(entry: RemoteEntry): String = entry.location.toHttpUrl().resolve(".")!!.toString()
    override fun read(location: String, offset: Long, length: Int): ByteArray? {
        val range = "bytes=$offset-${offset + length - 1}"
        val expected = "bytes $offset-${offset + length - 1}/${sizes[location] ?: return null}"
        return if (profile != null) runBlocking(Dispatchers.IO) {
            pending = currentCoroutineContext().job
            try {
                val out = ByteArray(length)
                client(location).stream(range, false) { response ->
                    if (response.status.value != 206 || response.headers["Content-Range"] != expected) throw IOException("Disc range mismatch")
                    response.bodyAsChannel().readFully(out)
                }
                out
            } finally { pending = null }
        } else request(location, range).use {
            if (it.code != 206 || it.header("Content-Range") != expected) throw IOException("Disc range mismatch")
            it.body.source().readByteArray(length.toLong())
        }
    }
    override fun cancelRead() { call?.cancel(); pending?.cancel() }
    override fun close() {
        cancelRead(); dav.values.forEach { it.close() }; dav.clear()
        http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }
}

internal suspend fun openHttpDisc(context: Context, address: String): com.stellar.player.disc.DiscSource {
    val url = address.toHttpUrl()
    val name = url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
    return openDiscAccess(HttpDiscAccess(context), RemoteEntry(name, address,
        if (url.encodedPath.endsWith('/') || name.equals("VIDEO_TS", true) || name.equals("BDMV", true)) RemoteFileKind.FOLDER else RemoteFileKind.VIDEO, null))
}
