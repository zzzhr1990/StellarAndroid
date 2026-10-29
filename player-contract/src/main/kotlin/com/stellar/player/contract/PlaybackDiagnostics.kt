package com.stellar.player.contract

/** On-demand, session-scoped measurements. Null means unavailable, never zero.
 * Kept outside PlaybackSnapshot: diagnostics must not drive playback chrome. */
data class PlaybackDiagnostics(
    val sessionId: Long,
    val backend: String? = null,
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sourceFps: Double? = null,
    val presentedFps: Double? = null,
    val bitrate: Long? = null,
    val color: String? = null,
    val fileBytes: Long? = null,
    val hardware: Boolean? = null,
    val bufferedMs: Long? = null,
    /** Cumulative media payload read from the network, excluding disk hits. */
    val networkBytes: Long? = null,
    val networkBytesPerSecond: Double? = null,
    val cpuPercent: Double? = null,
    val gpuPercent: Double? = null,
)
