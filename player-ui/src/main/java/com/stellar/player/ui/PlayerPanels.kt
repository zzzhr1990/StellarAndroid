package com.stellar.player.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.stellar.player.contract.*
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.roundToInt

fun CorePanel.titleResource() = when (this) {
    CorePanel.PLAYLIST -> R.string.playlist
    CorePanel.AUDIO_SUBTITLES -> R.string.audio_and_subtitles
    CorePanel.PLAYBACK -> R.string.playback_tab
    CorePanel.SUBTITLES -> R.string.subtitles
    CorePanel.SUBTITLE_STYLE -> R.string.subtitle_style
    CorePanel.SUBTITLE_DELAY -> R.string.subtitle_delay
    CorePanel.SUBTITLE_IMPORT -> R.string.import_sample
    CorePanel.SUBTITLE_SEARCH -> R.string.search_sample
    CorePanel.SPEED -> R.string.speed
    CorePanel.CUSTOM_SPEED -> R.string.custom_speed
    CorePanel.AUDIO -> R.string.audio_tracks
    CorePanel.VIDEO -> R.string.video_tracks
    CorePanel.MORE -> R.string.more
    CorePanel.TIMER -> R.string.timer
    CorePanel.EFFECT -> R.string.audio_effect
    CorePanel.BOOKMARKS -> R.string.bookmarks
    CorePanel.INFO -> R.string.media_info
    CorePanel.FRAME -> R.string.frame_step
    CorePanel.SYNC -> R.string.av_sync
    CorePanel.CAST -> R.string.cast
    CorePanel.SHARE -> R.string.share
    CorePanel.SNAPSHOT -> R.string.snapshot
    CorePanel.FIT -> R.string.screen_fit
    CorePanel.CONTROLS -> R.string.hills_controls
    CorePanel.TOOLS -> R.string.hills_other_tools
}
fun RepeatMode.titleResource() = when (this) { RepeatMode.ALL -> R.string.repeat_all; RepeatMode.ONE -> R.string.repeat_one; RepeatMode.RANDOM -> R.string.repeat_random }
private fun SubtitlePreset.titleResource() = when (this) { SubtitlePreset.CUSTOM -> R.string.style_custom; SubtitlePreset.MOVIES -> R.string.style_movies; SubtitlePreset.ANIMATION -> R.string.style_animation; SubtitlePreset.MINIMAL -> R.string.style_minimal }
private fun SubtitleColor.titleResource() = when (this) { SubtitleColor.WHITE, SubtitleColor.ANIMATION_WHITE -> R.string.color_white; SubtitleColor.YELLOW -> R.string.color_yellow; SubtitleColor.BLUE, SubtitleColor.ANIMATION_BLUE -> R.string.color_blue; SubtitleColor.RED -> R.string.color_red; SubtitleColor.BLACK -> R.string.color_black }

