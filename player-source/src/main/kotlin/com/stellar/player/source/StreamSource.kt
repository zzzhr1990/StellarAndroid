package com.stellar.player.source

import java.nio.ByteBuffer

/** Creates independent cursors without doing IO; each native open owns its reader. */
fun interface StreamSource {
    fun createReader(): StreamReader
}

/** Same ownership as RemoteFile. The buffer is borrowed only for this read call. */
abstract class StreamReader : RemoteFile {
    abstract fun read(buffer: ByteBuffer): Int
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
        read(ByteBuffer.wrap(buffer, offset, count))
}
