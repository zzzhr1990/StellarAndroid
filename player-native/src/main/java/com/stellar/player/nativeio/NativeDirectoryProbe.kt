package com.stellar.player.nativeio

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Directory access probes, independent of MPV playback sessions and surfaces. */
object NativeDirectoryProbe {
    enum class Result { OK, AUTHENTICATION, PATH, PERMISSION, TIMEOUT, CANCELLED, NETWORK, LIMIT }
    class Entry(bytes: ByteArray, val directory: Boolean, val regular: Boolean, val size: Long) {
        val name = bytes.toString(Charsets.UTF_8)
    }
    data class Listing(val result: Result, val entries: List<Entry>)
    init { System.loadLibrary("stellar_native") }
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "directory-probe").apply { isDaemon = true } }
    private external fun create(): Long
    private external fun cancel(handle: Long)
    private external fun destroy(handle: Long)
    private external fun smb(handle: Long, host: String, share: String, path: String,
                             user: String, password: String, domain: String, entries: MutableList<Entry>?): Int
    private external fun nfs(handle: Long, host: String, export: String, path: String, version: Int, port: Int, uid: Int, gid: Int, entries: MutableList<Entry>?): Int

    private suspend fun run(block: (Long) -> Int): Result = suspendCancellableCoroutine { continuation ->
        val handle = create()
        continuation.invokeOnCancellation { cancel(handle) }
        workers.execute {
            try {
                if (continuation.isActive) {
                    val code = block(handle)
                    continuation.resume(Result.entries.getOrElse(code) { Result.NETWORK })
                }
            // JNI allocation failures arrive as OutOfMemoryError, not Exception.
            // Deliver them to the awaiting coroutine and always release the ticket.
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error)
            } finally { destroy(handle) }
        }
    }
    suspend fun checkSmb(host: String, share: String, path: String, user: String, password: String, domain: String) =
        run { smb(it, host, share, path, user, password, domain, null) }
    suspend fun checkNfs(host: String, export: String, version: Int, port: Int, uid: Int?, gid: Int?) =
        run { nfs(it, host, export, "/", version, port, uid ?: -1, gid ?: -1, null) }
    suspend fun listSmb(host: String, share: String, path: String, user: String, password: String, domain: String): Listing {
        val entries = mutableListOf<Entry>()
        val result = run { smb(it, host, share, path, user, password, domain, entries) }
        return Listing(result, if (result == Result.OK) entries else emptyList())
    }
    suspend fun listNfs(host: String, export: String, path: String, version: Int, port: Int, uid: Int?, gid: Int?): Listing {
        val entries = mutableListOf<Entry>()
        val result = run { nfs(it, host, export, path, version, port, uid ?: -1, gid ?: -1, entries) }
        return Listing(result, if (result == Result.OK) entries else emptyList())
    }
}