/** One modal window owns the complete input region; background touches never reach player gestures. */
@Composable
fun PlayerPanels(snapshot: PlaybackSnapshot, state: PlayerPanelState, actions: PlayerPanelActions,
    samples: List<TrackDescriptor>, landscape: Boolean, rightHanded: Boolean = true,
    configureWindow: (android.view.Window) -> Unit = {}) {
    val active = state.active ?: return
    // Dialog creates another Android composition. Preserve the parent's app-local
    // resources and configuration together, including locale-dependent layout/formatting.
    val parentConfiguration = LocalConfiguration.current
    val parentResources = LocalResources.current
    val parentDirection = LocalLayoutDirection.current
    Dialog(onDismissRequest = actions::panelBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalConfiguration provides parentConfiguration, LocalResources provides parentResources,
            LocalLayoutDirection provides parentDirection) {
        val view = LocalView.current
        DisposableEffect(view, landscape) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                it.setDimAmount(0f)
                configureWindow(it)
            }
            onDispose {}
        }
        BoxWithConstraints(Modifier.fillMaxSize().then(if (landscape) Modifier else Modifier.safeDrawingPadding()).imePadding().testTag("panel-window")) {
            if (active == CorePanel.EFFECT && !snapshot.simulated) {
                EqualizerPanel(snapshot, actions, landscape)
                return@BoxWithConstraints
            }
            if (active == CorePanel.FRAME) {
                FrameStepPanel(snapshot, actions, landscape)
                return@BoxWithConstraints
            }
            if (landscape) {
                HillsPanelLayout(snapshot, state, actions, samples, rightHanded)
                return@BoxWithConstraints
            }
            // Retain the side-panel width when the landscape keyboard reduces available height.
            val sideWidth = remember(landscape) { minOf(maxHeight, maxWidth * 0.7f) }
            val compactKeyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0 && maxHeight < 180.dp
            val scrim = stringResource(R.string.dismiss_panel)
            Box(Modifier.fillMaxSize().testTag("panel-scrim").semantics { contentDescription = scrim }
                .clickable(onClick = actions::dismissPanel))
            val floating = active == CorePanel.SPEED || active == CorePanel.CUSTOM_SPEED || active == CorePanel.SUBTITLE_DELAY
            val size = if (landscape) {
                if (floating) Modifier.width(minOf(520.dp, maxWidth - 48.dp)).heightIn(max = maxHeight - 16.dp)
                else Modifier.width(sideWidth).fillMaxHeight()
            } else Modifier.fillMaxWidth().heightIn(max = minOf(if (floating) 520.dp else 400.dp, maxHeight * 0.78f))
            val shape = if (floating) RoundedCornerShape(10.dp) else if (landscape) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            Column(Modifier.align(if (landscape && !floating) Alignment.CenterEnd else Alignment.BottomEnd)
                .then(if (landscape && floating) Modifier.padding(end = 48.dp, bottom = 16.dp) else Modifier)
                .then(size).clip(shape).background(if (floating) Color(0xF2222230) else Color(0xFF1E1D2B))
                .pointerInput(Unit) { detectTapGestures {} }
                .testTag("core-panel")) {
                if (!compactKeyboard) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(active.titleResource()), fontSize = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                    PanelButton(stringResource(R.string.panel_back), "panel-back", actions::panelBack)
                }
                if (snapshot.simulated && (active == CorePanel.SUBTITLE_STYLE || active == CorePanel.SUBTITLE_DELAY)) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFF13131D)).padding(12.dp)) {
                        StyledSubtitle(subtitleCue(snapshot, state.delayMs) ?: stringResource(R.string.cue_gap), state.style, "subtitle-preview", 2)
                        if (active == CorePanel.SUBTITLE_DELAY) Text(stringResource(R.string.subtitle_time, formatTime((snapshot.positionMs - state.delayMs).coerceAtLeast(0))),
                            fontSize = 11.sp, color = PlayerColors.Secondary, modifier = Modifier.padding(top = 6.dp).testTag("subtitle-time"))
                    }
                }
                key(active) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    state.message?.let { message -> Text(stringResource(when (message) {
                        PanelMessage.SPEED_UNAVAILABLE -> R.string.reason_speed
                        PanelMessage.INVALID_SPEED -> R.string.invalid_speed
                        PanelMessage.TRACK_UNAVAILABLE -> R.string.reason_track
                        PanelMessage.EPISODES_UNAVAILABLE -> R.string.episodes_unavailable
                        PanelMessage.EPISODE_UNAVAILABLE -> R.string.episode_unavailable
                    }), color = Color(0xFFFFCF99), modifier = Modifier.padding(vertical = 8.dp).testTag("panel-message")) }
                    if (!snapshot.simulated && active in setOf(CorePanel.SUBTITLE_STYLE,
                            CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH)) {
                        Text(stringResource(R.string.real_unavailable), modifier = Modifier.testTag("real-unavailable"))
                    } else when (active) {
                        CorePanel.PLAYLIST -> QueuePanel(snapshot, state, actions)
                        CorePanel.AUDIO_SUBTITLES -> AudioSubtitleSettings(snapshot, state, actions)
                        CorePanel.PLAYBACK -> PlaybackSettings(snapshot, state.tools, actions)
                        CorePanel.VIDEO -> TrackDropdown(snapshot, TrackKind.VIDEO, actions::selectTrack)
                        CorePanel.SUBTITLES, CorePanel.AUDIO -> TrackPanel(snapshot, active == CorePanel.SUBTITLES, actions)
                        CorePanel.SUBTITLE_STYLE -> StylePanel(state.style, actions)
                        CorePanel.SUBTITLE_DELAY -> DelayPanel(state, actions)
                        CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH -> SamplePanel(snapshot, samples, active == CorePanel.SUBTITLE_SEARCH, actions)
                        CorePanel.SPEED, CorePanel.CUSTOM_SPEED -> SpeedPanel(snapshot, active == CorePanel.CUSTOM_SPEED, actions)
                        CorePanel.CONTROLS -> HillsControlSettings(state.tools, actions)
                        CorePanel.TOOLS -> ToolPanel(snapshot, state.copy(active = CorePanel.MORE), actions)
                        else -> ToolPanel(snapshot, state, actions)
                    }
                }
                }
            }
        }
        }
    }
}

