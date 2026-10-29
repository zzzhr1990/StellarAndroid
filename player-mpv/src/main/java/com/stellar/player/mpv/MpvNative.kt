package com.stellar.player.mpv

import android.view.Surface

internal object MpvNative {
    init { System.loadLibrary("stellar_mpv") }
    external fun discPalette(address: String): String
    external fun create(surface: Surface?, options: Array<String>): Long
    external fun configureHttpCache(handle: Long, token: String, address: String, directory: String,
        headers: String, userAgent: String, verifyTls: Boolean, caFile: String): Boolean
    external fun configureSmb(handle: Long, token: String, server: ByteArray, share: ByteArray,
        path: ByteArray, user: ByteArray, password: ByteArray, domain: ByteArray, seal: Boolean)
    external fun configureNfs(handle: Long, token: String, server: ByteArray, root: ByteArray,
        path: ByteArray, version: Int, uid: Int, gid: Int, port: Int)
    external fun cancelRemote(token: String)
    external fun configureStream(handle: Long, token: String, source: com.stellar.player.source.StreamSource, cacheDirectory: String): Boolean
    external fun remoteError(handle: Long): Int
    external fun remoteDiagnostics(handle: Long): String
    external fun destroy(handle: Long)
    external fun surface(handle: Long, surface: Surface?): Boolean
    external fun command(handle: Long, args: Array<String>): Int
    external fun addSubtitle(handle: Long, request: Long, path: String, label: String): Int
    external fun abortCommand(handle: Long, request: Long)
    external fun set(handle: Long, name: String, value: String): Int
    external fun headers(handle: Long, values: Array<String>): Int
    external fun read(handle: Long, name: String): String?
    external fun poll(handle: Long): String
    external fun equalizer(handle: Long, enabled: Boolean, preamp: Float, bands: FloatArray, rate: Int): String?
}
