package com.stellar.player.contract

import kotlinx.coroutines.flow.StateFlow

enum class MediaKind { VIDEO, AUDIO }
enum class PlaybackStatus { IDLE, PLAYING, PAUSED, ENDED, BUFFERING, ERROR }
enum class TrackKind { SUBTITLE, AUDIO, VIDEO }

data class TrackDescriptor(val id: String, val kind: TrackKind, val label: String, val sampleText: String? = null,
    val language: String? = null, val title: String? = null)
data class ExternalSubtitle(val id: String, val label: String, val path: String)

data class MediaChapter(val number: Int, val positionMs: Long)
enum class RejectionReason { STALE_SESSION, INVALID_STATE, SEEK_UNAVAILABLE, SPEED_UNAVAILABLE, TRACK_UNAVAILABLE, FRAME_UNAVAILABLE, OPERATION_BUSY }
enum class PlaybackError { SOURCE_UNAVAILABLE, UNSUPPORTED_MEDIA, AUTHENTICATION_FAILED,
    SOURCE_NOT_FOUND, ACCESS_DENIED, NETWORK_TIMEOUT, ENCRYPTION_FAILED,
    AUDIO_OUTPUT_FAILED, VIDEO_OUTPUT_FAILED, NO_PLAYABLE_TRACKS, PLAYBACK_FAILED }
data class Capabilities(
    val canSeek: Boolean = true,
    val canSetSpeed: Boolean = true,
    val minSpeed: Float = 0.5f,
    val maxSpeed: Float = 6f,
    val canSelectSubtitle: Boolean = true,
    val canSelectAudio: Boolean = true,
    val canStyleSubtitle: Boolean = true,
    val canDelaySubtitle: Boolean = true,
    val canImportSubtitle: Boolean = true,
    val canAdjustDevice: Boolean = true,
    val canSetAudioGain: Boolean = false,
    val canEqualize: Boolean = false,
    val canStepFrame: Boolean = false,
    val canSelectVideo: Boolean = false,
    val canSetHardwareDecoding: Boolean = false,
    val canSelectSecondarySubtitle: Boolean = false,
)

/** Opaque media identity; no engine, Android, or Surface types leak into the UI contract. */
data class MediaDescriptor(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val durationMs: Long?,
    val aspectRatio: Float = 16f / 9f,
    val subtitleTracks: List<TrackDescriptor> = emptyList(),
    val audioTracks: List<TrackDescriptor> = emptyList(),
    val capabilities: Capabilities = Capabilities(),
    val videoTracks: List<TrackDescriptor> = emptyList(),
    val chapters: List<MediaChapter> = emptyList(),
    val discNavigation: Boolean = false,
) {
    init {
        require(durationMs == null || durationMs >= 0)
        require(aspectRatio.isFinite() && aspectRatio > 0)
        require(subtitleTracks.all { it.kind == TrackKind.SUBTITLE })
        require(audioTracks.all { it.kind == TrackKind.AUDIO })
        require(videoTracks.all { it.kind == TrackKind.VIDEO })
        require((subtitleTracks + audioTracks + videoTracks).map { it.id }.distinct().size == subtitleTracks.size + audioTracks.size + videoTracks.size)
    }
}