@Composable
internal fun QueuePanel(snapshot: PlaybackSnapshot, state: PlayerPanelState, actions: PlayerPanelActions) {
    QueueHeader(state, actions)
    state.queue.forEach { media -> QueueEntry(media, snapshot.media?.id == media.id, actions) }
    if (state.queue.isNotEmpty()) QueueFooter(actions)
}

@Composable
internal fun QueueHeader(state: PlayerPanelState, actions: PlayerPanelActions) {
    Text(pluralStringResource(R.plurals.queue_count, state.queue.size, state.queue.size), color = PlayerColors.Secondary, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RepeatMode.entries.forEach { mode -> PanelButton(stringResource(mode.titleResource()), "repeat-${mode.name}", { actions.setRepeat(mode) }, state.repeat == mode) }
    }
    if (state.queue.isEmpty()) Text(stringResource(R.string.queue_empty), color = PlayerColors.Secondary, modifier = Modifier.padding(vertical = 24.dp).testTag("queue-empty"))
}

@Composable
internal fun QueueEntry(media: MediaDescriptor, selected: Boolean, actions: PlayerPanelActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 74.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) PlayerColors.Accent.copy(alpha = 0.17f) else Color.Transparent)
            .testTag("queue-${media.id}").semantics { this.selected = selected; role = Role.Button }
            .clickable { actions.selectMedia(media.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF303042)), contentAlignment = Alignment.Center) {
                Image(painterResource(if (media.kind == MediaKind.AUDIO) R.drawable.icon_music_bg else R.drawable.icon_cover_placeholder), null,
                    if (media.kind == MediaKind.AUDIO) Modifier.fillMaxSize() else Modifier.size(32.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(media.title, color = if (selected) Color(0xFF80ADFF) else Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(media.durationMs), fontSize = 12.sp, color = PlayerColors.Secondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val remove = stringResource(R.string.remove_media, media.title)
        Box(Modifier.size(48.dp).testTag("remove-${media.id}").semantics { contentDescription = remove; role = Role.Button }
            .clickable { actions.removeMedia(media.id) }, contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.icon_delete), null, Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun QueueFooter(actions: PlayerPanelActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.icon_delete_all), null, Modifier.size(24.dp))
        PanelButton(stringResource(R.string.clear_queue), "clear-queue", actions::clearQueue)
    }
}

@Composable
internal fun TrackPanel(snapshot: PlaybackSnapshot, subtitle: Boolean, actions: PlayerPanelActions) {
    val media = snapshot.media
    val tracks = if (subtitle) media?.subtitleTracks.orEmpty() else media?.audioTracks.orEmpty()
    val selected = if (subtitle) snapshot.selectedSubtitleId else snapshot.selectedAudioId
    val capable = if (subtitle) snapshot.capabilities.canSelectSubtitle else snapshot.capabilities.canSelectAudio
    if (!capable) Text(stringResource(R.string.reason_track), color = PlayerColors.Secondary, modifier = Modifier.testTag("tracks-unavailable"))
    if (tracks.isEmpty()) {
        if (subtitle) Image(painterResource(R.drawable.icon_subtitle_empty), null, Modifier.fillMaxWidth().height(100.dp))
        Text(stringResource(if (subtitle) R.string.subtitles_empty else R.string.audio_empty), color = PlayerColors.Secondary,
            modifier = Modifier.padding(vertical = 12.dp).testTag("tracks-empty"))
    }
    if (subtitle && snapshot.remotePlayback) RemoteSubtitleNotice(snapshot)
    if (subtitle) PanelButton(stringResource(if (snapshot.remotePlayback) R.string.remote_subtitles_off else R.string.subtitles_off), "subtitles-off", { actions.selectTrack(TrackKind.SUBTITLE, null) }, selected == null, enabled = capable)
    tracks.forEach { track -> PanelButton(track.label, "track-${track.id}", { actions.selectTrack(track.kind, track.id) }, selected == track.id,
        Modifier.fillMaxWidth(), capable && (!subtitle || track.id != snapshot.selectedSecondarySubtitleId)) }
    if (subtitle && snapshot.capabilities.canSelectSecondarySubtitle) {
        Spacer(Modifier.height(12.dp))
        TrackDropdown(snapshot, TrackKind.SUBTITLE, { _, id -> actions.selectSecondarySubtitle(id) }, secondary = true)
    }
    if (subtitle) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH, CorePanel.SUBTITLE_STYLE, CorePanel.SUBTITLE_DELAY).forEach { panel ->
                val supported = when (panel) {
                    CorePanel.SUBTITLE_STYLE -> snapshot.capabilities.canStyleSubtitle
                    CorePanel.SUBTITLE_DELAY -> snapshot.capabilities.canDelaySubtitle
                    else -> snapshot.capabilities.canImportSubtitle
                }
                PanelButton(stringResource(panel.titleResource()), "open-${panel.name}", { actions.showPanel(panel) }, enabled = capable && supported)
            }
        }
    }
}

