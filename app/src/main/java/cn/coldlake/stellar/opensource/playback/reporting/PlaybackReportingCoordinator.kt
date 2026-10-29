package cn.coldlake.stellar.opensource.playback.reporting

import com.stellar.player.contract.PlaybackSnapshot
import com.stellar.player.contract.PlaybackStatus
import java.util.UUID

internal enum class ReportKind { START, PROGRESS, FINISH }
internal enum class ProgressReason { TIME, PAUSE, UNPAUSE, SEEK, AUDIO, SUBTITLE, SPEED, OWNER }
internal enum class FinishReason { CLOSED, REPLACED, ENDED, ERROR }
internal enum class DeliveryState { Pending, Acknowledged, Retryable, AuthenticationRequired, Conflict, Unconfirmed }

/** Immutable confirmed facts. No URL, credential, Android object, or wall-clock extrapolation. */
internal data class PlaybackFact(
    val session: String, val sequence: Long, val kind: ReportKind,
    val positionMs: Long, val durationMs: Long?, val paused: Boolean, val canSeek: Boolean,
    val speed: Float, val audio: String?, val subtitle: String?,
    val reasons: Set<ProgressReason> = emptySet(), val finish: FinishReason? = null,
    val lastWatchedAt: Long, val persist: Boolean = true, val report: Boolean = true,
    val opensSession: Boolean = kind == ReportKind.START,
)

/** One owner calls this on the host thread; IO is performed by the runtime after normalization. */
internal class PlaybackReportingCoordinator(
    private val emit: (PlaybackFact) -> Unit,
    private val monotonic: () -> Long,
    private val wall: () -> Long,
    private val newSession: () -> String = { UUID.randomUUID().toString() },
) {
    private var media: String? = null
    private var nativeSession = 0L
    private var generation = 0L
    private var session = ""
    private var sequence = 0L
    private var ended = false
    private var previous: PlaybackSnapshot? = null
    private var latest: PlaybackFact? = null
    private var saved: PlaybackFact? = null
    private var savedAt = 0L
    private var reportedAt = 0L

    fun bind(mediaId: String, sourceSession: Long, ownerGeneration: Long) {
        finish(FinishReason.REPLACED)
        media = mediaId; nativeSession = sourceSession; generation = ownerGeneration
        session = newSession(); sequence = 0; ended = false; previous = null; latest = null; saved = null
    }

    /** The router grants ownership only after a receiver status or the local restore is confirmed. */
    fun transfer(sourceSession: Long, ownerGeneration: Long) {
        nativeSession = sourceSession; generation = ownerGeneration; previous = null
    }

    fun observe(value: PlaybackSnapshot, ownerGeneration: Long) {
        if (value.simulated || value.media?.discNavigation == true || value.media?.id != media ||
            value.sessionId != nativeSession || ownerGeneration != generation) return
        if (ended) {
            if (previous?.status != PlaybackStatus.ENDED || value.status != PlaybackStatus.PLAYING) return
            bind(checkNotNull(media), nativeSession, generation)
        }
        val old = previous
        val last = latest
        if (value.status == PlaybackStatus.ERROR) { finish(FinishReason.ERROR); return }
        if (value.status == PlaybackStatus.IDLE || value.mediaInfo == null) return
        // Restoring a paused task and initial buffering do not manufacture a viewing session.
        if (last == null && (value.status != PlaybackStatus.PLAYING || value.pendingSeek != null)) return
        val paused = when (value.status) {
            PlaybackStatus.PAUSED -> true
            PlaybackStatus.PLAYING -> false
            else -> last?.paused ?: false
        }
        val seekChanged = value.seekResult != null && value.seekResult != old?.seekResult
        val frameChanged = value.frameResult != null && value.frameResult != old?.frameResult
        val unsafe = value.pendingSeek != null || value.pendingFrame != null ||
            (seekChanged && value.seekResult?.succeeded != true) ||
            (frameChanged && value.frameResult?.failure != null)
        val position = if (unsafe) last?.positionMs ?: return else
            (if (paused) value.framePositionMs ?: value.positionMs else value.positionMs).coerceAtLeast(0)
        val reasons = buildSet {
            if (last != null && paused != last.paused) add(if (paused) ProgressReason.PAUSE else ProgressReason.UNPAUSE)
            if (!unsafe && (seekChanged || frameChanged)) add(ProgressReason.SEEK)
            if (last != null && value.selectedAudioId != last.audio) add(ProgressReason.AUDIO)
            if (last != null && value.selectedSubtitleId != last.subtitle) add(ProgressReason.SUBTITLE)
            if (last != null && value.speed != last.speed) add(ProgressReason.SPEED)
            if (last != null && old == null) add(ProgressReason.OWNER)
        }
        val watchedAt = if (last == null || position != last.positionMs || paused != last.paused) wall() else last.lastWatchedAt
        val now = monotonic()
        val fact = PlaybackFact(session, sequence + 1, if (last == null) ReportKind.START else ReportKind.PROGRESS,
            position, value.durationMs?.takeIf { it > 0 }, paused, value.canSeek, value.speed,
            value.selectedAudioId, value.selectedSubtitleId, reasons.ifEmpty { setOf(ProgressReason.TIME) },
            lastWatchedAt = watchedAt)
        previous = value; latest = fact
        if (value.status == PlaybackStatus.ENDED) { finish(FinishReason.ENDED); return }
        val changed = saved?.let { it.positionMs != position || it.paused != paused || it.durationMs != fact.durationMs ||
            it.audio != fact.audio || it.subtitle != fact.subtitle || it.speed != fact.speed } ?: true
        val persist = changed && (last == null || reasons.isNotEmpty() || now - savedAt >= 5_000)
        val report = last == null || reasons.isNotEmpty() || now - reportedAt >= 10_000
        if (persist || report) {
            sequence++; val event = fact.copy(sequence = sequence, persist = persist, report = report)
            if (persist) { saved = event; savedAt = now }
            if (report) reportedAt = now
            emit(event)
        }
    }

    fun finish(reason: FinishReason) {
        if (ended) return
        ended = true
        if (reason != FinishReason.ENDED) previous = null
        latest?.let { emit(it.copy(sequence = ++sequence, kind = ReportKind.FINISH, finish = reason, persist = true, report = true, opensSession = false)) }
    }
}
