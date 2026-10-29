package cn.coldlake.stellar.opensource.platform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.io.OutputStream

internal enum class ScreenshotDestination { ALBUM, FOLDER }

internal class ScreenshotPreferences(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("screenshots", Context.MODE_PRIVATE)
    var destination: ScreenshotDestination
        get() = if (preferences.getBoolean("folder", false)) ScreenshotDestination.FOLDER else ScreenshotDestination.ALBUM
        set(value) { preferences.edit { putBoolean("folder", value == ScreenshotDestination.FOLDER) } }
    val folder: Uri? get() = preferences.getString("tree", null)?.let(Uri::parse)
    val folderLabel: String? get() = preferences.getString("label", null)
    fun saveFolder(uri: Uri, label: String) { preferences.edit { putString("tree", uri.toString()); putString("label", label) } }
}

internal class ScreenshotFolderUnavailable : IOException()

/** Encodes off the UI thread. A failed write removes only the entry created for this capture. */
internal class ScreenshotStorage(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun hasFolderPermission(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    fun rememberFolder(uri: Uri, grantedFlags: Int, preferences: ScreenshotPreferences) {
        if (grantedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) throw SecurityException()
        val flags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        resolver.takePersistableUriPermission(uri, flags)
        if (!hasFolderPermission(uri)) throw SecurityException()
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val label = resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw ScreenshotFolderUnavailable()
        preferences.saveFolder(uri, label)
    }

    fun save(bitmap: Bitmap, folder: Uri?): Uri {
        return saveContent("png", "image/png", false, folder) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException()
        }
    }

    fun saveExport(file: File, video: Boolean, folder: Uri?, checkActive: () -> Unit): Uri =
        saveContent(if (video) "mp4" else "gif", if (video) "video/mp4" else "image/gif", video, folder, checkActive) { output ->
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    output.write(bytes, 0, count)
                }
            }
        }

    fun deleteExport(uri: Uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
        else if (uri.scheme == "file") { uri.path?.let { File(it).delete() } }
        else resolver.delete(uri, null, null)
    }

    private fun saveContent(extension: String, mime: String, video: Boolean, folder: Uri?,
        checkActive: () -> Unit = {}, write: (OutputStream) -> Unit): Uri {
        checkActive()
        val name = "StellarPlayer_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())}_${UUID.randomUUID().toString().take(8)}.$extension"
        fun writeUri(uri: Uri) {
            (resolver.openOutputStream(uri, "w") ?: throw IOException()).use(write)
            checkActive()
        }
        if (folder != null) {
            if (!hasFolderPermission(folder)) throw SecurityException()
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val uri = try { DocumentsContract.createDocument(resolver, parent, mime, name) }
                catch (_: java.io.FileNotFoundException) { throw ScreenshotFolderUnavailable() }
                ?: throw ScreenshotFolderUnavailable()
            try {
                writeUri(uri)
                return uri
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/StellarPlayer")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException()
            try {
                writeUri(uri)
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) != 1) throw IOException()
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "StellarPlayer")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
        val file = File(directory, name)
        try {
            file.outputStream().use(write)
            checkActive()
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            return Uri.fromFile(file)
        } catch (e: Exception) { file.delete(); throw e }
    }

}
