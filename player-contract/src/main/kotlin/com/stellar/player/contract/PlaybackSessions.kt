package com.stellar.player.contract

import java.util.concurrent.atomic.AtomicLong

/** Process-wide identities prevent late commands from matching another real backend. */
object PlaybackSessions {
    private val sequence = AtomicLong()
    fun nextReal(): Long = sequence.decrementAndGet()
}

enum class PlaybackBackend { MPV }