@Composable
internal fun SamplePanel(snapshot: PlaybackSnapshot, samples: List<TrackDescriptor>, search: Boolean, actions: PlayerPanelActions) {
    Text(stringResource(R.string.sample_only), color = PlayerColors.Secondary, fontSize = 12.sp)
    var query by remember { mutableStateOf("") }
    if (search) NumberOrQueryField(query, { query = it }, stringResource(R.string.search_query), "subtitle-query", false)
    val results = samples.filter { (!search && it.id == "import-local") || (search && it.label.contains(query.trim(), ignoreCase = true)) }
    if (results.isEmpty()) Text(stringResource(R.string.search_empty), modifier = Modifier.padding(vertical = 16.dp).testTag("search-empty"))
    results.forEach { track -> PanelButton(track.label, "sample-${track.id}", { actions.importSubtitle(track.id) },
        snapshot.selectedSubtitleId == track.id, Modifier.fillMaxWidth()) }
}

@Composable
internal fun SpeedPanel(snapshot: PlaybackSnapshot, custom: Boolean, actions: PlayerPanelActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val caps = snapshot.capabilities
    val capable = caps.canSetSpeed && snapshot.media?.kind == MediaKind.VIDEO && (snapshot.durationMs ?: 0) > 0
    val low = maxOf(0.5f, caps.minSpeed); val high = minOf(8f, caps.maxSpeed)
    if (!capable) Text(stringResource(R.string.reason_speed), modifier = Modifier.testTag("speed-unavailable"))
    if (!custom) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0.5f, 1f, 1.2f, 1.5f, 2f, 3f, 5f, 6f, 8f).forEach { speed ->
                PanelButton(speedLabel(speed), "preset-$speed", { actions.setSpeed(speed) }, snapshot.speed == speed, enabled = capable && speed in low..high)
            }
            PanelButton(stringResource(R.string.custom_speed), "open-custom-speed", { actions.showPanel(CorePanel.CUSTOM_SPEED) }, enabled = capable)
        }
    } else if (low <= high) {
        Text(stringResource(R.string.speed_range, decimalLabel(low), decimalLabel(high)), color = PlayerColors.Secondary, fontSize = 12.sp)
        val locale = LocalConfiguration.current.locales[0]
        var value by remember(snapshot.speed, locale) { mutableStateOf(formatDecimal(snapshot.speed, locale)) }
        var invalid by remember { mutableStateOf(false) }
        NumberOrQueryField(value, { value = it; invalid = false }, stringResource(R.string.speed), "speed-input", true)
        if (invalid) Text(stringResource(R.string.invalid_speed), color = Color(0xFFFFCF99), modifier = Modifier.testTag("speed-input-error"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelButton(stringResource(R.string.decrease), "speed-minus", { actions.setSpeed(((snapshot.speed * 10).roundToInt() - 1) / 10f, true) }, enabled = capable && snapshot.speed > low)
            PanelButton(stringResource(R.string.apply), "apply-speed", {
                val number = parseDecimal(value, locale)
                invalid = number == null
                if (number != null) { actions.setSpeed(number, true); focus.clearFocus(); keyboard?.hide() }
            }, modifier = Modifier.weight(1f), enabled = capable)
            PanelButton(stringResource(R.string.increase), "speed-plus", { actions.setSpeed(((snapshot.speed * 10).roundToInt() + 1) / 10f, true) }, enabled = capable && snapshot.speed < high)
        }
        if (low < high) PanelSlider(stringResource(R.string.speed), snapshot.speed, low..high, "custom-speed-slider", { actions.setSpeed((it * 10).roundToInt() / 10f, true) })
    }
}

