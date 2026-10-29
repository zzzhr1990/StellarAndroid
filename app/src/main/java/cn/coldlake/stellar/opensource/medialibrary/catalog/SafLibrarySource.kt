package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import java.io.IOException
import java.util.Locale

internal class SafLibrarySource(private val resolver: ContentResolver) : LibraryFileSource {
    override fun enumerate(root: ScanRoot, directory: String) = flow {
        val signal = CancellationSignal()
        // Cancels a blocking provider query as soon as the collecting Job is cancelled.
        val watcher = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(root.treeUri), directory)
            resolver.query(uri, COLUMNS, null, null, null, signal)?.use { cursor ->
                val columns = COLUMNS.map { cursor.getColumnIndexOrThrow(it) }
                var batch = ArrayList<FileFact>(128)
                var bytes = 0
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getString(columns[0]) ?: throw IOException("Missing document ID")
                    val name = cursor.getString(columns[1]) ?: id
                    val mime = cursor.getString(columns[2]).orEmpty()
                    // Bound the application batch also for unusually long provider fields.
                    val estimate = (id.length + name.length) * 4 + 96
                    if (estimate > 1024 * 1024) throw IOException("Document fields exceed batch budget")
                    if (batch.isNotEmpty() && (batch.size >= 128 || bytes + estimate > 1024 * 1024)) {
                        emit(DiscoveryBatch(batch)); batch = ArrayList(128); bytes = 0
                    }
                    batch.add(FileFact(id, name, kind(name, mime),
                        if (cursor.isNull(columns[3])) null else cursor.getLong(columns[3]),
                        if (cursor.isNull(columns[4])) null else cursor.getLong(columns[4])))
                    bytes += estimate
                }
                if (batch.isNotEmpty()) emit(DiscoveryBatch(batch))
                val incomplete = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null
                emit(DiscoveryBatch(emptyList(), !incomplete, if (incomplete) Coverage.PARTIAL else Coverage.COMPLETE))
            } ?: throw IOException("Provider returned no cursor")
        } finally { watcher.cancel(); signal.cancel() }
    }.buffer(0).flowOn(Dispatchers.IO) // rendezvous backpressure; never flowOn's default 64-batch queue

    override suspend fun open(root: ScanRoot, objectId: String) = resolver.openInputStream(
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root.treeUri), objectId))
        ?: throw IOException("Document cannot be opened")

    companion object {
        private val nfoExtensions = setOf("nfo", "xml")
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
        private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "vob")
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        fun kind(name: String, mime: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                mime == DocumentsContract.Document.MIME_TYPE_DIR -> FileKind.DIRECTORY
                ext in nfoExtensions -> FileKind.NFO
                ext in imageExtensions -> FileKind.IMAGE
                mime.startsWith("video/") || ext in videoExtensions -> FileKind.VIDEO
                else -> FileKind.OTHER
            }
        }
    }
}
