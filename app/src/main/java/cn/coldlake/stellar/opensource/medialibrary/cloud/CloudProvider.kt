package cn.coldlake.stellar.opensource.medialibrary.cloud

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.drive.DriveClient
import cn.coldlake.stellar.opensource.medialibrary.onedrive.OneDriveClient
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import cn.coldlake.stellar.opensource.network.checkRateLimit
import cn.coldlake.stellar.opensource.network.MAX_MEDIA_API_RESPONSE_BYTES
import cn.coldlake.stellar.opensource.network.rateLimitedRead

internal enum class CloudKind { DRIVE, ONEDRIVE }
internal data class CloudRef(val provider: CloudKind, val account: String, val drive: String, val id: String)
internal data class CloudCapabilities(val initialDelta: Boolean, val sharedDrives: Boolean = false,
    val shortcuts: Boolean = false, val stableIds: Boolean = true)
internal data class CloudObject(val ref: CloudRef, val parent: String?, val name: String,
    val kind: RemoteFileKind, val size: Long? = null, val modified: Long? = null,
    val version: String? = null, val deleted: Boolean = false, val unsupported: Boolean = false)
internal data class CloudPage(val objects: List<CloudObject>, val next: String?, val terminal: String? = null,
    val complete: Boolean = true)
internal class CloudCursorExpired : IOException("Cloud checkpoint expired")

internal data class CloudHttpReply(val status: Int, val body: String)
internal suspend fun okhttp3.Call.cloudReply(): CloudHttpReply = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, error: IOException) { continuation.resumeWith(Result.failure(error)) }
        override fun onResponse(call: okhttp3.Call, response: Response) {
            try {
                val value = response.use {
                    checkRateLimit(it.code, it.header("Retry-After"))
                    val limit = if (it.isSuccessful) MAX_MEDIA_API_RESPONSE_BYTES else 16 * 1024
                    val source = it.body.source(); source.request(limit.toLong() + 1)
                    if (it.isSuccessful && source.buffer.size > limit) throw DirectoryFailure(ServerError.LIMIT)
                    CloudHttpReply(it.code, source.buffer.readUtf8(minOf(source.buffer.size, limit.toLong())))
                }
                continuation.resumeWith(Result.success(value))
            } catch (error: Exception) { continuation.resumeWith(Result.failure(error)) }
        }
    })
}

/** A provider session owns requests and expiring downloads. Cursors are opaque to consumers. */
internal interface CloudProvider : CloudMediaClient {
    val capabilities: CloudCapabilities
    suspend fun metadata(id: String): CloudObject
    suspend fun children(folder: String, cursor: String? = null): CloudPage
    suspend fun start(): String?
    suspend fun changes(cursor: String?): CloudPage
}

internal fun cloudProvider(context: Context, profile: ServerProfile, reference: CloudRef? = null): CloudProvider = when (profile.kind) {
    ServerKind.GOOGLE_DRIVE -> DriveCloudProvider(DriveClient(context, profile.username), profile.username)
    ServerKind.ONEDRIVE -> OneDriveCloudProvider(OneDriveClient(context, profile.cloudAccountId, reference?.drive), profile.cloudAccountId, reference?.drive)
    else -> throw DirectoryFailure(ServerError.PROTOCOL)
}

internal suspend fun browseCloud(provider: CloudProvider, folder: String): List<RemoteEntry> {
    val entries = linkedMapOf<String, RemoteEntry>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = provider.children(folder, cursor)
        if (!page.complete) throw DirectoryFailure(ServerError.LIMIT)
        page.objects.filterNot { it.deleted }.forEach {
            entries[it.ref.id] = RemoteEntry(it.name, it.ref.id, it.kind, it.size)
            if (entries.size > 10_000) throw DirectoryFailure(ServerError.LIMIT)
        }
        cursor = page.next
        if (cursor != null && (!seen.add(cursor) || seen.size >= 100)) throw DirectoryFailure(ServerError.LIMIT)
    } while (cursor != null)
    return entries.values.toList()
}