@Composable
internal fun DelayPanel(state: PlayerPanelState, actions: PlayerPanelActions) {
    Text(stringResource(R.string.delay_value, decimalLabel(state.delayMs / 1000f)), modifier = Modifier.padding(vertical = 8.dp).testTag("delay-value"))
    Text(stringResource(R.string.delay_hint), color = PlayerColors.Secondary, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(stringResource(R.string.subtitle_earlier), "delay-minus", { actions.setSubtitleDelay(state.delayMs - 100) }, enabled = state.delayMs > -60_000)
        PanelButton(stringResource(R.string.reset), "delay-reset", { actions.setSubtitleDelay(0) })
        PanelButton(stringResource(R.string.subtitle_later), "delay-plus", { actions.setSubtitleDelay(state.delayMs + 100) }, enabled = state.delayMs < 60_000)
    }
}

@Composable
internal fun StylePanel(style: SubtitleStyle, actions: PlayerPanelActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SubtitlePreset.entries.forEach { preset -> PanelButton(stringResource(preset.titleResource()), "style-${preset.name}", { actions.setSubtitlePreset(preset) }, style.preset == preset) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(17 to R.string.size_small, 20 to R.string.size_medium, 24 to R.string.size_large).forEach { (size, label) ->
            PanelButton(stringResource(label), "size-$size", { actions.setSubtitleStyle(style.copy(size = size)) }, style.size == size)
        }
        PanelButton(stringResource(R.string.font_bold), "font-bold", { actions.setSubtitleStyle(style.copy(bold = !style.bold)) }, style.bold)
    }
    Text(stringResource(R.string.font_color), color = PlayerColors.Secondary, fontSize = 12.sp)
    ColorChoices(listOf(SubtitleColor.WHITE, SubtitleColor.YELLOW, SubtitleColor.BLUE, SubtitleColor.RED), style.color, "font") { actions.setSubtitleStyle(style.copy(color = it)) }
    PanelSlider(stringResource(R.string.opacity), style.opacity * 100, 0f..100f, "font-opacity", { actions.setSubtitleStyle(style.copy(opacity = it / 100f)) })
    Text(stringResource(R.string.font_outline), color = PlayerColors.Secondary, fontSize = 12.sp)
    ColorChoices(listOf(SubtitleColor.WHITE, SubtitleColor.BLACK, SubtitleColor.BLUE), style.outline, "outline") { actions.setSubtitleStyle(style.copy(outline = it)) }
    PanelSlider(stringResource(R.string.opacity), style.outlineOpacity * 100, 0f..100f, "outline-opacity", { actions.setSubtitleStyle(style.copy(outlineOpacity = it / 100f)) })
    PanelSlider(stringResource(R.string.font_shadow), style.shadow, -3f..3f, "font-shadow", { actions.setSubtitleStyle(style.copy(shadow = (it * 10).roundToInt() / 10f)) })
    PanelSlider(stringResource(R.string.subtitle_position), style.bottom.toFloat(), 0f..255f, "subtitle-bottom", { actions.setSubtitleStyle(style.copy(bottom = it.roundToInt())) })
}

