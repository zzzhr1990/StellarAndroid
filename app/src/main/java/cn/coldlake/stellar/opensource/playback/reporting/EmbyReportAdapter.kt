package cn.coldlake.stellar.opensource.playback.reporting

import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.network.HttpRateLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Protocol DTOs deliberately have separate progress/stop whitelists. */
internal class EmbyReportAdapter(private val source: EmbyPlayback,
    private val resolveProfile: suspend () -> ServerProfile?) : MediaServerReportAdapter {
    private var client: EmbyClient? = null
    override val target = PlaybackIdentity.key("delivery", source.profile.kind.name, source.profile.embyServerId,
        source.profile.embyUserId, source.profile.id).value
    override val baseline get() = source.item.baseline(source.version.id)
    private val jellyfin get() = source.profile.kind == ServerKind.JELLYFIN
    override suspend fun send(fact: PlaybackFact): ReportResult {
        val current = resolveProfile()
        val original = source.profile
        if (current == null || current.kind != original.kind || current.embyServerId != original.embyServerId ||
            current.embyUserId != original.embyUserId || current.address != original.address || current.updatedAt != original.updatedAt) {
            source.onReportError(ServerError.AUTHENTICATION)
            return ReportResult(DeliveryState.Unconfirmed, "TargetChanged")
        }
        if (current.embyToken.isEmpty()) {
            source.onReportError(ServerError.AUTHENTICATION)
            return ReportResult(DeliveryState.AuthenticationRequired, "Authentication")
        }
        val api = client ?: EmbyClient(current, source.verifyTlsCertificates).also { client = it }
        return try {
            api.report(when (fact.kind) { ReportKind.START -> ""; ReportKind.PROGRESS -> "Progress"; ReportKind.FINISH -> "Stopped" }, body(fact))
            source.onReportError(null)
            val confirmed = if (fact.kind == ReportKind.FINISH) withTimeoutOrNull(1_500) {
                try { api.detail(source.item.id).baseline(source.version.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } else null
            ReportResult(baseline = confirmed, error = if (fact.kind == ReportKind.FINISH && confirmed == null) "ReadbackPending" else null)
        } catch (e: HttpRateLimited) {
            ReportResult(DeliveryState.Retryable, "RateLimited", (e.retryAt - System.currentTimeMillis()).coerceAtLeast(0))
        } catch (e: EmbyFailure) {
            source.onReportError(e.reason)
            ReportResult(when {
                e.reason in setOf(ServerError.AUTHENTICATION, ServerError.PERMISSION) -> DeliveryState.AuthenticationRequired
                e.httpStatus == 429 || e.httpStatus in 500..599 || e.reason in setOf(ServerError.NETWORK, ServerError.TIMEOUT) -> DeliveryState.Retryable
                else -> DeliveryState.Unconfirmed
            }, e.reason.name)
        }
    }
    fun body(fact: PlaybackFact): JSONObject = JSONObject()
        .put("ItemId", source.item.id).put("MediaSourceId", source.version.id).put("PlaySessionId", source.playSessionId)
        .put("PositionTicks", millisecondsToTicks(fact.positionMs))
        .apply {
            if (fact.kind == ReportKind.FINISH && jellyfin) {
                put("Failed", fact.finish == FinishReason.ERROR)
                return@apply
            }
            put("PlayMethod", source.version.playMethod)
            put("IsPaused", fact.paused); put("CanSeek", fact.canSeek)
            track(fact.audio, "Audio")?.let { put("AudioStreamIndex", it) }
            put("SubtitleStreamIndex", track(fact.subtitle, "Subtitle") ?: -1)
            if (!jellyfin) {
                fact.durationMs?.let { put("RunTimeTicks", millisecondsToTicks(it)) }
                put("PlaybackRate", fact.speed.toDouble())
                put("EventName", when {
                    ProgressReason.PAUSE in fact.reasons || ProgressReason.UNPAUSE in fact.reasons -> if (fact.paused) "Pause" else "Unpause"
                    ProgressReason.AUDIO in fact.reasons -> "AudioTrackChange"
                    ProgressReason.SUBTITLE in fact.reasons -> "SubtitleTrackChange"
                    ProgressReason.SPEED in fact.reasons -> "PlaybackRateChange"
                    else -> "TimeUpdate"
                })
            }
        }
    private fun track(id: String?, type: String): Int? {
        if (id == null || !id.startsWith("${type.uppercase()}:")) return null
        val index = id.substringAfterLast(':').toIntOrNull() ?: return null
        return source.item.sources.firstOrNull { it.id == source.version.id }?.tracks
            ?.firstOrNull { it.type == type && it.index == index && !it.external }?.index
    }
    override fun close() { client?.close(); client = null }
}
internal fun EmbyItem.baseline(version: String?) = ServerBaseline(resumeMs, played, lastPlayedDate, version)
