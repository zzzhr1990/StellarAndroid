package cn.coldlake.stellar.opensource.casting

import com.stellar.player.contract.PlaybackStatus
import kotlinx.coroutines.flow.StateFlow

internal enum class CastProtocol { DLNA }
internal data class CastDevice(val id: String, val name: String, val protocol: CastProtocol)
internal enum class CastFailure { PERMISSION, NO_NETWORK, VPN_BLOCKED, UNAVAILABLE, SOURCE, LOAD, CONTROL, DISCONNECTED }
internal data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    val device: CastDevice? = null,
    val connecting: Boolean = false,
    val failure: CastFailure? = null,
    val volume: Float? = null,
    val subtitleBusy: Boolean = false,
    val subtitleFailed: Boolean = false,
    val canPause: Boolean = true,
    val relaying: Boolean = false,
)

/** URLs and credentials never enter Compose/saved state. */
internal class CastMedia(val url: String, val title: String, val mime: String, val durationMs: Long?, val subtitleUrl: String? = null) {
    fun withSubtitle(url: String?) = CastMedia(this.url, title, mime, durationMs, url)
    override fun toString() = "CastMedia(redacted)"
}
internal data class RemoteStatus(
    val status: PlaybackStatus = PlaybackStatus.PAUSED,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val canPause: Boolean = true,
    val volume: Float? = null,
    val failure: CastFailure? = null,
)
internal interface CastAdapter : AutoCloseable {
    val status: StateFlow<RemoteStatus>
    suspend fun load(device: CastDevice, media: CastMedia, positionMs: Long, play: Boolean)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun volume(value: Float)
    suspend fun stop()
}
