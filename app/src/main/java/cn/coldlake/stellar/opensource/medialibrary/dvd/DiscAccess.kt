package cn.coldlake.stellar.opensource.medialibrary.dvd

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.medialibrary.cloud.*
import com.stellar.player.disc.*
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.util.Locale

internal fun isDiscName(name: String) = name.endsWith(".iso", true) ||
    name.equals("VIDEO_TS.IFO", true) || name.equals("index.bdmv", true) ||
    name.equals("VIDEO_TS", true) || name.equals("BDMV", true)

internal interface DiscAccess : Closeable {
    suspend fun list(location: String): List<RemoteEntry>
    suspend fun size(entry: RemoteEntry): Long = entry.size?.takeIf { it >= 0 } ?: throw IOException("Disc size unavailable")
    suspend fun parent(entry: RemoteEntry): String = entry.location.trimEnd('/').substringBeforeLast('/', "") + "/"
    fun read(location: String, offset: Long, length: Int): ByteArray?
    fun cancelRead()
}

/** Bounded block cache, shared by protocols. At most one IO owner and four file handles. */
internal class AccessDiscReader(private val access: DiscAccess, private val files: Map<String, Pair<String, Long>>,
    private val blockBytes: Int) : DiscReader() {
    @Volatile private var closed = false
    @Volatile var bytesRead = 0L; private set
    @Volatile var requests = 0L; private set
    var cacheBudgetBytes = 4 * 1024 * 1024
    private val cache = object : LinkedHashMap<Pair<String, Long>, ByteArray>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, ByteArray>?) = size > cacheBudgetBytes / blockBytes
    }
    @Synchronized override fun readAt(name: String, offset: Long, length: Int): ByteArray? {
        if (closed || offset < 0 || length !in 0..2 * 1024 * 1024) return null
        val file = files[name] ?: return null
        val out = ByteArray(minOf(length.toLong(), (file.second - offset).coerceAtLeast(0)).toInt())
        return try {
            var done = 0
            while (done < out.size) {
                if (closed) return null
                val position = offset + done
                val start = position / blockBytes * blockBytes
                val key = name to start
                val size = minOf(blockBytes.toLong(), file.second - start).toInt()
                val bytes = cache[key] ?: (access.read(file.first, start, size)?.takeIf { it.size == size }
                    ?: return null).also { cache[key] = it; requests++; bytesRead += it.size }
                val from = (position - start).toInt()
                val count = minOf(out.size - done, bytes.size - from)
                bytes.copyInto(out, done, from, from + count); done += count
            }
            out
        } catch (_: Exception) { null }
    }
    override fun cancelRead() = access.cancelRead()
    override fun close() {
        closed = true; access.cancelRead()
        synchronized(this) { access.close(); cache.clear() }
    }
}

/** Only the disc's required directories are enumerated; never recursively scan a library. */
internal suspend fun openDiscAccess(access: DiscAccess, entry: RemoteEntry): DiscSource {
    var owned = true
    try {
        val iso = entry.name.endsWith(".iso", true)
        val files = withContext(Dispatchers.IO) {
            val result = linkedMapOf<String, Pair<String, Long>>()
            suspend fun add(key: String, file: RemoteEntry) {
                if (result.size >= 20_000) throw IOException("Disc directory too large")
                result[key] = file.location to access.size(file)
            }
            if (iso) add("", entry) else {
                var folder = if (entry.kind == RemoteFileKind.FOLDER) entry.location else access.parent(entry)
                var entries = access.list(folder)
                // A selected disc root may contain VIDEO_TS or BDMV.
                val disc = entries.firstOrNull { it.kind == RemoteFileKind.FOLDER && it.name.equals("BDMV", true) }
                    ?: entries.firstOrNull { it.kind == RemoteFileKind.FOLDER && it.name.equals("VIDEO_TS", true) }
                if (disc != null) { folder = disc.location; entries = access.list(folder) }
                val bluray = entries.any { it.name.equals("index.bdmv", true) } || entry.name.equals("BDMV", true)
                if (bluray) {
                    entries.filter { it.kind != RemoteFileKind.FOLDER }.forEach { add("BDMV/" + when { it.name.equals("MovieObject.bdmv", true) -> "MovieObject.bdmv"; else -> it.name.lowercase(Locale.ROOT) }, it) }
                    for (child in entries.filter { it.kind == RemoteFileKind.FOLDER && it.name.uppercase(Locale.ROOT) in setOf("PLAYLIST", "CLIPINF", "STREAM") }) {
                        access.list(child.location).filter { it.kind != RemoteFileKind.FOLDER }.forEach {
                            add("BDMV/${child.name.uppercase(Locale.ROOT)}/${it.name.lowercase(Locale.ROOT)}", it)
                        }
                    }
                } else entries.filter { it.kind != RemoteFileKind.FOLDER && it.name.matches(Regex("(?i)(VIDEO_TS|VTS_[0-9]{2}_[0-9])\\.(IFO|BUP|VOB)")) }
                    .forEach { add(it.name.uppercase(Locale.ROOT), it) }
            }
            if (result.isEmpty()) throw IOException("Disc files unavailable")
            result
        }
        currentCoroutineContext().ensureActive()
        val reader = AccessDiscReader(access, files, if (iso) 1024 * 1024 else 512 * 1024)
        owned = false // openDisc owns reader, including cancellation/failure.
        return openDisc(reader, files.mapValues { it.value.second }, iso).also {
            // Long GOPs make lavf repeatedly search the same 10+ MiB during a Blu-ray seek.
            // Keep those blocks without changing the player's demux/prefetch budget.
            if (it.bluray) reader.cacheBudgetBytes = 16 * 1024 * 1024
        }
    } finally { if (owned) access.close() }
}

