package com.stellar.player.disc

import com.stellar.player.source.SmbSource
import com.stellar.player.source.NfsSource
import java.io.Closeable
import java.util.UUID

/** One bounded random reader. Read/close share an owner; cancel may interrupt a read. */
class NasDiscFile(smb: SmbSource? = null, nfs: NfsSource? = null) : Closeable {
    private val lifecycle = Any()
    @Volatile var cancelled = false; private set
    private var handle: Long
    init {
        require((smb == null) != (nfs == null))
        val token = "${if (smb != null) "smb" else "nfs"}://${UUID.randomUUID()}"
        val strings = if (smb != null) listOf(token, smb.server, smb.share, smb.path, smb.user, smb.password, smb.domain, "")
            else checkNotNull(nfs).let { listOf(token, it.server, it.export, it.path, "", "", "", "") }
        val bytes = strings.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
        try {
            handle = NasDiscNative.create(bytes, if (smb != null) intArrayOf(0, 0, 0, 0, if (smb.requireEncryption) 1 else 0)
                else checkNotNull(nfs).let { intArrayOf(it.version.number, it.uid ?: -1, it.gid ?: -1, it.port, 0) })
            check(handle != 0L)
        } finally { bytes.forEach { it.fill(0) } }
    }
    @Synchronized fun readAt(offset: Long, size: Int): ByteArray? =
        if (handle == 0L || cancelled) null else NasDiscNative.read(handle, offset, size)
    fun cancel() = synchronized(lifecycle) { cancelled = true; if (handle != 0L) NasDiscNative.cancel(handle) }
    @Synchronized override fun close() = synchronized(lifecycle) {
        if (handle != 0L) { NasDiscNative.close(handle); handle = 0 }
    }
}
private object NasDiscNative {
    init { System.loadLibrary("stellar_disc") }
    external fun create(arguments: Array<ByteArray>, numbers: IntArray): Long
    external fun read(handle: Long, offset: Long, size: Int): ByteArray?
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
