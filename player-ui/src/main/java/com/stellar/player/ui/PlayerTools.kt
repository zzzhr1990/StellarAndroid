package com.stellar.player.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.contract.*
import kotlin.math.roundToInt

fun ToolMessage.resource() = when (this) {
    ToolMessage.LOCAL_ONLY -> R.string.tool_local_only
    ToolMessage.UNAVAILABLE -> R.string.tool_unavailable
    ToolMessage.TIMER_EXPIRED -> R.string.timer_expired
    ToolMessage.INVALID_TIMER -> R.string.timer_invalid
    ToolMessage.INVALID_BOOKMARK -> R.string.bookmark_invalid
    ToolMessage.BOOKMARK_SAVED -> R.string.bookmark_saved
    ToolMessage.HARDWARE_FLIP -> R.string.flip_hardware_hint
}
fun AudioEffect.resource() = when (this) { AudioEffect.STANDARD -> R.string.effect_standard; AudioEffect.CINEMA -> R.string.effect_cinema; AudioEffect.STEREO -> R.string.effect_stereo; AudioEffect.VOICE -> R.string.effect_voice }
fun ScreenFit.resource() = when (this) { ScreenFit.FIT -> R.string.fit_original; ScreenFit.STRETCH -> R.string.fit_stretch; ScreenFit.FILL -> R.string.fit_fill; ScreenFit.WIDE -> R.string.fit_wide; ScreenFit.CLASSIC -> R.string.fit_classic }

@Composable
fun PortraitMoreTools(
    snapshot: PlaybackSnapshot,
    livePanels: State<PlayerPanelState>,
    actions: PlayerPanelActions,
) {
    // Only the visible tool content observes the countdown, at second resolution.
    val panel by remember(livePanels) { derivedStateOf {
        val p = livePanels.value
        p.copy(active = CorePanel.MORE, tools = p.tools.copy(remainingMs = p.tools.remainingMs?.let { it / 1000 * 1000 }))
    } }
    ToolPanel(snapshot, panel, actions, showPlaybackSettings = false)
}

