package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import cn.coldlake.stellar.opensource.medialibrary.cloud.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import com.stellar.player.source.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readFully
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Call
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.Semaphore
import kotlin.coroutines.EmptyCoroutineContext

internal data class FingerprintFile(val id: Long, val scope: String, val document: String, val size: Long?,
    val modified: Long?, val version: String?, val root: String, val rootRevision: Long) {
    val cloud get() = CloudLocator.isCloud(root)
    val reusable get() = (size ?: 0) > 0 && (if (cloud) !version.isNullOrBlank() else !version.isNullOrBlank() || (modified ?: 0) > 0)
    val stamp get() = JSONArray(listOf(scope, document, size, modified, version, root, rootRevision)).toString()
    val uri get() = when {
        cloud -> CloudLocator.file(root, document)
        NasLocator.isNas(root) -> NasLocator.file(root, document)
        else -> DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root), document).toString()
    }
}

internal fun interface FingerprintReader { suspend fun read(file: FingerprintFile): ColdLakeHashKey? }
internal interface FingerprintTask { fun calculate(): ColdLakeHashKey; fun cancel() }

/** The caller's timeout never waits for a blocking provider. One outstanding worker, no queued reads. */
internal class FileFingerprintReader(private val context: Context, private val budgetMillis: Long = 500,
    private val enabled: () -> Boolean = { true },
    private val createTask: (FingerprintFile) -> FingerprintTask = { FingerprintSession(context, it, enabled) }) : FingerprintReader {
    override suspend fun read(file: FingerprintFile): ColdLakeHashKey? {
        if (!file.reusable || !enabled()) return null
        return withTimeoutOrNull(budgetMillis) {
            suspendCancellableCoroutine { continuation ->
                if (!slot.tryAcquire()) { continuation.resumeWith(Result.success(null)); return@suspendCancellableCoroutine }
                val session = try { createTask(file) } catch (_: Exception) {
                    slot.release(); continuation.resumeWith(Result.success(null)); return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { session.cancel() }
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                    try {
                        val result = if (continuation.isActive) session.calculate() else null
                        continuation.resumeWith(Result.success(result))
                    } catch (_: Exception) { continuation.resumeWith(Result.success(null)) }
                    finally { try { session.cancel() } finally { slot.release() } }
                })
            }
        }
    }
    private companion object { val slot = Semaphore(1) }
}

private class FingerprintSession(private val context: Context, private val file: FingerprintFile,
    private val enabled: () -> Boolean) : FingerprintTask {
    private val lifetime = Job()
    private val signal = CancellationSignal()
    private val guard = Any()
    private var active: RemoteFile? = null
    private var localChannel: java.nio.channels.FileChannel? = null
    private fun check() { lifetime.ensureActive(); if (!enabled()) throw CancellationException("Fingerprint disabled") }
    override fun cancel() {
        lifetime.cancel(); signal.cancel()
        synchronized(guard) { active?.cancel(); localChannel?.close() }
    }
    private fun hash(reader: RemoteFile): ColdLakeHashKey {
        synchronized(guard) { active = reader; if (!lifetime.isActive) reader.cancel() }
        // Unregister before close: native cancel must never race a destroyed handle.
        return ColdLakeHash.calculate(object : RemoteFile by reader {
            override fun close() { synchronized(guard) { active = null }; reader.close() }
        }, ::check)
    }
    override fun calculate(): ColdLakeHashKey = runBlocking(lifetime) {
        check()
        val key = when {
            file.cloud -> cloud()
            NasLocator.isNas(file.root) -> nas()
            else -> local()
        }
        check()
        if (key.fileSize != file.size) throw IOException("File changed")
        key
    }
    private fun local(): ColdLakeHashKey {
        val uri = Uri.parse(file.uri)
        fun verify() {
            check()
            val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(uri, columns, null, null, null, signal)?.use {
                if (!it.moveToFirst() || it.isNull(0) || it.isNull(1) || it.getLong(0) != file.size || it.getLong(1) != file.modified)
                    throw IOException("Document changed")
            } ?: throw IOException("Document unavailable")
        }
        verify()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("Document unavailable")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            synchronized(guard) { localChannel = input.channel; if (!lifetime.isActive) input.channel.close() }
            try { ColdLakeHash.calculate(input.channel, ::check).also { verify() } }
            finally { synchronized(guard) { localChannel = null } }
        }
    }
    private suspend fun cloud(): ColdLakeHashKey {
        val ref = CloudLocator.ref(file.uri)
        val profile = ServerDatabase(context).use { CloudLocator.profile(file.uri, it.list()) }
        return cloudProvider(context, profile, ref).use { provider ->
            suspend fun verify() {
                val actual = provider.metadata(ref.id)
                if (actual.ref != ref || actual.deleted || actual.unsupported || actual.size != file.size || actual.version != file.version)
                    throw IOException("Cloud version changed")
            }
            verify()
            val reader = CloudFingerprintFile(provider, ref.id, requireNotNull(file.size))
            hash(reader).also { verify() }
        }
    }
    private suspend fun nas(): ColdLakeHashKey {
        val profiles = ServerDatabase(context).use { it.list() }
        val profile = NasLocator.profile(file.uri, profiles)
        val source = NasLibrarySource(context, profiles)
        val parent = if (profile.kind == ServerKind.WEBDAV) java.net.URI(file.document).resolve(".").toString()
            else file.document.substringBeforeLast('/', "")
        val root = ScanRoot("hash", file.scope, parent, file.root, file.rootRevision, "")
        suspend fun verify() {
            val actual = source.enumerate(root, parent).first { batch -> batch.entries.any { it.objectId == file.document } }
                .entries.first { it.objectId == file.document }
            if (actual.size != file.size || actual.modified != file.modified || actual.version != file.version)
                throw IOException("NAS version changed")
        }
        verify()
        val entry = RemoteEntry("", file.document, RemoteFileKind.VIDEO, file.size)
        val reader = when (profile.kind) {
            ServerKind.SMB -> NativeRemoteFile.smb(requireNotNull(profile.smbMedia(entry)))
            ServerKind.NFS -> NativeRemoteFile.nfs(requireNotNull(profile.nfsMedia(entry)))
            else -> DavFingerprintFile(WebDavClient.fromSettings(context, file.document,
                profile.username.ifEmpty { null }, profile.password.takeIf { profile.username.isNotEmpty() }), requireNotNull(file.size), file.version)
        }
        return hash(reader).also { verify() }
    }
}

