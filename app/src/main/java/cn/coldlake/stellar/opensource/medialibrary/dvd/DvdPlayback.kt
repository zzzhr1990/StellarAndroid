package cn.coldlake.stellar.opensource.medialibrary.dvd

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.servers.RemoteEntry
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import com.stellar.player.disc.*
import kotlinx.coroutines.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

internal suspend fun openDisc(reader: DiscReader, files: Map<String, Long>, iso: Boolean): DiscSource = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { reader.close() }
    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
        try {
            val source = (if (iso || files.keys.any { it.startsWith("BDMV/") }) BluraySource.tryOpen(reader, files, iso) else null) ?: DvdSource.open(reader, files, iso)
            continuation.resume(source, onCancellation = { _, value, _ -> value.close() })
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    })
}

internal suspend fun openLocalDvd(context: Context, uri: android.net.Uri): DiscSource = openLocalDisc(context, uri)

internal suspend fun openWebDavDvd(context: Context, profile: ServerProfile, entry: RemoteEntry): DiscSource =
    openRemoteDisc(context, profile, entry)
