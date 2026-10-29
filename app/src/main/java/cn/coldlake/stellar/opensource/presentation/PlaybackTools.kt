package cn.coldlake.stellar.opensource.presentation

import com.stellar.player.contract.*
import com.stellar.player.ui.*

/** Pure session-local tool state. Platform effects are deliberately not owned here. */
class PlaybackTools(initial: PlayerToolsState = PlayerToolsState()) {
    var state = initial
        private set
    fun reset() { state = PlayerToolsState(doubleTapSeek = state.doubleTapSeek,
        doubleTapPlay = state.doubleTapPlay, hideWhenPaused = state.hideWhenPaused) }
    fun message(value: ToolMessage?) { state = state.copy(message = value) }
    fun closeMini() { state = state.copy(mini = false) }
    fun interactionOptions(options: PlaybackInteractionOptions, hideWhenPaused: Boolean) {
        state = state.copy(doubleTapPlay = options.doubleTapPlay, doubleTapSeek = options.doubleTapSeek,
            seekSeconds = options.forwardSeconds, rewindSeconds = options.rewindSeconds, hideWhenPaused = hideWhenPaused)
    }
    fun advance(elapsedMs: Long): Boolean {
        require(elapsedMs in 0..86_400_000)
        val remaining = state.remainingMs ?: return false
        val next = (remaining - elapsedMs).coerceAtLeast(0)
        state = state.copy(remainingMs = next.takeIf { it > 0 }, timerMinutes = if (next == 0L) 0 else state.timerMinutes,
            message = if (next == 0L) ToolMessage.TIMER_EXPIRED else state.message)
        return next == 0L
    }
    fun apply(event: ToolEvent, snapshot: PlaybackSnapshot) {
        val media = snapshot.media
        if (media == null) { message(ToolMessage.UNAVAILABLE); return }
        message(null)
        val video = media.kind == MediaKind.VIDEO
        when (event) {
            ToolEvent.Diagnostics -> state = state.copy(diagnostics = !state.diagnostics)
            is ToolEvent.Timer -> if (event.minutes !in 0..180) message(ToolMessage.INVALID_TIMER)
                else state = state.copy(timerMinutes = event.minutes, remainingMs = (event.minutes * 60_000L).takeIf { it > 0 })
            is ToolEvent.Effect -> message(ToolMessage.UNAVAILABLE)

            is ToolEvent.SaveBookmark -> {
                val title = event.title.trim()
                val existing = state.bookmarks.firstOrNull { it.id == event.id }
                if (!snapshot.canSeek || title.isEmpty() || title.length > 80 || (event.id != null && existing == null) || (event.id == null && state.bookmarks.size >= 50)) {
                    message(ToolMessage.INVALID_BOOKMARK); return
                }
                val item = existing?.copy(title = title) ?: PlaybackBookmark(state.nextBookmarkId, snapshot.positionMs, title)
                state = state.copy(bookmarks = (state.bookmarks.filterNot { it.id == item.id } + item).sortedBy { it.positionMs },
                    nextBookmarkId = state.nextBookmarkId + if (existing == null) 1 else 0, message = ToolMessage.BOOKMARK_SAVED)
            }
            is ToolEvent.DeleteBookmark -> state = state.copy(bookmarks = state.bookmarks.filterNot { it.id == event.id })
            ToolEvent.ClearBookmarks -> state = state.copy(bookmarks = emptyList())
            is ToolEvent.Sync -> if (!video || media.audioTracks.isEmpty()) message(ToolMessage.UNAVAILABLE)
                else state = state.copy(syncMs = event.valueMs.coerceIn(-10_000, 10_000), message = ToolMessage.LOCAL_ONLY)
            ToolEvent.Hardware -> if (!video) message(ToolMessage.UNAVAILABLE) else state = state.copy(hardware = !state.hardware,
                flipHorizontal = false, flipVertical = false, message = ToolMessage.LOCAL_ONLY)
            ToolEvent.Background -> if (!video) message(ToolMessage.UNAVAILABLE) else state = state.copy(background = !state.background, message = ToolMessage.LOCAL_ONLY)
            is ToolEvent.Cast -> if (!video || event.deviceId !in listOf(null, "living-room", "studio")) message(ToolMessage.UNAVAILABLE)
                else state = state.copy(castDevice = event.deviceId, message = ToolMessage.LOCAL_ONLY)
            ToolEvent.Mini -> if (snapshot.status in listOf(PlaybackStatus.ERROR, PlaybackStatus.BUFFERING)) message(ToolMessage.UNAVAILABLE)
                else state = state.copy(mini = true, message = ToolMessage.LOCAL_ONLY)
            is ToolEvent.Fit -> if (!video) message(ToolMessage.UNAVAILABLE) else state = state.copy(fit = event.value)
            is ToolEvent.Flip -> if (!video) message(ToolMessage.UNAVAILABLE) else if (snapshot.simulated && state.hardware) message(ToolMessage.HARDWARE_FLIP)
                else state = if (event.horizontal) state.copy(flipHorizontal = !state.flipHorizontal) else state.copy(flipVertical = !state.flipVertical)
            is ToolEvent.SeekButton -> state = state.copy(seekSeconds = event.seconds.coerceIn(1, 60))
            is ToolEvent.RewindButton -> state = state.copy(rewindSeconds = event.seconds.coerceIn(1, 60))
            is ToolEvent.DoubleTapSeek -> state = state.copy(doubleTapSeek = event.enabled)
            is ToolEvent.DoubleTapPlay -> state = state.copy(doubleTapPlay = event.enabled)
            is ToolEvent.HideWhenPaused -> state = state.copy(hideWhenPaused = event.enabled)
            is ToolEvent.DragMode -> state = state.copy(dragByTime = event.byTime)
            is ToolEvent.DragAmount -> state = if (state.dragByTime) state.copy(dragSeconds = event.value.coerceIn(1, 60))
                else state.copy(dragPercent = event.value.coerceIn(1, 200))
            is ToolEvent.Frame, ToolEvent.ResumeFrames, is ToolEvent.GoBookmark, ToolEvent.Rotate -> Unit // Playback commands belong to the coordinator.
        }
    }
}
