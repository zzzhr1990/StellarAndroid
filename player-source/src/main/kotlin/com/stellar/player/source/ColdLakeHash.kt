package com.stellar.player.source

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Match all three fields. A sampled digest alone is neither a file ID nor an integrity check. */
data class ColdLakeHashKey(
    val algorithmVersion: String,
    val fileSize: Long,
    val quickHash: String,
)

/**
 * Byte-compatible with CalcColdLakeHash for stable, fully readable files, including empty files.
 * At most 3 MiB is fed to SHA-1: all bytes for sizes <= 3 MiB, otherwise 1 MiB each at
 * 0, size / 2 - 512 KiB, and size - 1 MiB, in that order. Output is lowercase hexadecimal.
 * Size/version are separate key fields; they are NOT added to the digest input.
 *
 * Blocking IO: call on a worker thread. `checkCancelled` may throw to abort between reads;
 * cancelling an in-flight network read remains the source owner's responsibility.
 * Readers must expose one stable file version and actual random access, never emulate seek
 * by downloading/discarding preceding bytes. This helper cannot detect same-size rewrites.
 */
object ColdLakeHash {
    const val ALGORITHM_VERSION = "coldlake-3x1m-sha1-v1"
    const val SAMPLE_SIZE = 1024 * 1024

    /** Opens one read-only handle and always closes it, including on failure/cancellation. */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun calculate(file: File, checkCancelled: () -> Unit = {}): ColdLakeHashKey =
        RandomAccessFile(file, "r").use { calculate(it.channel, checkCancelled) }

    /**
     * Borrows a seekable channel representing the whole file (e.g. a seekable Android PFD).
     * Does not close it or change its position. Rejects a size change during calculation.
     * Asset subranges require an adapter with the correct logical size and offsets.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun calculate(channel: FileChannel, checkCancelled: () -> Unit = {}): ColdLakeHashKey {
        checkActive(checkCancelled)
        channel.position() // Reject non-seekable descriptors, even if size() reports zero.
        val size = channel.size()
        val key = hash(size, checkCancelled) { offset, buffer, count ->
            channel.read(ByteBuffer.wrap(buffer, 0, count), offset)
        }
        if (channel.size() != size) throw IOException("File size changed while hashing")
        return key
    }

    /**
     * Takes ownership of a fresh reader: opens and always closes it, even if open fails.
     * Never pass a playback-owned reader. open() must return the exact non-negative size;
     * seek/read must fail if the source version changes. Partial positive reads are supported.
     * StreamSource.createReader() and NativeRemoteFile can supply independent readers.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun calculate(source: RemoteFile, checkCancelled: () -> Unit = {}): ColdLakeHashKey = source.use {
        checkActive(checkCancelled)
        val size = it.open()
        var position = -1L
        hash(size, checkCancelled) { offset, buffer, count ->
            if (position != offset) it.seek(offset)
            it.read(buffer, 0, count).also { read ->
                if (read > 0 && read <= count) position = offset + read
            }
        }
    }

    private fun hash(
        size: Long,
        checkCancelled: () -> Unit,
        readAt: (Long, ByteArray, Int) -> Int,
    ): ColdLakeHashKey {
        if (size < 0) throw IOException("Unknown file size")
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(minOf(size, SAMPLE_SIZE.toLong()).toInt())
        fun readRange(start: Long, length: Int) {
            var offset = start
            var remaining = length
            while (remaining > 0) {
                checkActive(checkCancelled)
                val requested = minOf(remaining, buffer.size)
                val read = readAt(offset, buffer, requested)
                if (read < 0) throw EOFException("Unexpected EOF while hashing at offset $offset")
                if (read == 0) throw IOException("Hash read made no progress at offset $offset")
                if (read > requested) throw IOException("Hash read exceeded requested length")
                digest.update(buffer, 0, read)
                offset += read
                remaining -= read
            }
        }
        if (size <= 3L * SAMPLE_SIZE) {
            readRange(0, size.toInt())
        } else {
            readRange(0, SAMPLE_SIZE)
            readRange(size / 2 - SAMPLE_SIZE / 2, SAMPLE_SIZE)
            readRange(size - SAMPLE_SIZE, SAMPLE_SIZE)
        }
        checkActive(checkCancelled)
        val hex = "0123456789abcdef"
        val value = buildString(40) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
        return ColdLakeHashKey(ALGORITHM_VERSION, size, value)
    }

    private fun checkActive(checkCancelled: () -> Unit) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Hash calculation interrupted")
        checkCancelled()
    }
}
