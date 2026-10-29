package cn.coldlake.stellar.opensource.medialibrary.cloud

import com.stellar.player.source.StreamReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** Native IO thread owns the cursor/response. cancel only cancels the call and auth job. */
internal class CloudStreamReader(private val client: CloudMediaClient, private val fileId: String,
    private val onClose: () -> Unit = {}) : StreamReader() {
    private val lifetime = Job()
    private val call = AtomicReference<Call?>()
    private var response: Response? = null
    private var position = 0L
    private var length = -1L
    private var responseEnd = 0L // exclusive; some services cap a Range response

    override fun open(): Long {
        lifetime.ensureActive()
        if (length < 0) connect()
        return length
    }

    private fun connect() {
        lifetime.ensureActive()
        val next = runBlocking(lifetime) {
            client.media(fileId, "bytes=$position-", false) { active ->
                call.set(active)
                if (!lifetime.isActive) active.cancel()
            }
        }
        try {
            lifetime.ensureActive()
            val total: Long
            val end: Long
            if (next.code == 206) {
                val range = CONTENT_RANGE.matchEntire(next.header("Content-Range").orEmpty())
                    ?: throw IOException("Missing cloud byte range")
                val start = range.groupValues[1].toLongOrNull()
                end = range.groupValues[2].toLongOrNull() ?: throw IOException("Invalid cloud byte range")
                total = range.groupValues[3].toLongOrNull() ?: throw IOException("Unknown cloud size")
                if (start != position || end < position || end >= total)
                    throw IOException("Cloud byte range mismatch")
                val bodyLength = next.body.contentLength()
                if (bodyLength >= 0 && bodyLength != end - position + 1)
                    throw IOException("Cloud response length mismatch")
            } else if (next.code == 200 && position == 0L) {
                total = next.body.contentLength()
                if (total < 0) throw IOException("Unknown cloud size")
                end = total - 1
            } else throw IOException("Cloud media request failed (${next.code})")
            if (length >= 0 && total != length) throw IOException("Cloud file size changed")
            length = total
            responseEnd = end + 1
            response = next
        } catch (error: Exception) {
            call.getAndSet(null)?.cancel()
            next.close()
            throw error
        }
    }

    override fun read(buffer: ByteBuffer): Int {
        lifetime.ensureActive()
        if (!buffer.hasRemaining()) return 0
        if (length >= 0 && position == length) return -1
        if (response != null && position == responseEnd) disconnect()
        // Retry a broken response once at the first byte not delivered to the engine.
        // Provider media() retains its own bounded token/download-URL refresh.
        repeat(2) { attempt ->
            if (response == null) connect()
            val oldLimit = buffer.limit()
            try {
                if (position == length) return -1
                buffer.limit(buffer.position() + minOf(buffer.remaining().toLong(), responseEnd - position).toInt())
                val count = checkNotNull(response).body.source().read(buffer)
                if (count < 0) throw EOFException("Cloud response truncated")
                position += count
                return count
            } catch (error: IOException) {
                disconnect()
                lifetime.ensureActive()
                if (attempt == 1) throw error
            } finally { buffer.limit(oldLimit) }
        }
        error("Unreachable")
    }

    override fun seek(offset: Long) {
        lifetime.ensureActive()
        if (offset < 0 || offset > open()) throw IOException("Cloud seek out of range")
        if (offset == position) return
        disconnect()
        position = offset
    }

    private fun disconnect() {
        // Cancel before close: never drain a large unread response after seek.
        call.getAndSet(null)?.cancel()
        response?.close()
        response = null
    }

    override fun cancel() {
        lifetime.cancel()
        call.get()?.cancel()
    }

    override fun close() { cancel(); try { disconnect() } finally { onClose() } }

    private companion object {
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
