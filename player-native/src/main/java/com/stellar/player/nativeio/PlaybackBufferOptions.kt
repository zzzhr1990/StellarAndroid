package com.stellar.player.nativeio

/** Undecoded packet queue, independent of the disk cache. Captured for each playback session. */
data class PlaybackBufferOptions(val minMs: Long = 3_000, val maxMs: Long = 15_000, val drop: Boolean = false) {
    init { require(minMs >= 0 && maxMs > 0 && minMs <= maxMs) }
}