internal suspend fun openRemoteDisc(context: Context, profile: ServerProfile, entry: RemoteEntry): DiscSource {
    val access: DiscAccess = when {
        profile.kind == ServerKind.SMB || profile.kind == ServerKind.NFS -> NasDiscAccess(context, profile)
        profile.kind.cloud -> CloudDiscAccess(cloudProvider(context, profile))
        else -> HttpDiscAccess(context, profile)
    }
    return openDiscAccess(access, entry)
}

private class NasDiscAccess(context: Context, private val profile: ServerProfile) : DiscAccess {
    private val directory = ProtocolDirectory(context)
    private val files = linkedMapOf<String, NasDiscFile>()
    @Volatile private var active: NasDiscFile? = null
    override suspend fun list(location: String) = directory.list(profile, location.trim('/'))
    override fun read(location: String, offset: Long, length: Int): ByteArray? {
        files[location]?.takeIf { it.cancelled }?.let { files.remove(location); it.close() }
        val file = files[location] ?: run {
            if (files.size >= 4) files.remove(files.keys.first())?.close()
            val entry = RemoteEntry("", location, RemoteFileKind.VIDEO, null)
            NasDiscFile(smb = if (profile.kind == ServerKind.SMB) checkNotNull(profile.smbMedia(entry)) else null,
                nfs = if (profile.kind == ServerKind.NFS) checkNotNull(profile.nfsMedia(entry)) else null).also { files[location] = it }
        }
        active = file
        return try { file.readAt(offset, length) } finally { active = null }
    }
    override fun cancelRead() { active?.cancel() }
    override fun close() { files.values.forEach { it.close() }; files.clear() }
}

private class CloudDiscAccess(private val provider: CloudProvider) : DiscAccess {
    @Volatile private var pending: Job? = null
    override suspend fun list(location: String) = browseCloud(provider, location)
    override suspend fun parent(entry: RemoteEntry) = provider.metadata(entry.location).parent ?: throw IOException("Disc parent unavailable")
    override suspend fun size(entry: RemoteEntry) = entry.size ?: provider.metadata(entry.location).size ?: throw IOException("Disc size unavailable")
    override fun read(location: String, offset: Long, length: Int): ByteArray? = runBlocking(Dispatchers.IO) {
        pending = currentCoroutineContext().job
        try {
            provider.media(location, "bytes=$offset-${offset + length - 1}", false).use { response ->
                val range = response.header("Content-Range").orEmpty()
                if (response.code != 206 || !range.startsWith("bytes $offset-${offset + length - 1}/")) throw IOException("Disc range mismatch")
                val source = response.body.source()
                source.readByteArray(length.toLong())
            }
        } finally { pending = null }
    }
    override fun cancelRead() { pending?.cancel() }
    override fun close() { provider.close() }
}
