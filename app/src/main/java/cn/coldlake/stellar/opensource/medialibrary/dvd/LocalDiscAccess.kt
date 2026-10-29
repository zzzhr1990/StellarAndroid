package cn.coldlake.stellar.opensource.medialibrary.dvd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Documents
import android.provider.OpenableColumns
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import com.stellar.player.disc.DiscSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

internal class DiscFolderPermissionException : IOException()

private class LocalDiscAccess(private val context: Context) : DiscAccess {
    private class OpenFile(val descriptor: ParcelFileDescriptor) : AutoCloseable {
        val input = FileInputStream(descriptor.fileDescriptor)
        override fun close() { runCatching { input.close() }; descriptor.close() }
    }
    private val files = linkedMapOf<String, OpenFile>()
    override suspend fun list(location: String): List<RemoteEntry> {
        val uri = Uri.parse(location)
        if (uri.scheme == "file") return File(checkNotNull(uri.path)).listFiles()?.map {
            RemoteEntry(it.name, Uri.fromFile(it).toString(), remoteFileKind(it.name, it.isDirectory), it.length())
        } ?: throw IOException("Disc directory unavailable")
        if (!Documents.isTreeUri(uri)) throw DiscFolderPermissionException()
        val id = runCatching { Documents.getDocumentId(uri) }.getOrElse { Documents.getTreeDocumentId(uri) }
        val children = Documents.buildChildDocumentsUriUsingTree(uri, id)
        val result = ArrayList<RemoteEntry>()
        val columns = arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
            Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE)
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (result.size >= 20_000) throw IOException("Disc directory too large")
                val name = cursor.getString(1)
                if (!validRemoteName(name)) continue
                result += RemoteEntry(name, Documents.buildDocumentUriUsingTree(uri, cursor.getString(0)).toString(),
                    remoteFileKind(name, cursor.getString(2) == Documents.Document.MIME_TYPE_DIR), if (cursor.isNull(3)) null else cursor.getLong(3))
            }
        } ?: throw IOException("Disc directory unavailable")
        return result
    }
    override suspend fun parent(entry: RemoteEntry): String {
        val uri = Uri.parse(entry.location)
        if (uri.scheme == "file") return Uri.fromFile(File(checkNotNull(uri.path)).parentFile!!).toString()
        // A single-document grant does not authorize sibling VOB/M2TS files.
        throw DiscFolderPermissionException()
    }
    override suspend fun size(entry: RemoteEntry): Long = entry.size ?: context.contentResolver.openFileDescriptor(Uri.parse(entry.location), "r")?.use {
        it.statSize.takeIf { size -> size >= 0 } ?: throw IOException("Disc requires seekable input")
    } ?: throw IOException("Disc unavailable")
    override fun read(location: String, offset: Long, length: Int): ByteArray? {
        val file = files[location] ?: run {
            if (files.size >= 4) files.remove(files.keys.first())?.close()
            OpenFile(context.contentResolver.openFileDescriptor(Uri.parse(location), "r") ?: return null).also { files[location] = it }
        }
        val data = ByteArray(length)
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            val count = file.input.channel.read(buffer, offset + buffer.position())
            if (count <= 0) return null
        }
        return data
    }
    override fun cancelRead() { /* Positional local reads are bounded; close runs on the IO owner. */ }
    override fun close() { files.values.forEach { runCatching { it.close() } }; files.clear() }
}

internal suspend fun openLocalDisc(context: Context, uri: Uri, tree: Boolean = false): DiscSource {
    val entry = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = File(checkNotNull(uri.path))
            RemoteEntry(file.name, uri.toString(), remoteFileKind(file.name, file.isDirectory), file.length())
        } else {
            val document = if (tree) Documents.buildDocumentUriUsingTree(uri, Documents.getTreeDocumentId(uri)) else uri
            context.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (!it.moveToFirst()) throw IOException("Disc unavailable")
                RemoteEntry(it.getString(0), document.toString(), if (tree) RemoteFileKind.FOLDER else RemoteFileKind.VIDEO,
                    it.getColumnIndex(OpenableColumns.SIZE).takeIf { column -> column >= 0 && !it.isNull(column) }?.let(it::getLong))
            } ?: throw IOException("Disc unavailable")
        }
    }
    return openDiscAccess(LocalDiscAccess(context), entry)
}