@Composable
internal fun ToolPanel(
    snapshot: PlaybackSnapshot,
    panel: PlayerPanelState,
    actions: PlayerPanelActions,
    showPlaybackSettings: Boolean = true,
) {
    if (snapshot.media == null) { Text(stringResource(R.string.tool_unavailable), modifier = Modifier.testTag("tool-message")); return }
    val t = panel.tools
    if (!snapshot.simulated && panel.active in setOf(CorePanel.EFFECT, CorePanel.SYNC,
            CorePanel.CAST, CorePanel.SHARE, CorePanel.SNAPSHOT)) {
        Text(stringResource(R.string.real_unavailable), modifier = Modifier.testTag("real-unavailable")); return
    }
    t.message?.let { Text(stringResource(it.resource()), color = Color(0xFFFFCF99), fontSize = 13.sp, modifier = Modifier.testTag("tool-message").padding(vertical = 8.dp)) }
    when (panel.active) {
        CorePanel.MORE -> MoreTools(snapshot, t, actions, showPlaybackSettings)
        CorePanel.TIMER -> TimerTools(t, actions)
        CorePanel.EFFECT -> {
            Text(stringResource(R.string.effect_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
            AudioEffect.entries.forEach { effect -> PanelButton(stringResource(effect.resource()), "effect-${effect.name}", { actions.tool(ToolEvent.Effect(effect)) }, t.effect == effect, Modifier.fillMaxWidth()) }
        }
        CorePanel.INFO -> {
            Text(stringResource(if (snapshot.simulated) R.string.info_sample else R.string.real_media), color = PlayerColors.Secondary, fontSize = 13.sp)
            Text(snapshot.media?.title.orEmpty(), modifier = Modifier.padding(vertical = 12.dp))
            if (snapshot.simulated) Text(stringResource(if (snapshot.media?.kind == MediaKind.AUDIO) R.string.info_audio else R.string.info_video), modifier = Modifier.testTag("media-info"))
            else snapshot.details?.let { details ->
                Text(stringResource(R.string.media_format, details.format), modifier = Modifier.testTag("media-info"))
                if (details.width > 0) Text(stringResource(R.string.video_format, details.videoCodec,
                    numberLabel(details.width), numberLabel(details.height)))
            }
            Text(stringResource(R.string.info_duration, formatTime(snapshot.durationMs)), modifier = Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.info_tracks, numberLabel(snapshot.media?.audioTracks?.size ?: 0), numberLabel(snapshot.media?.subtitleTracks?.size ?: 0)))
        }
        CorePanel.FRAME -> FrameStepContent(snapshot, actions)
        CorePanel.SYNC -> {
            Text(stringResource(R.string.sync_value, decimalLabel(t.syncMs / 1000f)), modifier = Modifier.testTag("sync-value").padding(vertical = 12.dp))
            Text(stringResource(R.string.sync_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
            FlowRow {
                PanelButton(stringResource(R.string.sync_earlier), "sync-minus", { actions.tool(ToolEvent.Sync(t.syncMs - 200)) }, enabled = t.syncMs > -10_000)
                PanelButton(stringResource(R.string.reset), "sync-reset", { actions.tool(ToolEvent.Sync(0)) })
                PanelButton(stringResource(R.string.sync_later), "sync-plus", { actions.tool(ToolEvent.Sync(t.syncMs + 200)) }, enabled = t.syncMs < 10_000)
            }
        }
        CorePanel.CAST -> {
            Text(stringResource(R.string.cast_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
            listOf("living-room" to R.string.cast_living, "studio" to R.string.cast_studio).forEach { (id, resource) ->
                PanelButton(stringResource(resource), "cast-$id", { actions.tool(ToolEvent.Cast(id)) }, t.castDevice == id, Modifier.fillMaxWidth())
            }
            Text(stringResource(if (t.castDevice == null) R.string.cast_disconnected else R.string.cast_connected), modifier = Modifier.testTag("cast-state"))
            PanelButton(stringResource(R.string.cast_disconnect), "cast-disconnect", { actions.tool(ToolEvent.Cast(null)) })
        }
        CorePanel.SNAPSHOT -> {
            Text(stringResource(R.string.snapshot_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 12.dp).testTag("snapshot-preview")) {
                StaticDemoFrame(snapshot)
            }
            Text(stringResource(R.string.frame_position, formatTime(snapshot.positionMs), numberLabel((snapshot.positionMs % 1000).toInt())), fontSize = 13.sp)
        }
        CorePanel.SHARE -> {
            Text(stringResource(R.string.share_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
            Text(stringResource(R.string.share_preview, snapshot.media?.title.orEmpty(), formatTime(snapshot.positionMs)), modifier = Modifier.padding(vertical = 18.dp).testTag("share-preview"))
        }
        else -> Unit
    }
}

@Composable
private fun MoreTools(s: PlaybackSnapshot, t: PlayerToolsState, a: PlayerPanelActions, showPlaybackSettings: Boolean) {
    val video = s.media?.kind == MediaKind.VIDEO
    val hardware = if (s.simulated) t.hardware else s.hardwareDecoding
    val tools = buildList {
        if (video) add(Triple(R.string.screenshot, R.drawable.hills_photo_camera, "SNAPSHOT"))
        if (video && !s.simulated && !showPlaybackSettings) add(Triple(R.string.capture_animation, R.drawable.hills_video_library, "animated"))
        if (s.simulated || video) add(Triple(if (s.simulated) R.string.mini_player else R.string.picture_in_picture, R.drawable.icon_video_frame, "mini"))
        add(Triple(R.string.frame_step, R.drawable.icon_video_frame, "FRAME"))
        add(Triple(R.string.timer, if (t.remainingMs != null) R.drawable.icon_video_clock_on else R.drawable.icon_video_clock_off, "TIMER"))
        if (video) add(Triple(R.string.background, if (t.background) R.drawable.icon_video_back_play_on else R.drawable.icon_video_back_play, "background"))
        add(Triple(R.string.media_info, R.drawable.icon_video_detail, "INFO"))
        add(Triple(R.string.hardware, R.drawable.icon_video_hardware, "hardware"))
        add(Triple(R.string.av_sync, R.drawable.icon_va_sync, "SYNC"))
        add(Triple(R.string.share, R.drawable.icon_video_share, "SHARE"))
        add(Triple(R.string.audio_effect, R.drawable.icon_video_equalizer, "EFFECT"))
        add(Triple(R.string.diagnostics, R.drawable.hills_info, "diagnostics"))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val configuration = LocalConfiguration.current
        val columns = if (maxWidth >= 360.dp && configuration.fontScale <= 1f && configuration.locales[0].language != "en") 5 else 3
        Column {
            tools.chunked(columns).forEach { row -> Row(Modifier.fillMaxWidth()) {
                row.forEach { (labelId, icon, id) ->
                    val label = stringResource(labelId)
                    val active = if (id == "diagnostics") t.diagnostics else hardware
                    val highlighted = id == "hardware" || id == "diagnostics"
                    val hardwareColor = if (active) PlayerColors.Accent else LocalContentColor.current
                    Column(Modifier.weight(1f).heightIn(min = 88.dp).clip(RoundedCornerShape(6.dp)).testTag("tool-$id")
                        .semantics { contentDescription = label; role = Role.Button; if (highlighted) selected = active }
                        .clickable(enabled = id != "hardware" || s.simulated || s.capabilities.canSetHardwareDecoding) {
                            when (id) {
                                "animated" -> a.captureAnimation()
                                "mini" -> a.tool(ToolEvent.Mini)
                                "background" -> a.tool(ToolEvent.Background)
                                "hardware" -> a.tool(ToolEvent.Hardware)
                                "diagnostics" -> a.tool(ToolEvent.Diagnostics)
                                else -> a.showPanel(CorePanel.valueOf(id))
                            }
                        }.padding(vertical = 12.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(icon), null, Modifier.size(28.dp),
                            colorFilter = if (highlighted) ColorFilter.tint(hardwareColor) else null)
                        Text(label, color = if (highlighted) hardwareColor else LocalContentColor.current,
                            fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
    t.remainingMs?.let { Text(stringResource(R.string.timer_remaining, formatTime(it)), color = PlayerColors.Accent, modifier = Modifier.testTag("timer-summary")) }
    Text(stringResource(if (s.simulated) R.string.tool_local_only else R.string.real_tools_hint), color = PlayerColors.Secondary, fontSize = 12.sp)
    if (!showPlaybackSettings) return
    if (video) {
        Text(stringResource(R.string.screen_fit), color = PlayerColors.Secondary, modifier = Modifier.padding(top = 20.dp))
        FlowRow { ScreenFit.entries.forEach { fit -> PanelButton(stringResource(fit.resource()), "fit-${fit.name}", { a.tool(ToolEvent.Fit(fit)) }, t.fit == fit) } }
        FlowRow {
            PanelButton(stringResource(R.string.flip_horizontal), "flip-horizontal", { a.tool(ToolEvent.Flip(true)) }, t.flipHorizontal)
            PanelButton(stringResource(R.string.flip_vertical), "flip-vertical", { a.tool(ToolEvent.Flip(false)) }, t.flipVertical)
        }
    }
    Text(stringResource(R.string.seek_settings), color = PlayerColors.Secondary, modifier = Modifier.padding(top = 20.dp))
    PanelSlider(stringResource(R.string.seek_forward_seconds), t.seekSeconds.toFloat(), 1f..60f, "seek-button-setting", { a.tool(ToolEvent.SeekButton(it.roundToInt())) })
    PanelSlider(stringResource(R.string.seek_rewind_seconds), t.rewindSeconds.toFloat(), 1f..60f, "rewind-button-setting", { a.tool(ToolEvent.RewindButton(it.roundToInt())) })
    FlowRow {
        PanelButton(stringResource(R.string.seek_percent), "drag-percent", { a.tool(ToolEvent.DragMode(false)) }, !t.dragByTime)
        PanelButton(stringResource(R.string.seek_time), "drag-time", { a.tool(ToolEvent.DragMode(true)) }, t.dragByTime)
    }
    PanelSlider(stringResource(if (t.dragByTime) R.string.seek_drag_seconds else R.string.seek_drag_percent),
        (if (t.dragByTime) t.dragSeconds else t.dragPercent).toFloat(), if (t.dragByTime) 1f..60f else 1f..200f, "seek-drag-setting", { a.tool(ToolEvent.DragAmount(it.roundToInt())) })
}

@Composable
private fun TimerTools(t: PlayerToolsState, a: PlayerPanelActions) {
    Text(stringResource(R.string.timer_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
    Text(stringResource(R.string.timer_remaining, t.remainingMs?.let(::formatTime) ?: stringResource(R.string.timer_off)), modifier = Modifier.testTag("timer-remaining").padding(vertical = 12.dp))
    listOf(0 to R.string.timer_off, 30 to R.string.timer_30, 60 to R.string.timer_60, 120 to R.string.timer_120, 180 to R.string.timer_180).forEach { (minutes, label) ->
        PanelButton(stringResource(label), "timer-$minutes", { a.tool(ToolEvent.Timer(minutes)) }, t.timerMinutes == minutes, Modifier.fillMaxWidth())
    }
    val locale = LocalConfiguration.current.locales[0]
    var value by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val applyTimer = {
        val number = parseDecimal(value, locale)
        a.tool(ToolEvent.Timer(if (number != null && number in 1f..180f && number % 1f == 0f) number.toInt() else -1))
        keyboard?.hide(); focus.clearFocus()
    }
    NumberOrQueryField(value, { value = it }, stringResource(R.string.timer_custom), "timer-input", true, applyTimer)
    PanelButton(stringResource(R.string.apply), "timer-apply", applyTimer)
}

@Composable
private fun BookmarkTools(s: PlaybackSnapshot, t: PlayerToolsState, a: PlayerPanelActions) {
    var title by remember(s.sessionId) { mutableStateOf("") }
    var editing by remember(s.sessionId) { mutableStateOf<Long?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Text(stringResource(R.string.bookmark_hint), color = PlayerColors.Secondary, fontSize = 13.sp)
    val save = {
        a.tool(ToolEvent.SaveBookmark(title, editing)); keyboard?.hide(); focus.clearFocus()
        if (title.trim().length in 1..80) { title = ""; editing = null }
    }
    NumberOrQueryField(title, { title = it }, stringResource(R.string.bookmark_title), "bookmark-input", false, save)
    FlowRow {
        PanelButton(stringResource(if (editing == null) R.string.bookmark_add else R.string.bookmark_save), "bookmark-save", save, enabled = s.canSeek)
        if (editing != null) PanelButton(stringResource(R.string.cancel), "bookmark-cancel", { title = ""; editing = null })
    }
    if (t.bookmarks.isEmpty()) Text(stringResource(R.string.bookmark_empty), modifier = Modifier.padding(vertical = 20.dp).testTag("bookmark-empty"))
    t.bookmarks.forEach { mark ->
        PanelButton(stringResource(R.string.bookmark_item, formatTime(mark.positionMs), mark.title), "bookmark-${mark.id}", { a.tool(ToolEvent.GoBookmark(mark.id)) }, modifier = Modifier.fillMaxWidth())
        FlowRow {
            PanelButton(stringResource(R.string.edit), "bookmark-edit-${mark.id}", { editing = mark.id; title = mark.title })
            PanelButton(stringResource(R.string.delete), "bookmark-delete-${mark.id}", { a.tool(ToolEvent.DeleteBookmark(mark.id)); if (editing == mark.id) { editing = null; title = "" } })
        }
    }
    if (t.bookmarks.isNotEmpty()) PanelButton(stringResource(R.string.bookmark_clear), "bookmark-clear", { a.tool(ToolEvent.ClearBookmarks); editing = null; title = "" })
}

@Composable
fun PlaybackStatusOverlay(snapshot: PlaybackSnapshot, retry: () -> Unit, modifier: Modifier = Modifier) {
    if (snapshot.status == PlaybackStatus.BUFFERING) {
        Column(modifier.testTag("status-overlay"), horizontalAlignment = Alignment.CenterHorizontally) {
            PlaybackBufferingIndicator()
            if (snapshot.simulated) {
                PanelButton(stringResource(R.string.finish_buffering), "status-action", retry)
            }
        }
        return
    }
    if (snapshot.status != PlaybackStatus.ERROR && snapshot.status != PlaybackStatus.ENDED) return
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(PlayerColors.Detail).pointerInput(Unit) { detectTapGestures {} }.padding(horizontal = 12.dp)
        .testTag("status-overlay"), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(when (snapshot.status) {
            PlaybackStatus.ERROR -> when (snapshot.error) {
                PlaybackError.UNSUPPORTED_MEDIA -> R.string.error_unsupported
                PlaybackError.AUTHENTICATION_FAILED -> R.string.error_authentication
                PlaybackError.SOURCE_NOT_FOUND -> R.string.error_not_found
                PlaybackError.ACCESS_DENIED -> R.string.error_access_denied
                PlaybackError.NETWORK_TIMEOUT -> R.string.error_network_timeout
                PlaybackError.ENCRYPTION_FAILED -> R.string.error_encryption
                PlaybackError.AUDIO_OUTPUT_FAILED -> R.string.error_audio_output
                PlaybackError.VIDEO_OUTPUT_FAILED -> R.string.error_video_output
                PlaybackError.NO_PLAYABLE_TRACKS -> R.string.error_no_playable_tracks
                PlaybackError.PLAYBACK_FAILED -> R.string.error_playback
                else -> R.string.error_source
            }
            else -> R.string.state_ended
        }), fontSize = 13.sp, modifier = Modifier.weight(1f))
        PanelButton(stringResource(if (snapshot.status == PlaybackStatus.ERROR) R.string.retry else R.string.replay), "status-action", retry)
    }
}