data class PlaybackSnapshot(
    val sessionId: Long = 0,
    val media: MediaDescriptor? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val selectedSubtitleId: String? = null,
    val subtitleDelayMs: Int = 0,
    val externalSubtitle: ExternalSubtitle? = null,
    val selectedAudioId: String? = null,
    val selectedVideoId: String? = null,
    val error: PlaybackError? = null,
    val simulated: Boolean = true,
    /** A receiver owns decoding/output; local device controls and Surface tools do not apply. */
    val remotePlayback: Boolean = false,
    /** The receiver rejected the subtitle request, or the external file could not be prepared. */
    val remoteSubtitleFailed: Boolean = false,
    val pendingSeek: PendingSeek? = null,
    val seekResult: SeekResult? = null,
    val mediaInfo: String? = null,
    val details: MediaDetails? = null,
    /** Actual decoder state; a hardware preference alone must not light the control. */
    val hardwareDecoding: Boolean = false,
    /** Selected video upscaler; HDR and non-upscaled frames retain ordinary rendering. */
    /** Session-scoped requested software gain; system stream volume remains host-owned. */
    val audioGainDb: Float = 0f,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val pendingFrame: PendingFrameStep? = null,
    val frameResult: FrameStepResult? = null,
    /** First decoded timestamp confirmed by a paused seek/step, not a render-completion signal. */
    val framePositionMs: Long? = null,
    /** Terminal-step limit derived from video duration/rate; not an absolute VFR frame index. */
    val frameEndBoundMs: Long? = null,
    val selectedSecondarySubtitleId: String? = null,
) {
    val durationMs: Long? get() = media?.durationMs
    val capabilities: Capabilities get() = media?.capabilities ?: Capabilities(false, false, canSelectSubtitle = false, canSelectAudio = false)
    val canSeek: Boolean get() = media != null && capabilities.canSeek && (durationMs ?: 0) > 0
    val canStepFrame: Boolean get() = media?.kind == MediaKind.VIDEO && capabilities.canStepFrame && canSeek
    fun canStep(direction: Int): Boolean = (direction == -1 || direction == 1) && canStepFrame &&
        status == PlaybackStatus.PAUSED && pendingSeek == null && pendingFrame == null &&
        !(direction < 0 && (framePositionMs ?: positionMs) == 0L) &&
        !(frameEndBoundMs != null && (framePositionMs ?: positionMs) >= frameEndBoundMs) &&
        !(frameResult?.failure == FrameStepFailure.BOUNDARY && frameResult.direction == direction)
}

/** Source video metadata, independent of the display's current output mode. */
enum class VideoDynamicRange { UNKNOWN, SDR, HDR, DOLBY_VISION }

data class MediaDetails(
    val format: String, val videoCodec: String, val width: Int, val height: Int,
    val videoDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
)

/** Estimated queued playback interval, not a downloaded-file or persistent-cache range.
 * Kept separate from PlaybackSnapshot so buffer updates need not invalidate the page. */
data class PlaybackBuffer(val sessionId: Long = 0, val startMs: Long = 0, val endMs: Long = 0)

data class PendingSeek(val id: Long, val targetMs: Long)
data class SeekResult(val id: Long, val positionMs: Long?, val succeeded: Boolean)
data class PendingFrameStep(val id: Long, val direction: Int)
enum class FrameStepFailure { FAILED, BOUNDARY }
data class FrameStepResult(val id: Long, val direction: Int, val positionMs: Long?, val failure: FrameStepFailure? = null)
data class PlaybackRestore(val positionMs: Long = 0, val speed: Float = 1f,
    val subtitleId: String? = null, val audioId: String? = null, val videoId: String? = null,
    val subtitleDelayMs: Int = 0, val externalSubtitle: ExternalSubtitle? = null,
    val restoreSubtitleSelection: Boolean = true, val secondarySubtitleId: String? = null)

sealed interface PlaybackCommand {
    data class Open(val media: MediaDescriptor, val restore: PlaybackRestore? = null,
        val equalizer: EqualizerSettings = EqualizerSettings(), val subtitleLanguage: String? = null) : PlaybackCommand
    sealed interface InSession : PlaybackCommand { val sessionId: Long }
    data class Play(override val sessionId: Long) : InSession
    data class Pause(override val sessionId: Long) : InSession
    data class Close(override val sessionId: Long) : InSession
    data class Seek(override val sessionId: Long, val positionMs: Long) : InSession
    data class StepFrame(override val sessionId: Long, val direction: Int) : InSession
    data class SetSpeed(override val sessionId: Long, val speed: Float) : InSession
    data class SetHardwareDecoding(override val sessionId: Long, val enabled: Boolean) : InSession
    data class SetAudioGain(override val sessionId: Long, val decibels: Float) : InSession
    data class SetEqualizer(override val sessionId: Long, val settings: EqualizerSettings) : InSession
    data class SetSubtitleDelay(override val sessionId: Long, val delayMs: Int) : InSession
    data class LoadSubtitle(override val sessionId: Long, val subtitle: ExternalSubtitle) : InSession
    data class SelectTrack(override val sessionId: Long, val kind: TrackKind, val trackId: String?) : InSession
    data class SelectSecondarySubtitle(override val sessionId: Long, val trackId: String?) : InSession
}

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Rejected(val reason: RejectionReason) : CommandResult
}

interface PlaybackPort {
    val snapshots: StateFlow<PlaybackSnapshot>
    fun dispatch(command: PlaybackCommand): CommandResult
}
