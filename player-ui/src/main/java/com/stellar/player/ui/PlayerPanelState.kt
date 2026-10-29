package com.stellar.player.ui

import com.stellar.player.contract.*

enum class RepeatMode { ALL, RANDOM, ONE }
enum class CorePanel { PLAYLIST, AUDIO_SUBTITLES, PLAYBACK, SUBTITLES, SUBTITLE_STYLE, SUBTITLE_DELAY, SUBTITLE_IMPORT, SUBTITLE_SEARCH, SPEED, CUSTOM_SPEED, AUDIO, VIDEO,
    MORE, TIMER, EFFECT, BOOKMARKS, INFO, FRAME, SYNC, CAST, SHARE, SNAPSHOT, FIT, CONTROLS, TOOLS }
enum class PanelMessage { SPEED_UNAVAILABLE, INVALID_SPEED, TRACK_UNAVAILABLE, EPISODES_UNAVAILABLE, EPISODE_UNAVAILABLE }
enum class SubtitlePreset { CUSTOM, MOVIES, ANIMATION, MINIMAL }
enum class SubtitleColor(val rgb: Long) { WHITE(0xFFFFFF), YELLOW(0xDFD447), BLUE(0x41A8EA), RED(0xD64580), BLACK(0x000000), ANIMATION_WHITE(0xF9F9F9), ANIMATION_BLUE(0x3AA2DB) }

data class SubtitleStyle(
    val preset: SubtitlePreset = SubtitlePreset.CUSTOM,
    val size: Int = 17,
    val bold: Boolean = false,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val opacity: Float = 1f,
    val outline: SubtitleColor = SubtitleColor.WHITE,
    val outlineOpacity: Float = 1f,
    val shadow: Float = 0f,
    val bottom: Int = 0,
) {
    companion object {
        fun preset(value: SubtitlePreset): SubtitleStyle = when (value) {
            SubtitlePreset.CUSTOM -> SubtitleStyle()
            SubtitlePreset.MOVIES -> SubtitleStyle(value, 20, true, opacity = 229f / 255f, outline = SubtitleColor.BLACK, outlineOpacity = 229f / 255f, shadow = 0.5f, bottom = 9)
            SubtitlePreset.ANIMATION -> SubtitleStyle(value, 20, color = SubtitleColor.ANIMATION_WHITE, outline = SubtitleColor.ANIMATION_BLUE, shadow = 0.1f, bottom = 8)
            SubtitlePreset.MINIMAL -> SubtitleStyle(value, 17, true, opacity = 229f / 255f, outlineOpacity = 0f, shadow = 0.5f, bottom = 8)
        }
    }
}

data class PlayerPanelState(
    val active: CorePanel? = null,
    val queue: List<MediaDescriptor> = emptyList(),
    val episodeQueue: Boolean = false,
    val queuePreparing: Boolean = false,
    val repeat: RepeatMode = RepeatMode.ALL,
    val style: SubtitleStyle = SubtitleStyle(),
    val delayMs: Int = 0,
    val message: PanelMessage? = null,
    val tools: PlayerToolsState = PlayerToolsState(),
)

interface PlayerPanelActions : PlayerToolActions {
    fun captureAnimation() {}
    fun showPanel(panel: CorePanel)
    fun dismissPanel()
    fun panelBack()
    fun selectMedia(id: String)
    fun adjacentMedia(offset: Int) {}
    fun removeMedia(id: String)
    fun clearQueue()
    fun setRepeat(mode: RepeatMode)
    fun selectTrack(kind: TrackKind, id: String?)
    fun selectSecondarySubtitle(id: String?) {}
    fun loadLocalSubtitle() {}
    fun loadUrlSubtitle() {}
    fun importSubtitle(id: String)
    fun setSubtitleStyle(style: SubtitleStyle)
    fun setSubtitlePreset(preset: SubtitlePreset)
    fun setSubtitleDelay(delayMs: Int)
    fun setSpeed(speed: Float, custom: Boolean = false)
    fun selectChapter(number: Int) {}
    fun setEqualizer(settings: EqualizerSettings) {}
}

/** Repeating fixture cue: the final two seconds are a gap, making delay observable. */
fun subtitleCue(snapshot: PlaybackSnapshot, delayMs: Int): String? {
    val text = snapshot.media?.subtitleTracks?.firstOrNull { it.id == snapshot.selectedSubtitleId }?.sampleText ?: return null
    val time = snapshot.positionMs - delayMs
    return text.takeIf { time >= 0 && time % 10_000 < 8_000 }
}
