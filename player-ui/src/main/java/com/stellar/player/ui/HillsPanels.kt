package com.stellar.player.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.contract.*

/** Uses the existing modal window and single active panel; there is no second popup owner. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxWithConstraintsScope.HillsPanelLayout(snapshot: PlaybackSnapshot, state: PlayerPanelState,
    actions: PlayerPanelActions, samples: List<TrackDescriptor>, rightHanded: Boolean) {
    val active = state.active ?: return
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
    val edge = with(density) { maxOf(insets.getLeft(this, direction), insets.getRight(this, direction)).toDp().coerceAtLeast(48.dp) }
    val dismiss = stringResource(R.string.dismiss_panel)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
        .testTag("panel-scrim").semantics { contentDescription = dismiss }.clickable(onClick = actions::dismissPanel))
    val anchored = active == CorePanel.MORE || active == CorePanel.SPEED
    val speed = active == CorePanel.SPEED
    val width = if (speed) 104.dp else if (anchored) 208.dp else minOf(360.dp, maxWidth * .72f)
    val align = if (speed) {
        if (rightHanded) androidx.compose.ui.AbsoluteAlignment.CenterRight else androidx.compose.ui.AbsoluteAlignment.CenterLeft
    } else if (rightHanded) androidx.compose.ui.AbsoluteAlignment.TopRight else androidx.compose.ui.AbsoluteAlignment.TopLeft
    val margin = if (speed) edge + 64.dp else if (anchored) edge else maxOf(16.dp, edge - 32.dp)
    CompositionLocalProvider(LocalContentColor provides HillsInk) {
    Column(Modifier.align(align).absolutePadding(left = if (rightHanded) 0.dp else margin, right = if (rightHanded) margin else 0.dp,
        top = if (speed) 8.dp else if (anchored) 64.dp else 16.dp, bottom = 12.dp)
        .width(minOf(width, maxWidth - margin - 16.dp)).heightIn(max = maxHeight - if (anchored && !speed) 76.dp else 28.dp)
        .clip(RoundedCornerShape(if (anchored) 9.dp else 12.dp))
        .background(if (anchored) HillsPanel else Color.Transparent).pointerInput(Unit) { detectTapGestures {} }.testTag("core-panel")) {
        if (!anchored) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(active.titleResource()), fontSize = 28.sp, color = HillsInk, modifier = Modifier.weight(1f).padding(start = 8.dp))
            HillsIcon(R.drawable.hills_close, stringResource(R.string.panel_back), "panel-back", actions::panelBack)
        }
        key(active) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).then(
                if (anchored) Modifier else Modifier.clip(RoundedCornerShape(12.dp)).background(HillsPanel).padding(12.dp))) {
                state.message?.let { message -> Text(stringResource(when(message) {
                    PanelMessage.SPEED_UNAVAILABLE -> R.string.reason_speed
                    PanelMessage.INVALID_SPEED -> R.string.invalid_speed
                    PanelMessage.TRACK_UNAVAILABLE -> R.string.reason_track
                    PanelMessage.EPISODES_UNAVAILABLE -> R.string.episodes_unavailable
                    PanelMessage.EPISODE_UNAVAILABLE -> R.string.episode_unavailable
                }), color = HillsInk, fontSize = 12.sp, modifier = Modifier.padding(12.dp).testTag("panel-message")) }
                when (active) {
                    CorePanel.MORE -> HillsMore(actions)
                    CorePanel.SPEED -> {
                        val caps = snapshot.capabilities
                        listOf(8f, 6f, 5f, 3f, 2f, 1.5f, 1f, .5f).filter { it in caps.minSpeed..caps.maxSpeed }.forEach { value ->
                            HillsChoice(hillsSpeedLabel(value), "preset-$value", snapshot.speed == value,
                                { actions.setSpeed(value); actions.dismissPanel() },
                                enabled = caps.canSetSpeed, compact = true)
                        }
                        HillsChoice(stringResource(R.string.custom_speed), "open-custom-speed", false, { actions.showPanel(CorePanel.CUSTOM_SPEED) }, compact = true)
                    }
                    CorePanel.FIT -> {
                        ScreenFit.entries.forEach { fit -> HillsChoice(stringResource(fit.resource()), "fit-${fit.name}", state.tools.fit == fit,
                            { actions.tool(ToolEvent.Fit(fit)); actions.dismissPanel() }) }
                        HillsChoice(stringResource(R.string.flip_horizontal), "flip-horizontal", state.tools.flipHorizontal, { actions.tool(ToolEvent.Flip(true)) })
                        HillsChoice(stringResource(R.string.flip_vertical), "flip-vertical", state.tools.flipVertical, { actions.tool(ToolEvent.Flip(false)) })
                    }
                    CorePanel.CONTROLS -> HillsControlSettings(state.tools, actions)
                    CorePanel.SUBTITLES -> HillsTracks(snapshot, TrackKind.SUBTITLE, actions, state.delayMs)
                    CorePanel.AUDIO -> HillsTracks(snapshot, TrackKind.AUDIO, actions)
                    CorePanel.VIDEO -> HillsTracks(snapshot, TrackKind.VIDEO, actions)
                    CorePanel.PLAYLIST -> QueuePanel(snapshot, state, actions)
                    CorePanel.CUSTOM_SPEED -> SpeedPanel(snapshot, true, actions)
                    CorePanel.SUBTITLE_STYLE -> if (snapshot.simulated) StylePanel(state.style, actions) else UnsupportedPanel()
                    CorePanel.SUBTITLE_DELAY -> if (snapshot.capabilities.canDelaySubtitle) DelayPanel(state, actions) else UnsupportedPanel()
                    CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH -> if (snapshot.simulated) SamplePanel(snapshot, samples, active == CorePanel.SUBTITLE_SEARCH, actions) else UnsupportedPanel()
                    CorePanel.TOOLS -> ToolPanel(snapshot, state.copy(active = CorePanel.MORE), actions)
                    else -> ToolPanel(snapshot, state, actions)
                }
            }
        }
    }
    }
}

@Composable private fun UnsupportedPanel() { Text(stringResource(R.string.real_unavailable), color = HillsInk, modifier = Modifier.testTag("real-unavailable")) }

@Composable private fun HillsMore(a: PlayerPanelActions) {
    HillsMenuRow(R.drawable.hills_screen_rotation, R.string.hills_rotate, "rotate") { a.tool(ToolEvent.Rotate) }
    HillsMenuRow(R.drawable.hills_timer, R.string.timer, "tool-TIMER") { a.showPanel(CorePanel.TIMER) }
    HillsMenuRow(R.drawable.hills_info, R.string.media_info, "tool-INFO") { a.showPanel(CorePanel.INFO) }
    HillsMenuRow(R.drawable.hills_playlist_play, R.string.playlist, "menu-playlist") { a.showPanel(CorePanel.PLAYLIST) }
    HillsMenuRow(R.drawable.hills_settings, R.string.hills_controls, "control-settings") { a.showPanel(CorePanel.CONTROLS) }
}

@Composable private fun HillsMenuRow(icon: Int, text: Int, tag: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).clickable(onClick = action)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = HillsInk)
        Text(stringResource(text), color = HillsInk, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun HillsTracks(s: PlaybackSnapshot, kind: TrackKind, a: PlayerPanelActions, delayMs: Int = 0) {
    val subtitles = kind == TrackKind.SUBTITLE
    val tracks = when (kind) {
        TrackKind.VIDEO -> s.media?.videoTracks.orEmpty()
        TrackKind.AUDIO -> s.media?.audioTracks.orEmpty()
        TrackKind.SUBTITLE -> s.media?.subtitleTracks.orEmpty()
    }
    val selected = when (kind) {
        TrackKind.VIDEO -> s.selectedVideoId
        TrackKind.AUDIO -> s.selectedAudioId
        TrackKind.SUBTITLE -> s.selectedSubtitleId
    }
    val capable = when (kind) {
        TrackKind.VIDEO -> s.capabilities.canSelectVideo && s.pendingSeek == null && s.pendingFrame == null
        TrackKind.AUDIO -> s.capabilities.canSelectAudio
        TrackKind.SUBTITLE -> s.capabilities.canSelectSubtitle
    }
    val icon = when (kind) {
        TrackKind.VIDEO -> R.drawable.hills_video_library
        TrackKind.AUDIO -> R.drawable.hills_headphones
        TrackKind.SUBTITLE -> R.drawable.hills_closed_caption
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = HillsInk)
        Text(stringResource(R.string.hills_tracks), color = HillsInk, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (subtitles && s.capabilities.canDelaySubtitle) {
            Text(stringResource(R.string.subtitle_delay_short), color = HillsInk, fontSize = 12.sp)
            SubtitleDelayControl(delayMs, a, true)
        }
    }
    if (subtitles && s.remotePlayback) RemoteSubtitleNotice(s)
    if (subtitles) HillsChoice(stringResource(if (s.remotePlayback) R.string.remote_subtitles_off else R.string.subtitles_off), "subtitles-off", selected == null,
        { a.selectTrack(TrackKind.SUBTITLE, null) }, enabled = capable)
    tracks.forEach { track -> HillsChoice(track.label, "track-${track.id}", selected == track.id, { a.selectTrack(track.kind, track.id) },
        capable && (!subtitles || track.id != s.selectedSecondarySubtitleId)) }
    if (tracks.isEmpty()) Text(stringResource(when (kind) {
        TrackKind.VIDEO -> R.string.no_available_tracks
        TrackKind.AUDIO -> R.string.audio_empty
        TrackKind.SUBTITLE -> R.string.subtitles_empty
    }), color = HillsInk.copy(alpha = .65f),
        fontSize = 14.sp, modifier = Modifier.padding(16.dp).testTag("tracks-empty"))
    if (subtitles && s.capabilities.canSelectSecondarySubtitle) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = HillsInk.copy(alpha = .15f))
        Text(stringResource(R.string.second_subtitle), color = HillsInk, fontSize = 18.sp, modifier = Modifier.padding(12.dp))
        HillsChoice(stringResource(R.string.subtitles_off), "secondary-subtitles-off", s.selectedSecondarySubtitleId == null,
            { a.selectSecondarySubtitle(null) })
        tracks.forEach { track -> HillsChoice(track.label, "secondary-track-${track.id}", s.selectedSecondarySubtitleId == track.id,
            { a.selectSecondarySubtitle(track.id) }, enabled = track.id != s.selectedSubtitleId) }
    }
    if (subtitles && (s.capabilities.canImportSubtitle || s.capabilities.canStyleSubtitle || s.capabilities.canDelaySubtitle)) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = HillsInk.copy(alpha = .15f))
        Text(stringResource(R.string.hills_subtitle_settings), color = HillsInk, fontSize = 18.sp, modifier = Modifier.padding(12.dp))
        if (!s.simulated && s.capabilities.canImportSubtitle) {
            HillsMenuRow(R.drawable.hills_add, R.string.load_local_subtitle, "subtitle-local", a::loadLocalSubtitle)
            HillsMenuRow(R.drawable.hills_closed_caption, R.string.load_url_subtitle, "subtitle-url", a::loadUrlSubtitle)
        } else if (s.capabilities.canImportSubtitle) {
            HillsMenuRow(R.drawable.hills_add, R.string.import_sample, "open-SUBTITLE_IMPORT") { a.showPanel(CorePanel.SUBTITLE_IMPORT) }
            HillsMenuRow(R.drawable.hills_closed_caption, R.string.search_sample, "open-SUBTITLE_SEARCH") { a.showPanel(CorePanel.SUBTITLE_SEARCH) }
        }
        if (s.capabilities.canStyleSubtitle) HillsMenuRow(R.drawable.hills_settings, R.string.subtitle_style, "open-SUBTITLE_STYLE") { a.showPanel(CorePanel.SUBTITLE_STYLE) }
        if (s.simulated && s.capabilities.canDelaySubtitle) HillsMenuRow(R.drawable.hills_timer, R.string.subtitle_delay, "open-SUBTITLE_DELAY") { a.showPanel(CorePanel.SUBTITLE_DELAY) }
    }
}

@Composable internal fun HillsChoice(label: String, tag: String, selected: Boolean, onClick: () -> Unit,
    enabled: Boolean = true, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics { this.selected = selected; role = Role.RadioButton }
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start) {
        if (!compact) RadioButton(selected, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFD0BCFF), unselectedColor = HillsInk))
        Text(label, color = (if (selected) Color(0xFFD0BCFF) else HillsInk).copy(alpha = if (enabled) 1f else .4f), fontSize = 14.sp,
            modifier = if (compact) Modifier else Modifier.padding(start = 12.dp))
    }
}

@Composable internal fun HillsControlSettings(t: PlayerToolsState, a: PlayerPanelActions) {
    HillsToggle(R.string.hills_double_play, "double-tap-play", t.doubleTapPlay) { a.tool(ToolEvent.DoubleTapPlay(it)) }
    HillsToggle(R.string.hills_double_seek, "double-tap-seek", t.doubleTapSeek) { a.tool(ToolEvent.DoubleTapSeek(it)) }
    Text(stringResource(R.string.hills_double_hint), color = HillsInk.copy(alpha = .65f), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    HillsToggle(R.string.hills_hide_paused, "hide-paused", t.hideWhenPaused) { a.tool(ToolEvent.HideWhenPaused(it)) }
    PanelSlider(stringResource(R.string.seek_forward_seconds), t.seekSeconds.toFloat(), 1f..60f, "seek-button-setting", { a.tool(ToolEvent.SeekButton(it.toInt())) })
    PanelSlider(stringResource(R.string.seek_rewind_seconds), t.rewindSeconds.toFloat(), 1f..60f, "rewind-button-setting", { a.tool(ToolEvent.RewindButton(it.toInt())) })
    HillsMenuRow(R.drawable.hills_tune, R.string.hills_other_tools, "other-tools") { a.showPanel(CorePanel.TOOLS) }
}

@Composable private fun HillsToggle(label: Int, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val text = stringResource(label)
        Text(text, color = HillsInk, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onChange, Modifier.testTag(tag).semantics { contentDescription = text })
    }
}
