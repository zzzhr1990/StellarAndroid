package cn.coldlake.stellar.opensource.platform

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import com.stellar.player.contract.MediaDescriptor
import com.stellar.player.contract.MediaKind
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.servers.RemoteFileKind
import cn.coldlake.stellar.opensource.medialibrary.servers.remoteFileKind
import java.io.FileNotFoundException
import java.util.Locale

internal class UnsupportedExternalMedia : Exception()

/** Called on IO. Keep the granted URI intact; never convert a provider URI into a disk path. */
internal fun readExternalMedia(context: Context, intent: Intent): MediaDescriptor {
    val uri = intent.data ?: throw UnsupportedExternalMedia()
    if (intent.action != Intent.ACTION_VIEW || uri.scheme !in setOf("content", "file") ||
        (uri.scheme == "content" && uri.authority.isNullOrBlank()) ||
        (uri.scheme == "file" && uri.path.isNullOrBlank())) throw UnsupportedExternalMedia()
    val resolver = context.contentResolver
    val title = if (uri.scheme == "content") runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() else null
    val name = title?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.local_file)
    val types = listOfNotNull(runCatching { resolver.getType(uri) }.getOrNull(), intent.type)
        .map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }
    // File providers sometimes return a generic type even when the sender knows the media type.
    val mime = types.firstOrNull { it.startsWith("video/") || it.startsWith("audio/") ||
        it in setOf("application/ogg", "application/x-matroska") } ?: types.firstOrNull()
    val kind = when {
        mime?.startsWith("video/") == true || mime == "application/x-matroska" -> MediaKind.VIDEO
        mime?.startsWith("audio/") == true -> MediaKind.AUDIO
        mime in setOf(null, "", "*/*", "application/octet-stream", "application/ogg") -> when (remoteFileKind(name, false)) {
            RemoteFileKind.VIDEO -> MediaKind.VIDEO
            RemoteFileKind.AUDIO -> MediaKind.AUDIO
            else -> if (mime == "application/ogg") MediaKind.AUDIO else throw UnsupportedExternalMedia()
        }
        else -> throw UnsupportedExternalMedia()
    }
    // Fail before replacing the current session if the sender did not grant access or removed the file.
    resolver.openAssetFileDescriptor(uri, "r")?.use { } ?: throw FileNotFoundException()
    val persistableRead = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    if (uri.scheme == "content" && intent.flags and persistableRead == persistableRead) {
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    return MediaDescriptor("local:$uri", name, kind, null)
}
