package com.stellar.player.ui

enum class AudioEffect { STANDARD, CINEMA, STEREO, VOICE }
enum class ScreenFit { FIT, STRETCH, FILL, WIDE, CLASSIC }
enum class ToolMessage { LOCAL_ONLY, UNAVAILABLE, TIMER_EXPIRED, INVALID_TIMER, INVALID_BOOKMARK, BOOKMARK_SAVED, HARDWARE_FLIP }
data class PlaybackBookmark(val id: Long, val positionMs: Long, val title: String)
data class PlayerToolsState(
    val remainingMs: Long? = null,
    val timerMinutes: Int = 0,
    val effect: AudioEffect = AudioEffect.STANDARD,
    val bookmarks: List<PlaybackBookmark> = emptyList(),
    val nextBookmarkId: Long = 1,
    val syncMs: Int = 0,
    val hardware: Boolean = true,
    val diagnostics: Boolean = false,
    val background: Boolean = false,
    val castDevice: String? = null,
    val mini: Boolean = false,
    val fit: ScreenFit = ScreenFit.FIT,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val seekSeconds: Int = 10,
    val rewindSeconds: Int = 10,
    val dragByTime: Boolean = false,
    val dragSeconds: Int = 10,
    val dragPercent: Int = 100,
    val doubleTapSeek: Boolean = false,
    val doubleTapPlay: Boolean = true,
    val hideWhenPaused: Boolean = true,
    val message: ToolMessage? = null,
)
sealed interface ToolEvent {
    data class Timer(val minutes: Int) : ToolEvent
    data class Effect(val value: AudioEffect) : ToolEvent
    data class SaveBookmark(val title: String, val id: Long? = null) : ToolEvent
    data class DeleteBookmark(val id: Long) : ToolEvent
    data class GoBookmark(val id: Long) : ToolEvent
    data object ClearBookmarks : ToolEvent
    data class Sync(val valueMs: Int) : ToolEvent
    data class Frame(val direction: Int) : ToolEvent
    data object ResumeFrames : ToolEvent
    data object Hardware : ToolEvent
    data object Diagnostics : ToolEvent
    data object Background : ToolEvent
    data class Cast(val deviceId: String?) : ToolEvent
    data object Mini : ToolEvent
    data object Rotate : ToolEvent
    data class Fit(val value: ScreenFit) : ToolEvent
    data class Flip(val horizontal: Boolean) : ToolEvent
    data class SeekButton(val seconds: Int) : ToolEvent
    data class RewindButton(val seconds: Int) : ToolEvent
    data class DragMode(val byTime: Boolean) : ToolEvent
    data class DragAmount(val value: Int) : ToolEvent
    data class DoubleTapSeek(val enabled: Boolean) : ToolEvent
    data class DoubleTapPlay(val enabled: Boolean) : ToolEvent
    data class HideWhenPaused(val enabled: Boolean) : ToolEvent
}
interface PlayerToolActions { fun tool(event: ToolEvent) }
