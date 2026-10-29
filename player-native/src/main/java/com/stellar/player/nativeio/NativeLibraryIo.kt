package com.stellar.player.nativeio

import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Raw directory pages and bounded sidecar reads, with exclusive reuse of healthy NAS sessions. */
object NativeLibraryIo {
    class ReadLimitExceeded : IOException("NAS sidecar exceeds limit")
    class Entry(bytes: ByteArray, val directory: Boolean, val regular: Boolean, val size: Long, val modified: Long) {
        val name = bytes.toString(Charsets.UTF_8)
    }
    data class Connection(val host: String, val share: String, val user: String = "", val password: String = "",
        val domain: String = "", val nfsVersion: Int = 0, val port: Int = 2049, val mountPort: Int = 0, val uid: Int = -1, val gid: Int = -1) {
        override fun toString() = "LibraryConnection(nfsVersion=$nfsVersion)"
    }
    init { System.loadLibrary("stellar_native") }
    private external fun create(): Long
    private external fun cancel(handle: Long)
    private external fun destroy(handle: Long)
    private external fun connect(handle: Long, host: String, share: String, user: String,
        password: String, domain: String, version: Int, port: Int, mountPort: Int, uid: Int, gid: Int): Int
    private external fun open(handle: Long, path: String, file: Boolean): Int
    private external fun finish(handle: Long): Int
    private external fun page(handle: Long, entries: MutableList<Entry>): Int
    private external fun read(handle: Long, limit: Int): ByteArray?

    // The existing scanner/image budgets limit active work. Keep at most four idle
    // transports globally, keyed by every connection/authentication setting, for 30s.
    private class Idle(val connection: Connection, val handle: Long) {
        val since = System.nanoTime()
        var expiry: Job? = null
    }
    private val idle = ArrayList<Idle>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun acquire(connection: Connection): Long? {
        val entry = synchronized(idle) {
            idle.lastOrNull { it.connection == connection }?.also { idle.remove(it); it.expiry?.cancel() }
        } ?: return null
        if (System.nanoTime() - entry.since < 30_000_000_000L) return entry.handle
        destroy(entry.handle)
        return null
    }
    private fun recycle(connection: Connection, handle: Long) {
        val entry = Idle(connection, handle)
        val evicted = synchronized(idle) {
            val oldest = if (idle.size >= 4) idle.removeAt(0).also { it.expiry?.cancel() } else null
            idle.add(entry)
            entry.expiry = cleanup.launch(start = CoroutineStart.LAZY) {
                delay(30_000)
                if (synchronized(idle) { idle.remove(entry) }) destroy(handle)
            }
            oldest
        }
        entry.expiry?.start()
        evicted?.let { destroy(it.handle) }
    }

    private class StaleSession : IOException()
    private suspend fun <T> session(connection: Connection, path: String, file: Boolean, action: suspend (Long) -> T): T =
        try { useSession(connection, path, file, true, action) }
        catch (_: StaleSession) {
            // An idle peer may have disconnected. Retry once before any data is delivered.
            useSession(connection, path, file, false, action)
        }

    private suspend fun <T> useSession(connection: Connection, path: String, file: Boolean, reuse: Boolean,
        action: suspend (Long) -> T): T =
        withContext(Dispatchers.IO) {
            val reused = if (reuse) acquire(connection) else null
            val handle = reused ?: create()
            if (handle == 0L) throw IOException("NAS session unavailable")
            val finished = AtomicBoolean(false)
            var healthy = false
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!finished.get()) cancel(handle) }
            }
            try {
                val c = connection
                val result = if (reused != null) 0 else connect(handle, c.host, c.share, c.user, c.password, c.domain,
                    c.nfsVersion, c.port, c.mountPort, c.uid, c.gid)
                ensureActive()
                if (result != 0) throw IOException("NAS connection failed ($result)")
                val opened = open(handle, path, file)
                ensureActive()
                if (opened != 0) {
                    if (reused != null) throw StaleSession()
                    throw IOException("NAS open failed ($opened)")
                }
                val value = action(handle)
                val closed = finish(handle)
                ensureActive()
                if (closed != 0) throw IOException("NAS close failed ($closed)")
                healthy = true
                value
            } finally {
                finished.set(true)
                val reusable = healthy && isActive
                // A cancellation callback must finish before another caller owns this handle.
                withContext(NonCancellable) { watcher.cancelAndJoin() }
                if (reusable) recycle(connection, handle) else destroy(handle)
            }
        }

    suspend fun enumerate(connection: Connection, path: String, consume: suspend (List<Entry>) -> Unit) =
        session(connection, path, false) { handle ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entries = ArrayList<Entry>()
                val result = page(handle, entries)
                currentCoroutineContext().ensureActive()
                if (result < 0) throw IOException("NAS directory incomplete ($result)")
                if (entries.isNotEmpty()) consume(entries)
                if (result == 1) break
            }
        }

    suspend fun readSmall(connection: Connection, path: String, limit: Int): ByteArray {
        require(limit in 1..32 * 1024 * 1024)
        return session(connection, path, true) { handle ->
            val bytes = read(handle, limit)
            currentCoroutineContext().ensureActive()
            if (bytes == null) throw IOException("NAS sidecar unavailable")
            if (bytes.size > limit) throw ReadLimitExceeded()
            bytes
        }
    }
}
