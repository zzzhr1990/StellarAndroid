package cn.coldlake.stellar.opensource.medialibrary.webdav

import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Session-scoped Range bridge. Both backends see a random loopback URL, never DAV credentials.
 * Each connection streams bounded chunks; no complete file or disk cache is created. */
internal class WebDavPlayback(private val client: WebDavClient, val title: String, val audio: Boolean) : Closeable {
    val id = "webdav-session:${UUID.randomUUID()}"
    private val token = UUID.randomUUID().toString()
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val address = "http://127.0.0.1:${listener.localPort}/$token"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    init {
        scope.launch {
            try {
                while (isActive) {
                    val socket = listener.accept()
                    if (!isActive || sockets.size >= 8) { socket.close(); continue }
                    sockets += socket
                    if (!isActive) { sockets -= socket; socket.close(); break }
                    launch { try { serve(socket) } catch (_: Exception) { /* No request/credential logging. */ }
                        finally { sockets -= socket; runCatching { socket.close() } } }
                }
            } catch (_: Exception) { /* Closing the session interrupts accept. */ }
        }
    }
    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 8192) {
                val byte = input.read()
                if (byte == -1) throw java.io.EOFException()
                if (byte == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
                bytes += byte.toByte()
            }
            throw java.io.IOException("Header limit")
        }
        val request = line().split(' ')
        val output = socket.getOutputStream().buffered(64 * 1024)
        if (request.size != 3 || request[0] !in setOf("GET", "HEAD") || request[1] != "/$token") {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return
        }
        var range: String? = null
        var ended = false
        for (i in 0 until 64) {
            val header = line()
            if (header.isEmpty()) { ended = true; break }
            if (header.substringBefore(':').equals("Range", true)) {
                val value = header.substringAfter(':').trim()
                if (!value.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))) throw java.io.IOException("Invalid range")
                range = value
            }
        }
        if (!ended) throw java.io.IOException("Header limit")
        var sent = false
        try {
            client.stream(range, request[0] == "HEAD") { response ->
                // Do not expose redirects, authentication challenges or upstream error bodies.
                val status = response.status.value.takeIf { it in setOf(200, 206, 416) } ?: 502
                val header = buildString {
                    append("HTTP/1.1 $status Result\r\nConnection: close\r\n")
                    if (status == 502) append("Content-Length: 0\r\n")
                    else for (key in listOf("Content-Length", "Content-Range", "Content-Type", "Accept-Ranges")) {
                        response.headers[key]?.takeIf { '\r' !in it && '\n' !in it }?.let { append("$key: $it\r\n") }
                    }
                    append("\r\n")
                }
                sent = true; output.write(header.toByteArray(Charsets.US_ASCII)); output.flush()
                if (request[0] != "HEAD" && status in setOf(200, 206)) {
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    while (currentCoroutineContext().isActive) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (count > 0) { output.write(buffer, 0, count); output.flush() }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (!sent) { output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush() }
        }
    }
    override fun close() {
        scope.cancel()
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        client.close()
    }
}
