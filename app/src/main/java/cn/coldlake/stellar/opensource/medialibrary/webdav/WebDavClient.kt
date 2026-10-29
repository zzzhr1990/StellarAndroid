package cn.coldlake.stellar.opensource.medialibrary.webdav

import cn.coldlake.stellar.opensource.network.checkRateLimit
import cn.coldlake.stellar.opensource.network.rateLimitedRead

import android.content.Context
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import cn.coldlake.stellar.opensource.network.RemoteHttp
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response.HrefRelation
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.clearAuthTokens
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.http.Url
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLException

/** Read-only, session-scoped WebDAV access. No UI, persistence, media IO or global logging changes. */
class WebDavClient(
    address: String,
    username: String? = null,
    password: String? = null,
    verifyTlsCertificates: Boolean = true,
) : Closeable {
    private val endpoint = parseEndpoint(address)
    @Volatile private var credentials = if (username == null && password == null) null else {
        require(!username.isNullOrEmpty() && password != null)
        BasicAuthCredentials(username, password)
    }
    private val logger = Logger.getAnonymousLogger().apply {
        level = Level.OFF
        useParentHandlers = false
    }
    private val authentication = mutableListOf<RememberedDavAuth>()
    private val boundary = createClientPlugin("WebDavOriginBoundary") {
        onRequest { request, _ ->
            if (!sameOrigin(request.url.build())) throw WebDavFailure(WebDavError.CROSS_ORIGIN)
            if (request.method.value !in setOf("PROPFIND", "GET", "HEAD")) throw WebDavFailure(WebDavError.PROTOCOL)
        }
        onResponse { response -> authentication.forEach { it.observe(response) } }
    }
    private val http = HttpClient(RemoteHttp.webDavEngine(verifyTlsCertificates)) {
        followRedirects = false // dav4jvm follows redirects; each resulting request passes the boundary.
        expectSuccess = false
        install(boundary)
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
        if (credentials != null) install(Auth) {
            // One challenge, or one nonce refresh for a previously accepted Digest proof.
            reAuthorizeOnResponse { it.status.value == 401 && (it.call.request.headers["Authorization"] == null ||
                it.call.request.attributes.getOrNull(RememberedDavAuth.PREEMPTIVE_DIGEST) == true) }
            digest { credentials { this@WebDavClient.credentials?.let { DigestAuthCredentials(it.username, it.password) } } }
            basic {
                credentials { this@WebDavClient.credentials }
                sendWithoutRequest { false }
            }
            val delegates = providers.toList()
            providers.clear()
            delegates.forEach { delegate ->
                RememberedDavAuth(delegate, ::sameOrigin).also { authentication += it; providers += it }
            }
        }
    }

    /** Verifies a real DAV collection using Depth: 0; OPTIONS success alone is insufficient. */
    suspend fun checkConnection(): WebDavEntry = query(depth = 0).first

    /** Lists one collection, excluding the collection itself. Never recursively scans the server. */
    suspend fun listDirectory(): List<WebDavEntry> = query(depth = 1).second

    /** A separate scan path: no aggregate XML/List and no browser's 10,000-item limit. */
    internal suspend fun scanDirectory(address: String = endpoint.toString(), consume: suspend (DavScanEntry) -> Unit) = withContext(Dispatchers.IO) { rateLimitedRead {
        val target = target(address)
        http.prepareRequest(target) {
            method = HttpMethod("PROPFIND")
            header("Depth", "1"); header("Content-Type", "application/xml; charset=utf-8")
            setBody("""<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>""")
            // Socket timeout still bounds a stalled server; wide active streams have no total deadline.
            timeout { requestTimeoutMillis = Long.MAX_VALUE }
        }.execute { response ->
            checkRateLimit(response.status.value, response.headers["Retry-After"])
            if (response.status.value != 207) throw failureForStatus(response.status.value)
            response.bodyAsChannel().toInputStream().use { input ->
                readDavScan(input, target.toString(), consume)
            }
        }
    } }

    internal suspend fun readSmallFile(limit: Int, address: String = endpoint.toString()): ByteArray = withContext(Dispatchers.IO) { rateLimitedRead {
        var bytes: ByteArray? = null
        stream(null, false, address) { response ->
            checkRateLimit(response.status.value, response.headers["Retry-After"])
            if (response.status.value != 200) throw failureForStatus(response.status.value)
            response.bodyAsChannel().toInputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                val value = out.toByteArray()
                if (value.size > limit) throw WebDavFailure(WebDavError.LIMIT)
                bytes = value
            }
        }
        requireNotNull(bytes)
    } }

    /** Stream a single selected file; auth and TLS policy are shared with directory access. */
    internal suspend fun stream(range: String?, head: Boolean, address: String = endpoint.toString(), consume: suspend (HttpResponse) -> Unit) {
        http.prepareRequest(target(address)) {
            method = if (head) HttpMethod.Head else HttpMethod.Get
            header("Accept-Encoding", "identity")
            range?.let { header("Range", it) }
            timeout { requestTimeoutMillis = Long.MAX_VALUE }
        }.execute { consume(it) }
    }

    private suspend fun query(depth: Int): Pair<WebDavEntry, List<WebDavEntry>> = withContext(Dispatchers.IO) {
        try {
            var self: WebDavEntry? = null
            var count = 0
            val entries = mutableListOf<WebDavEntry>()
            DavResource(http, endpoint, logger).propfind(depth, WebDAV.ResourceType, WebDAV.GetContentLength).collect { item ->
                if (item is MultiStatusItem.Response) {
                    if (++count > 10_000) throw WebDavFailure(WebDavError.LIMIT)
                    val response = item.response
                    if (!sameOrigin(response.href) || response.href.user != null || response.href.password != null ||
                        response.href.parameters.isEmpty().not() || response.href.fragment.isNotEmpty()) {
                        throw WebDavFailure(WebDavError.CROSS_ORIGIN)
                    }
                    if (!response.isSuccess()) throw failureForStatus(response.status?.value)
                    val type = response[ResourceType::class.java]
                        ?: throw WebDavFailure(WebDavError.PROTOCOL)
                    val entry = WebDavEntry(
                        address = response.href.toString(),
                        name = response.hrefName(),
                        isDirectory = WebDAV.Collection in type.types,
                        sizeBytes = response[GetContentLength::class.java]?.contentLength?.takeIf { it >= 0 },
                    )
                    when (item.relation) {
                        HrefRelation.SELF -> self = entry
                        HrefRelation.MEMBER -> entries += entry
                        HrefRelation.OTHER -> throw WebDavFailure(WebDavError.PROTOCOL)
                    }
                }
            }
            val root = self ?: throw WebDavFailure(WebDavError.PROTOCOL)
            if (!root.isDirectory) throw WebDavFailure(WebDavError.NOT_COLLECTION)
            root to entries.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebDavFailure) {
            throw e
        } catch (e: HttpException) {
            throw failureForStatus(e.statusCode)
        } catch (_: SSLException) {
            throw WebDavFailure(WebDavError.TLS)
        } catch (_: SocketTimeoutException) {
            throw WebDavFailure(WebDavError.TIMEOUT)
        } catch (_: HttpRequestTimeoutException) {
            throw WebDavFailure(WebDavError.TIMEOUT)
        } catch (_: DavException) {
            throw WebDavFailure(WebDavError.PROTOCOL)
        } catch (_: IOException) {
            throw WebDavFailure(WebDavError.NETWORK)
        }
    }

    private fun sameOrigin(url: Url) = url.protocol == endpoint.protocol &&
        url.host == endpoint.host && url.port == endpoint.port

    private fun target(address: String): Url = parseEndpoint(address).also {
        if (!sameOrigin(it)) throw WebDavFailure(WebDavError.CROSS_ORIGIN)
    }

    override fun close() {
        credentials = null
        http.clearAuthTokens()
        http.close()
    }

    companion object {
        /** Snapshots the current setting; reconnect to apply a later policy change. */
        fun fromSettings(context: Context, address: String, username: String? = null, password: String? = null): WebDavClient =
            WebDavClient(address, username, password,
                verifyTlsCertificates = !MediaSecuritySettings(context).ignoreCertificateErrors)

        private fun parseEndpoint(address: String): Url {
            // Validate before Ktor normalization; credentials belong in separate arguments.
            val uri = try { URI(address.trim()) } catch (_: Exception) { throw IllegalArgumentException("Invalid WebDAV address") }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty())
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            return Url(uri.toASCIIString())
        }

        private fun failureForStatus(status: Int?) = WebDavFailure(when (status) {
            401 -> WebDavError.AUTHENTICATION
            403 -> WebDavError.FORBIDDEN
            404 -> WebDavError.NOT_FOUND
            else -> WebDavError.HTTP
        }, status)
    }
}

data class WebDavEntry(val address: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long?)

enum class WebDavError { AUTHENTICATION, FORBIDDEN, NOT_FOUND, NOT_COLLECTION, TLS, TIMEOUT, NETWORK, HTTP, PROTOCOL, CROSS_ORIGIN, LIMIT }

/** Contains only stable semantics/status, never the upstream exception with HTTP request/body excerpts. */
class WebDavFailure(val reason: WebDavError, val httpStatus: Int? = null) : IOException(reason.name)