@Composable
private fun ColorChoices(colors: List<SubtitleColor>, selected: SubtitleColor, prefix: String, onSelect: (SubtitleColor) -> Unit) {
    Row {
        colors.forEach { color ->
            val label = stringResource(color.titleResource())
            Box(Modifier.size(48.dp).testTag("$prefix-${color.name}").semantics { contentDescription = label; this.selected = color == selected; role = Role.RadioButton }
                .clickable { onSelect(color) }.padding(10.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF000000 or color.rgb)).border(if (selected == color) 3.dp else 1.dp, if (selected == color) PlayerColors.Accent else Color.Gray, RoundedCornerShape(14.dp)))
        }
    }
}

@Composable
fun PanelButton(text: String, tag: String, click: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).testTag(tag).clip(RoundedCornerShape(6.dp))
        .background(if (selected) PlayerColors.Accent.copy(alpha = 0.17f) else Color.Transparent)
        .semantics { this.selected = selected; role = Role.Button }.clickable(enabled = enabled, onClick = click)
        .padding(horizontal = 10.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = if (!enabled) Color.Gray else if (selected) Color(0xFF80ADFF) else Color.White, fontSize = 14.sp)
    }
}

@Composable
internal fun PanelSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, onChange: (Float) -> Unit) {
    val progressColor = PlayerColors.Accent
    val latest by rememberUpdatedState(onChange)
    Text(stringResource(R.string.parameter_value, label, decimalLabel(value)), color = PlayerColors.Secondary, fontSize = 12.sp)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(48.dp).testTag(tag).semantics {
            contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value, range)
            setProgress { if (it.isFinite()) { latest(it.coerceIn(range)); true } else false }
        }.pointerInput(range) { detectTapGestures { p -> latest(range.start + (p.x / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start)) } }
            .pointerInput(range) { detectDragGestures { change, _ -> change.consume(); latest(range.start + (change.position.x / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start)) } }) {
            val y = size.height / 2; val x = size.width * ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            drawLine(PlayerColors.Track, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(progressColor, Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, 5.dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
internal fun NumberOrQueryField(value: String, change: (String) -> Unit, label: String, tag: String, numeric: Boolean, onDone: (() -> Unit)? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Text(label, fontSize = 12.sp, color = PlayerColors.Secondary, modifier = Modifier.padding(top = 12.dp))
    BasicTextField(value, change, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp))
        .background(Color(0xFF343447)).padding(12.dp).testTag(tag).semantics { contentDescription = label },
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, textDirection = if (numeric) TextDirection.Ltr else TextDirection.Content),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus(); onDone?.invoke() }), singleLine = true)
}

@Composable
fun StyledSubtitle(text: String, style: SubtitleStyle, tag: String, lines: Int = 1) {
    val common = TextStyle(fontSize = style.size.sp, fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        textDirection = TextDirection.ContentOrLtr, shadow = Shadow(Color.Black, Offset(style.shadow * 2, style.shadow * 2), kotlin.math.abs(style.shadow) * 2))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (style.outlineOpacity > 0f) Text(text, style = common.copy(drawStyle = Stroke(width = 2f)),
            color = Color(0xFF000000 or style.outline.rgb).copy(alpha = style.outlineOpacity), maxLines = lines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {})
        Text(text, style = common, color = Color(0xFF000000 or style.color.rgb).copy(alpha = style.opacity), maxLines = lines,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(tag))
    }
}
fun formatDecimal(value: Float, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; isGroupingUsed = false }.format(value)
@Composable fun decimalLabel(value: Float) = formatDecimal(value, LocalConfiguration.current.locales[0])
fun parseDecimal(value: String, locale: Locale): Float? {
    val trimmed = value.trim(); val position = ParsePosition(0)
    val parsed = NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = false }.parse(trimmed, position)?.toFloat()
    return parsed?.takeIf { it.isFinite() && position.index == trimmed.length }
}
