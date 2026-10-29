package com.stellar.player.nativeio

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** App-owned playback cache. Filesystem calls run on the playback worker or an IO dispatcher. */
class PlaybackDiskCache(val directory: File, private val options: () -> Options) {
    data class Options(val enabled: Boolean = false, val retentionDays: Int = 7)
    data class Configuration(val path: Path, val expireSeconds: Int)

    @Synchronized fun prepare(source: String, http: com.stellar.player.source.HttpSource? = null): Configuration? {
        val setting = options()
        if (!setting.enabled || URI(source).scheme?.lowercase() !in setOf("http", "https", "stellarcloud")) return null
        val root = root()
        Files.createDirectories(root)
        val expireSeconds = setting.retentionDays.coerceIn(1, 30) * 86_400
        val cutoff = System.currentTimeMillis() - expireSeconds * 1000L
        for (entry in children(root)) {
            if (Files.getLastModifiedTime(entry, NOFOLLOW_LINKS).toMillis() < cutoff) delete(entry)
        }
        // MPV ignores URL query/fragment and HTTP headers in its own identity. Partition by the
        // complete request identity to prevent different media variants or credentials sharing bytes.
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        add(source)
        http?.headers?.entries?.sortedBy { it.key.lowercase() }?.forEach { add(it.key.lowercase()); add(it.value) }
        add(http?.userAgent.orEmpty())
        add((http?.verifyTlsCertificates ?: true).toString())
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        val path = root.resolve(key)
        check(!Files.isSymbolicLink(path))
        Files.createDirectories(path)
        path.toFile().setLastModified(System.currentTimeMillis())
        return Configuration(path, expireSeconds)
    }

    @Synchronized fun usageBytes(): Long {
        val root = root()
        var bytes = 0L
        if (Files.exists(root, NOFOLLOW_LINKS)) Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Disk caches may be sparse: report allocated storage, not the full media length.
                if (attrs.isRegularFile) try { bytes += Os.lstat(file.toString()).st_blocks * 512L }
                catch (error: ErrnoException) { if (error.errno != OsConstants.ENOENT) throw error }
                return FileVisitResult.CONTINUE
            }
        })
        return bytes
    }

    @Synchronized fun clear() {
        val root = root()
        for (entry in children(root)) delete(entry)
    }

    private fun root(): Path = directory.toPath().toAbsolutePath().normalize().also {
        check(!Files.isSymbolicLink(it)) { "Invalid cache root" }
    }
    private fun children(root: Path): List<Path> = if (!Files.exists(root, NOFOLLOW_LINKS)) emptyList()
        else Files.newDirectoryStream(root).use { it.toList() }
    private fun delete(entry: Path) {
        // Only direct children of this dedicated root may be removed; walkFileTree does not follow links.
        check(entry.parent == root())
        Files.walkFileTree(entry, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
