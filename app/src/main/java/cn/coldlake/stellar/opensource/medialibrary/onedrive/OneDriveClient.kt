package cn.coldlake.stellar.opensource.medialibrary.onedrive

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.cloud.cloudReply
import cn.coldlake.stellar.opensource.medialibrary.cloud.CloudMediaClient
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import cn.coldlake.stellar.opensource.network.rateLimitedRead

internal const val ONEDRIVE_ADDRESS = "https://graph.microsoft.com/v1.0/"
internal fun validOneDriveId(value: String) = value.length in 1..256 &&
    value.none { it.isISOControl() || it in "/\\?#" } && value !in setOf(".", "..")

/** Personal/work/school default drive, delegated Files.Read only. No URL/token diagnostics. */
internal class OneDriveClient(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
    private val base: HttpUrl = ONEDRIVE_ADDRESS.toHttpUrl(),
    http: OkHttpClient = cn.coldlake.stellar.opensource.network.RemoteHttp.cloud,
    private val driveId: String? = null,
) : CloudMediaClient {
    constructor(context: Context, accountId: String, driveId: String? = null) : this({ force ->
        OneDriveAuthorization.token(context.applicationContext, accountId, force)
    }, driveId = driveId)
    private val closed = AtomicBoolean(false)
    private val http = cn.coldlake.stellar.opensource.network.HttpCallScope(http)
    private val downloadLock = Mutex()
    private class Download(val item: String, val url: HttpUrl, val expires: Long)
    private var download: Download? = null

    private suspend fun json(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        check(!closed.get())
        if (!sameOrigin(url, base) || !url.encodedPath.startsWith(base.encodedPath)) throw DirectoryFailure(ServerError.PROTOCOL)
        repeat(2) { attempt ->
            val token = tokenProvider(attempt == 1)
            currentCoroutineContext().ensureActive()
            check(!closed.get())
            val reply = rateLimitedRead {
                http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).cloudReply()
            }
            if (reply.status == 401 && attempt == 0) return@repeat
            if (reply.status == 410) throw cn.coldlake.stellar.opensource.medialibrary.cloud.CloudCursorExpired()
            if (reply.status !in 200..299) throw DirectoryFailure(error(reply.status))
            return@withContext try { JSONObject(reply.body) } catch (_: Exception) { throw DirectoryFailure(ServerError.PROTOCOL) }
        }
        throw DirectoryFailure(ServerError.ONEDRIVE_AUTH)
    }

    private fun item(id: String): HttpUrl.Builder {
        if (!validOneDriveId(id)) throw DirectoryFailure(ServerError.PATH)
        return base.newBuilder().apply {
            if (driveId == null) addPathSegments("me/drive") else addPathSegment("drives").addPathSegment(driveId)
            if (id == "root") addPathSegment("root") else addPathSegment("items").addPathSegment(id)
        }
    }

    suspend fun list(folder: String): List<RemoteEntry> =
        cn.coldlake.stellar.opensource.medialibrary.cloud.browseCloud(
            cn.coldlake.stellar.opensource.medialibrary.cloud.OneDriveCloudProvider(this, ""), folder)
    internal suspend fun metadata(id: String, fields: String) = json(item(id).addQueryParameter("\$select", fields).build())
    internal suspend fun account(): Pair<String, String> {
        val drive = json(base.newBuilder().addPathSegments("me/drive").addQueryParameter("\$select", "id,name,owner").build())
        val id = drive.optString("id").takeIf(::validOneDriveId) ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val label = drive.optJSONObject("owner")?.optJSONObject("user")?.optString("displayName")?.takeIf(String::isNotBlank)
            ?: drive.optString("name").takeIf(String::isNotBlank) ?: id
        return id to label
    }
    internal suspend fun defaultDriveId() = driveId ?: json(base.newBuilder().addPathSegments("me/drive")
        .addQueryParameter("\$select", "id").build()).optString("id").takeIf { it.isNotEmpty() }
        ?: throw DirectoryFailure(ServerError.PROTOCOL)
    internal suspend fun cloudPage(folder: String, cursor: String?, delta: Boolean, fields: String): JSONObject {
        val first = item(folder).addPathSegment(if (delta) "delta" else "children")
            .addQueryParameter("\$select", fields).addQueryParameter("\$top", "128").build()
        val page = cursor?.toHttpUrlOrNull() ?: if (cursor == null) first else throw DirectoryFailure(ServerError.PROTOCOL)
        if (!sameOrigin(page, first) || page.encodedPath != first.encodedPath) throw DirectoryFailure(ServerError.PROTOCOL)
        return json(page)
    }

    private fun downloadUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val controlledLoopback = base.scheme == "http" && base.host == "127.0.0.1" && url.scheme == "http" && url.host == "127.0.0.1"
        if ((!url.isHttps && !controlledLoopback) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
            throw DirectoryFailure(ServerError.PROTOCOL)
        return url
    }

    private suspend fun download(id: String, stale: HttpUrl? = null): HttpUrl = downloadLock.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        download?.takeIf { it.item == id && it.expires > now && it.url != stale }?.let { return@withLock it.url }
        // Some personal drives omit downloadUrl from projected responses, even when selected.
        // The normal driveItem response includes it; fetch only this one item's bounded metadata.
        val value = json(item(id).build())
        if (value.optString("id") != id || !value.has("file")) throw DirectoryFailure(ServerError.PROTOCOL)
        val url = downloadUrl(value.optString("@microsoft.graph.downloadUrl"))
        download = Download(id, url, now + 120_000)
        url
    }

    override suspend fun media(id: String, range: String?, head: Boolean, onCall: (Call) -> Unit): Response = withContext(Dispatchers.IO) {
        check(!closed.get())
        var url = download(id)
        repeat(2) { attempt ->
            val original = url
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                check(!closed.get())
                // A preauthenticated download URL must never receive the Graph Bearer token.
                val response = http.newCall(Request.Builder().url(url).method(if (head) "HEAD" else "GET", null)
                    .header("Accept-Encoding", "identity").apply { range?.let { header("Range", it) } }.build()).also(onCall).awaitOneDrive()
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location"); response.close()
                    if (++redirects > 5 || location == null) throw DirectoryFailure(ServerError.PROTOCOL)
                    url = downloadUrl(url.resolve(location)?.toString().orEmpty())
                    continue
                }
                if (response.code in setOf(401, 403, 404) && attempt == 0) {
                    response.close(); url = download(id, original); break
                }
                return@withContext response
            }
        }
        throw DirectoryFailure(ServerError.NETWORK)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            http.close()
        }
    }
    companion object {
        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port &&
            a.username.isEmpty() && a.password.isEmpty() && a.fragment == null
        private fun error(status: Int) = when (status) {
            401 -> ServerError.ONEDRIVE_AUTH
            403 -> ServerError.PERMISSION
            404 -> ServerError.PATH
            429 -> ServerError.LIMIT
            else -> ServerError.NETWORK
        }
    }
}

private suspend fun Call.awaitOneDrive(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