internal class CloudReadLimitExceeded : IOException("Cloud sidecar exceeds limit")
internal suspend fun CloudProvider.smallFile(id: String, limit: Int): ByteArray = rateLimitedRead {
    val owner = this
    val response = media(id, "bytes=0-$limit", false)
    suspendCancellableCoroutine { continuation ->
        // Cancel the HTTP call through its owner, never close a buffered source concurrently
        // with the reader. The reader alone owns response.close().
        continuation.invokeOnCancellation { owner.close() }
        readWorkers.execute {
            try {
                val bytes = response.use {
                    checkRateLimit(it.code, it.header("Retry-After"))
                    if (it.code !in setOf(200, 206)) throw IOException("Cloud media request failed")
                    val source = it.body.source(); source.request(limit.toLong() + 1)
                    if (source.buffer.size > limit) throw CloudReadLimitExceeded()
                    source.buffer.readByteArray()
                }
                continuation.resumeWith(Result.success(bytes))
            } catch (error: Exception) { continuation.resumeWith(Result.failure(error)) }
        }
    }
}
private val readWorkers = java.util.concurrent.Executors.newFixedThreadPool(4) { task ->
    Thread(task, "cloud-small-read").apply { isDaemon = true }
}
private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotEmpty() && it != "null" }
private fun JSONObject.modified() = text("modifiedTime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    ?: text("lastModifiedDateTime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal class DriveCloudProvider(private val client: DriveClient, private val account: String) : CloudProvider {
    override val capabilities = CloudCapabilities(initialDelta = false)
    private val fields = "id,parents,name,mimeType,size,modifiedTime,version,trashed,driveId,shortcutDetails"
    private fun objectValue(v: JSONObject, parent: String? = null): CloudObject {
        val id = v.text("id")?.takeIf { cn.coldlake.stellar.opensource.medialibrary.drive.validDriveId(it) }
            ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val mime = v.optString("mimeType")
        val unsupported = v.has("driveId") || mime.startsWith("application/vnd.google-apps.") && mime != "application/vnd.google-apps.folder"
        val name = v.text("name") ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val kind = when {
            unsupported -> RemoteFileKind.OTHER
            mime == "application/vnd.google-apps.folder" -> RemoteFileKind.FOLDER
            mime.startsWith("application/vnd.google-apps.") -> RemoteFileKind.OTHER
            mime.startsWith("video/") -> RemoteFileKind.VIDEO
            mime.startsWith("audio/") -> RemoteFileKind.AUDIO
            else -> remoteFileKind(name, false)
        }
        return CloudObject(CloudRef(CloudKind.DRIVE, account, v.text("driveId") ?: "my-drive", id),
            v.optJSONArray("parents")?.optString(0)?.takeIf(String::isNotEmpty) ?: parent, name, kind,
            v.text("size")?.toLongOrNull(), v.modified(), v.text("version"), v.optBoolean("trashed"), unsupported)
    }
    override suspend fun metadata(id: String) = objectValue(client.api("files/$id", mapOf("fields" to fields)))
    override suspend fun children(folder: String, cursor: String?): CloudPage {
        if (!cn.coldlake.stellar.opensource.medialibrary.drive.validDriveId(folder)) throw DirectoryFailure(ServerError.PATH)
        val v = client.api("files", mapOf("q" to "'$folder' in parents and trashed = false", "pageSize" to "128",
            "orderBy" to "folder,name", "spaces" to "drive", "fields" to "nextPageToken,incompleteSearch,files($fields)",
            "pageToken" to cursor))
        val values = v.optJSONArray("files") ?: throw DirectoryFailure(ServerError.PROTOCOL)
        return CloudPage(List(values.length()) { objectValue(values.getJSONObject(it), folder) }, v.text("nextPageToken"),
            complete = !v.optBoolean("incompleteSearch"))
    }
    override suspend fun start() = client.api("changes/startPageToken", mapOf("fields" to "startPageToken"))
        .text("startPageToken") ?: throw DirectoryFailure(ServerError.PROTOCOL)
    override suspend fun changes(cursor: String?): CloudPage {
        val v = client.api("changes", mapOf("pageToken" to requireNotNull(cursor), "pageSize" to "128", "spaces" to "drive",
            "restrictToMyDrive" to "true", "includeRemoved" to "true",
            "fields" to "nextPageToken,newStartPageToken,changes(fileId,removed,file($fields))"))
        val values = v.optJSONArray("changes") ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val objects = List(values.length()) { index ->
            val change = values.getJSONObject(index)
            if (change.optBoolean("removed")) CloudObject(CloudRef(CloudKind.DRIVE, account, "my-drive",
                change.text("fileId") ?: throw DirectoryFailure(ServerError.PROTOCOL)), null, "", RemoteFileKind.OTHER, deleted = true)
            else objectValue(change.optJSONObject("file") ?: throw DirectoryFailure(ServerError.PROTOCOL))
        }
        val next = v.text("nextPageToken"); val terminal = v.text("newStartPageToken")
        if (next == null && terminal == null) throw DirectoryFailure(ServerError.PROTOCOL)
        return CloudPage(objects, next, terminal)
    }
    override suspend fun media(id: String, range: String?, head: Boolean, onCall: (okhttp3.Call) -> Unit): Response = client.media(id, range, head, onCall)
    override fun close() = client.close()
}

internal class OneDriveCloudProvider(private val client: OneDriveClient, private val account: String,
    private var scopedDrive: String? = null) : CloudProvider {
    override val capabilities = CloudCapabilities(initialDelta = true)
    private val fields = "id,name,size,file,folder,remoteItem,package,deleted,parentReference,lastModifiedDateTime,eTag,cTag,root"
    private fun objectValue(v: JSONObject, parent: String? = null): CloudObject {
        val id = v.text("id") ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val name = v.text("name").orEmpty()
        val deleted = v.has("deleted")
        if (!deleted && name.isEmpty()) throw DirectoryFailure(ServerError.PROTOCOL)
        val unsupported = v.has("remoteItem") || v.has("package")
        val mime = v.optJSONObject("file")?.optString("mimeType").orEmpty()
        val kind = when {
            unsupported || deleted -> RemoteFileKind.OTHER
            v.has("folder") -> RemoteFileKind.FOLDER
            !v.has("file") -> RemoteFileKind.OTHER
            mime.startsWith("video/") -> RemoteFileKind.VIDEO
            mime.startsWith("audio/") -> RemoteFileKind.AUDIO
            else -> remoteFileKind(name, false)
        }
        val p = v.optJSONObject("parentReference")
        return CloudObject(CloudRef(CloudKind.ONEDRIVE, account, p?.text("driveId") ?: scopedDrive.orEmpty(), id),
            p?.text("id") ?: parent, name, kind, v.text("size")?.toLongOrNull(), v.modified(),
            v.text("cTag") ?: v.text("eTag"), deleted, unsupported)
    }
    override suspend fun metadata(id: String): CloudObject {
        val item = objectValue(client.metadata(id, fields))
        val result = if (item.ref.drive.isNotEmpty()) item else item.copy(ref = item.ref.copy(drive = client.defaultDriveId()))
        scopedDrive = result.ref.drive
        return result
    }
    override suspend fun children(folder: String, cursor: String?): CloudPage = page(client.cloudPage(folder, cursor, false, fields), folder)
    override suspend fun start(): String? = null
    override suspend fun changes(cursor: String?): CloudPage {
        if (scopedDrive == null) scopedDrive = client.defaultDriveId()
        return page(client.cloudPage("root", cursor, true, fields), null, true)
    }
    private fun page(v: JSONObject, parent: String?, delta: Boolean = false): CloudPage {
        val values = v.optJSONArray("value") ?: throw DirectoryFailure(ServerError.PROTOCOL)
        val next = v.text("@odata.nextLink"); val terminal = v.text("@odata.deltaLink")
        if (delta && next == null && terminal == null) throw DirectoryFailure(ServerError.PROTOCOL)
        return CloudPage(List(values.length()) { objectValue(values.getJSONObject(it), parent) }, next, terminal)
    }
    override suspend fun media(id: String, range: String?, head: Boolean, onCall: (okhttp3.Call) -> Unit): Response = client.media(id, range, head, onCall)
    override fun close() = client.close()
}
