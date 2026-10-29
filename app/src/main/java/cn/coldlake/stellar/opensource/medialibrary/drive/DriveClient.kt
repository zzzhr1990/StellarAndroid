package cn.coldlake.stellar.opensource.medialibrary.drive

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.cloud.cloudReply
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import cn.coldlake.stellar.opensource.network.RemoteHttp
import cn.coldlake.stellar.opensource.network.HttpCallScope
import cn.coldlake.stellar.opensource.network.rateLimitedRead
import kotlin.coroutines.resumeWithException

internal const val DRIVE_ADDRESS = "https://www.googleapis.com/drive/v3/"
internal fun validDriveId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))

/** Drive v3, bounded metadata pages and streaming media. Never logs URLs or response bodies. */
internal class DriveClient(
    private val tokenProvider: suspend () -> String,
    private val invalidate: suspend (String) -> Unit = {},
    private val base: HttpUrl = DRIVE_ADDRESS.toHttpUrl(),
    http: OkHttpClient = RemoteHttp.cloud,
) : cn.coldlake.stellar.opensource.medialibrary.cloud.CloudMediaClient {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val http = HttpCallScope(http)
    constructor(context: Context, email: String) : this(
        { DriveAuthorization.token(context.applicationContext, email) },
        { DriveAuthorization.invalidate(context.applicationContext, email, it) })

    private suspend fun execute(builder: Request.Builder, onCall: (Call) -> Unit): Response = withContext(Dispatchers.IO) {
        check(!closed.get())
        repeat(2) { attempt ->
            val token = tokenProvider()
            currentCoroutineContext().ensureActive(); check(!closed.get())
            val response = http.newCall(builder.header("Authorization", "Bearer $token").build()).also(onCall).awaitResponse()
            if (response.code != 401 || attempt == 1) return@withContext response
            response.close(); invalidate(token)
        }
        throw DirectoryFailure(ServerError.DRIVE_AUTH)
    }
    private suspend fun json(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        check(!closed.get())
        repeat(2) { attempt ->
            val token = tokenProvider()
            currentCoroutineContext().ensureActive(); check(!closed.get())
            val reply = rateLimitedRead {
                http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).cloudReply()
            }
            if (reply.status == 401 && attempt == 0) { invalidate(token); return@repeat }
            if (reply.status == 410 || reply.status == 400 && url.queryParameter("pageToken") != null)
                throw cn.coldlake.stellar.opensource.medialibrary.cloud.CloudCursorExpired()
            if (reply.status !in 200..299) throw DirectoryFailure(error(reply.status, reply.body))
            return@withContext try { JSONObject(reply.body) } catch (_: Exception) { throw DirectoryFailure(ServerError.PROTOCOL) }
        }
        throw DirectoryFailure(ServerError.DRIVE_AUTH)
    }
    suspend fun account(): String = json(base.newBuilder().addPathSegment("about")
        .addQueryParameter("fields", "user(emailAddress)").build())
        .optJSONObject("user")?.optString("emailAddress")?.takeIf { it.isNotBlank() }
        ?: throw DirectoryFailure(ServerError.DRIVE_AUTH)

    suspend fun list(folder: String): List<RemoteEntry> =
        cn.coldlake.stellar.opensource.medialibrary.cloud.browseCloud(
            cn.coldlake.stellar.opensource.medialibrary.cloud.DriveCloudProvider(this, ""), folder)
    internal suspend fun api(path: String, params: Map<String, String?>): JSONObject = json(
        base.newBuilder().apply {
            path.split('/').forEach { addPathSegment(it) }
            params.forEach { (key, value) -> value?.let { addQueryParameter(key, it) } }
        }.build())
    override suspend fun media(id: String, range: String?, head: Boolean, onCall: (Call) -> Unit): Response {
        if (!validDriveId(id)) throw DirectoryFailure(ServerError.PATH)
        return execute(Request.Builder().url(base.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("alt", "media").addQueryParameter("supportsAllDrives", "true").build())
            .method(if (head) "HEAD" else "GET", null).header("Accept-Encoding", "identity")
            .apply { range?.let { header("Range", it) } }, onCall)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            http.close()
        }
    }
    companion object {
        internal fun error(status: Int, body: String = "") = when (status) {
            401 -> ServerError.DRIVE_AUTH
            403 -> if ("accessNotConfigured" in body || "SERVICE_DISABLED" in body) ServerError.DRIVE_CONFIG
                else if ("rateLimitExceeded" in body || "downloadQuotaExceeded" in body) ServerError.LIMIT else ServerError.PERMISSION
            404 -> ServerError.PATH
            429 -> ServerError.LIMIT
            else -> ServerError.NETWORK
        }
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
