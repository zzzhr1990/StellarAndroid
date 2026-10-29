package cn.coldlake.stellar.opensource.casting

import com.stellar.player.source.RemoteFile
import java.io.IOException
import java.io.InputStream

/** Each HTTP request owns its reader and cursor; cancel never waits for network IO. */
internal class NasCastSource(private val mime: String, private val create: () -> RemoteFile) : CastByteSource {
    private val readers = mutableSetOf<RemoteFile>()
    private var closed = false

    override fun open(head: Boolean, range: String?): CastResponse {
        val reader = synchronized(readers) {
            if (closed) throw IOException("Source closed")
            create().also { readers += it }
        }
        fun release() {
            // Keep cancel safe until native close has removed the handle.
            try { reader.close() } finally { synchronized(readers) { readers -= reader } }
        }
        try {
            val length = reader.open()
            val requested = range?.let { ByteRange.parse(it, length) }
            if (range != null && requested == null) {
                release()
                return CastResponse(416, mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
            }
            val headers = mutableMapOf("Content-Type" to mime, "Content-Length" to (requested?.length ?: length).toString(), "Accept-Ranges" to "bytes")
            if (requested != null) headers["Content-Range"] = "bytes ${requested.start}-${requested.end}/$length"
            val code = if (requested == null) 200 else 206
            if (head) { release(); return CastResponse(code, headers) }
            reader.seek(requested?.start ?: 0)
            val input = object : InputStream() {
                private var remaining = requested?.length ?: length
                override fun read(): Int {
                    val byte = ByteArray(1)
                    return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    require(offset >= 0 && count >= 0 && offset <= buffer.size - count)
                    if (count == 0) return 0
                    if (remaining == 0L) return -1
                    val result = reader.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
                    if (result <= 0) throw IOException("Remote file truncated")
                    remaining -= result
                    return result
                }
            }
            return CastResponse(code, headers, input, ::release)
        } catch (e: Exception) { release(); throw e }
    }

    override fun close() = synchronized(readers) {
        closed = true
        readers.forEach { it.cancel() }
    }
}
