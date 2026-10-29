package com.stellar.player.source

import java.io.IOException

/** One IO owner opens, seeks, reads and closes; cancel alone may run concurrently. */
interface RemoteFile : AutoCloseable {
    fun open(): Long
    fun seek(offset: Long)
    fun read(buffer: ByteArray, offset: Int, count: Int): Int
    fun cancel()
}

/** JNI access to the same RemoteReader used by MPV and MPV, with independent file handles. */
class NativeRemoteFile private constructor(private val handle: Long) : RemoteFile {
    override fun open(): Long = NativeRemoteFiles.open(handle).also { if (it < 0) throw IOException("Remote open failed") }
    override fun seek(offset: Long) {
        if (!NativeRemoteFiles.seek(handle, offset)) throw IOException("Remote seek failed")
    }
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset <= buffer.size - count)
        if (count == 0) return 0
        val result = NativeRemoteFiles.read(handle, buffer, offset, count)
        if (result < 0) throw IOException("Remote read failed")
        return if (result == 0) -1 else result
    }
    override fun cancel() = NativeRemoteFiles.cancel(handle)
    override fun close() = NativeRemoteFiles.close(handle)

    companion object {
        fun smb(source: SmbSource): RemoteFile = NativeRemoteFile(NativeRemoteFiles.smb(
            source.server.toByteArray(), source.share.toByteArray(), source.path.toByteArray(),
            source.user.toByteArray(), source.password.toByteArray(), source.domain.toByteArray(), source.requireEncryption))
        fun nfs(source: NfsSource): RemoteFile = NativeRemoteFile(NativeRemoteFiles.nfs(
            source.server.toByteArray(), source.export.toByteArray(), source.path.toByteArray(),
            source.version.number, source.uid ?: -1, source.gid ?: -1, source.port))
    }
}

private object NativeRemoteFiles {
    init { System.loadLibrary("stellar_source") }
    external fun smb(server: ByteArray, share: ByteArray, path: ByteArray, user: ByteArray,
        password: ByteArray, domain: ByteArray, seal: Boolean): Long
    external fun nfs(server: ByteArray, export: ByteArray, path: ByteArray, version: Int, uid: Int, gid: Int, port: Int): Long
    external fun open(handle: Long): Long
    external fun seek(handle: Long, offset: Long): Boolean
    external fun read(handle: Long, buffer: ByteArray, offset: Int, count: Int): Int
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