/** Closed, bounded ranges only. A server ignoring Range never causes a full-file download. */
internal class CloudFingerprintFile(private val provider: CloudMediaClient, private val id: String,
    private val size: Long) : RemoteFile {
    private val lifetime = Job()
    @Volatile private var call: Call? = null
    private var position = 0L
    override fun open(): Long { lifetime.ensureActive(); return size }
    override fun seek(offset: Long) { lifetime.ensureActive(); require(offset in 0..size); position = offset }
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = runBlocking(lifetime) {
        val n = minOf(count.toLong(), size - position).toInt()
        if (n == 0) return@runBlocking -1
        val end = position + n - 1
        val response = provider.media(id, "bytes=$position-$end", false) {
            call = it; if (!lifetime.isActive) it.cancel()
        }
        try {
            response.let {
                if (it.code != 206 || it.header("Content-Range") != "bytes $position-$end/$size" ||
                    it.body.contentLength().let { length -> length >= 0 && length != n.toLong() }) throw IOException("Cloud range mismatch")
                var done = 0
                val input = it.body.byteStream()
                while (done < n) {
                    lifetime.ensureActive()
                    val read = input.read(buffer, offset + done, n - done)
                    if (read <= 0) throw IOException("Incomplete range")
                    done += read
                }
                position += n
                n
            }
        } finally { call?.cancel(); response.close(); call = null }
    }
    override fun cancel() { lifetime.cancel(); call?.cancel() }
    override fun close() = cancel()
}

private class DavFingerprintFile(private val client: WebDavClient, private val size: Long,
    private val version: String?) : RemoteFile {
    private val lifetime = Job()
    private var position = 0L
    override fun open(): Long { lifetime.ensureActive(); return size }
    override fun seek(offset: Long) { lifetime.ensureActive(); require(offset in 0..size); position = offset }
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = runBlocking(lifetime) {
        val n = minOf(count.toLong(), size - position).toInt()
        if (n == 0) return@runBlocking -1
        val end = position + n - 1
        client.stream("bytes=$position-$end", false) { response ->
            if (response.status.value != 206 || response.headers["Content-Range"] != "bytes $position-$end/$size" ||
                (!version.isNullOrBlank() && response.headers["ETag"] != version)) throw IOException("DAV range/version mismatch")
            response.bodyAsChannel().readFully(buffer, offset, n)
        }
        position += n; n
    }
    override fun cancel() { lifetime.cancel() }
    override fun close() { cancel(); client.close() }
}

internal const val CLOUD_FINGERPRINT_MIN_BYTES = 100L * 1024 * 1024
