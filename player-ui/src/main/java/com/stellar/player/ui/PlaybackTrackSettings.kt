package com.stellar.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.*
import kotlin.math.roundToInt

/** Popup state is local to this media session and disappears when its tab leaves composition. */
@Composable
internal fun TrackDropdown(
    snapshot: PlaybackSnapshot,
    kind: TrackKind,
    select: (TrackKind, String?) -> Unit,
    secondary: Boolean = false,
) {
    val tracks = when (kind) {
        TrackKind.VIDEO -> snapshot.media?.videoTracks.orEmpty()
        TrackKind.AUDIO -> snapshot.media?.audioTracks.orEmpty()
        TrackKind.SUBTITLE -> snapshot.media?.subtitleTracks.orEmpty()
    }
    val selected = when (kind) {
        TrackKind.VIDEO -> snapshot.selectedVideoId
        TrackKind.AUDIO -> snapshot.selectedAudioId
        TrackKind.SUBTITLE -> if (secondary) snapshot.selectedSecondarySubtitleId else snapshot.selectedSubtitleId
    }
    val capable = when (kind) {
        TrackKind.VIDEO -> snapshot.capabilities.canSelectVideo && snapshot.pendingSeek == null && snapshot.pendingFrame == null
        TrackKind.AUDIO -> snapshot.capabilities.canSelectAudio
        TrackKind.SUBTITLE -> if (secondary) snapshot.capabilities.canSelectSecondarySubtitle else snapshot.capabilities.canSelectSubtitle
    }
    val label = stringResource(when (kind) {
        TrackKind.VIDEO -> R.string.video_tracks
        TrackKind.AUDIO -> R.string.audio_tracks
        TrackKind.SUBTITLE -> if (secondary) R.string.second_subtitle else R.string.subtitles
    })
    val offLabel = if (snapshot.remotePlayback) R.string.remote_subtitles_off else R.string.subtitles_off
    val current = tracks.firstOrNull { it.id == selected }?.label ?: stringResource(
        if (kind == TrackKind.SUBTITLE) offLabel else R.string.no_available_tracks)
    PlaybackDropdown(snapshot.sessionId, label, current, if (secondary) "second-subtitle" else "track-dropdown-${kind.name}", capable && tracks.isNotEmpty()) { dismiss ->
        val options = if (kind == TrackKind.SUBTITLE) listOf(null) + tracks else tracks
        options.forEachIndexed { index, track ->
            SelectableDropdownMenuItem(selected = selected == track?.id,
                enabled = track == null || kind != TrackKind.SUBTITLE || track.id !=
                    (if (secondary) snapshot.selectedSubtitleId else snapshot.selectedSecondarySubtitleId),
                onClick = { dismiss(); select(kind, track?.id) },
                text = { Text(track?.label ?: stringResource(offLabel)) },
                shapes = MenuDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag(if (secondary) track?.let { "secondary-track-${it.id}" } ?: "secondary-subtitles-off"
                    else track?.let { "track-${it.id}" } ?: "subtitles-off"),
                selectedLeadingIcon = { Icon(painterResource(R.drawable.hills_check), null) })
        }
    }
}

@Composable
private fun PlaybackDropdown(sessionId: Long, label: String, current: String, tag: String, enabled: Boolean = true,
    content: @Composable (() -> Unit) -> Unit) {
    var expanded by remember(sessionId, tag) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.testTag(tag).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Text(current, Modifier.weight(1f),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.track_arrow_drop_down), null, Modifier.size(24.dp))
            }
            DropdownMenuPopup(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1),
                    modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).verticalScroll(rememberScrollState()),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    content { expanded = false }
                }
            }
        }
    }
}

@Composable
internal fun AudioSubtitleSettings(snapshot: PlaybackSnapshot, state: PlayerPanelState, actions: PlayerPanelActions) {
    TrackDropdown(snapshot, TrackKind.AUDIO, actions::selectTrack)
    if (snapshot.media?.kind == MediaKind.VIDEO) {
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        TrackDropdown(snapshot, TrackKind.SUBTITLE, actions::selectTrack)
        if (snapshot.remotePlayback) RemoteSubtitleNotice(snapshot)
        Spacer(Modifier.height(12.dp))
        ListItem(onClick = { actions.showPanel(CorePanel.SUBTITLE_DELAY) }, enabled = snapshot.capabilities.canDelaySubtitle,
            modifier = Modifier.fillMaxWidth().testTag("subtitle-delay"),
            content = { Text(stringResource(R.string.subtitle_delay)) },
            trailingContent = { SubtitleDelayControl(state.delayMs, actions, snapshot.capabilities.canDelaySubtitle) })
        listOf(
            Triple(R.string.load_local_subtitle, "subtitle-local", actions::loadLocalSubtitle),
            Triple(R.string.load_url_subtitle, "subtitle-url", actions::loadUrlSubtitle),
        ).forEach { (label, tag, action) ->
            ListItem(onClick = action, enabled = snapshot.capabilities.canImportSubtitle && !snapshot.simulated,
                modifier = Modifier.fillMaxWidth().testTag(tag), content = { Text(stringResource(label)) })
        }
        ListItem(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("subtitle-search"),
            content = { Text(stringResource(R.string.search_subtitles)) },
            supportingContent = { Text(stringResource(R.string.subtitle_not_implemented)) })
        Spacer(Modifier.height(12.dp))
        TrackDropdown(snapshot, TrackKind.SUBTITLE, { _, id -> actions.selectSecondarySubtitle(id) }, secondary = true)
    }
}

@Composable
internal fun PlaybackSettings(snapshot: PlaybackSnapshot, tools: PlayerToolsState, actions: PlayerPanelActions) {
    val chapters = snapshot.media?.chapters.orEmpty()
    if (chapters.isNotEmpty()) {
        var expanded by remember(snapshot.media?.id) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }, enabled = snapshot.canSeek,
            modifier = Modifier.testTag("dvd-chapters")) { Text(stringResource(R.string.dvd_chapters)) }
        if (expanded) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("dvd-chapter-list")) {
            items(chapters, key = { it.number }) { chapter ->
                TextButton(onClick = { actions.selectChapter(chapter.number); expanded = false },
                    enabled = snapshot.canSeek,
                    modifier = Modifier.fillMaxWidth().testTag("dvd-chapter-${chapter.number}")) {
                    Text(stringResource(R.string.dvd_chapter, chapter.number, formatTime(chapter.positionMs)))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if ((snapshot.media?.videoTracks?.size ?: 0) > 1) {
        TrackDropdown(snapshot, TrackKind.VIDEO, actions::selectTrack)
        Spacer(Modifier.height(24.dp))
    }
    val caps = snapshot.capabilities
    val canSetSpeed = caps.canSetSpeed && snapshot.media?.kind == MediaKind.VIDEO && (snapshot.durationMs ?: 0) > 0
    PlaybackDropdown(snapshot.sessionId, stringResource(R.string.speed), speedLabel(snapshot.speed),
        "speed-dropdown", canSetSpeed) { dismiss ->
        val speeds = listOf(0.5f, 1f, 1.2f, 1.5f, 2f, 3f, 5f, 6f, 8f)
        speeds.forEachIndexed { index, speed ->
            SelectableDropdownMenuItem(selected = snapshot.speed == speed,
                onClick = { dismiss(); actions.setSpeed(speed) }, text = { Text(speedLabel(speed)) },
                enabled = speed in maxOf(.5f, caps.minSpeed)..minOf(8f, caps.maxSpeed),
                shapes = MenuDefaults.itemShape(index, speeds.size + 1), modifier = Modifier.testTag("preset-$speed"),
                selectedLeadingIcon = { Icon(painterResource(R.drawable.hills_check), null) })
        }
        SelectableDropdownMenuItem(selected = false, onClick = { dismiss(); actions.showPanel(CorePanel.CUSTOM_SPEED) },
            text = { Text(stringResource(R.string.custom_speed)) },
            shapes = MenuDefaults.itemShape(speeds.size, speeds.size + 1), modifier = Modifier.testTag("open-custom-speed"))
    }
    if (!canSetSpeed) Text(stringResource(R.string.reason_speed), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("speed-unavailable"))
    if (snapshot.media?.kind == MediaKind.VIDEO) {
        Spacer(Modifier.height(24.dp))
        PlaybackDropdown(snapshot.sessionId, stringResource(R.string.screen_fit), stringResource(tools.fit.resource()), "fit-dropdown") { dismiss ->
            ScreenFit.entries.forEachIndexed { index, fit ->
                SelectableDropdownMenuItem(selected = tools.fit == fit,
                    onClick = { dismiss(); actions.tool(ToolEvent.Fit(fit)) }, text = { Text(stringResource(fit.resource())) },
                    shapes = MenuDefaults.itemShape(index, ScreenFit.entries.size), modifier = Modifier.testTag("fit-${fit.name}"),
                    selectedLeadingIcon = { Icon(painterResource(R.drawable.hills_check), null) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tools.flipHorizontal, onClick = { actions.tool(ToolEvent.Flip(true)) },
                label = { Text(stringResource(R.string.flip_horizontal)) }, modifier = Modifier.testTag("flip-horizontal"))
            FilterChip(selected = tools.flipVertical, onClick = { actions.tool(ToolEvent.Flip(false)) },
                label = { Text(stringResource(R.string.flip_vertical)) }, modifier = Modifier.testTag("flip-vertical"))
        }
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.seek_settings), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 8.dp))
    PanelSlider(stringResource(R.string.seek_forward_seconds), tools.seekSeconds.toFloat(), 1f..60f, "seek-button-setting", {
        actions.tool(ToolEvent.SeekButton(it.roundToInt()))
    })
    PanelSlider(stringResource(R.string.seek_rewind_seconds), tools.rewindSeconds.toFloat(), 1f..60f, "rewind-button-setting", {
        actions.tool(ToolEvent.RewindButton(it.roundToInt()))
    })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !tools.dragByTime, onClick = { actions.tool(ToolEvent.DragMode(false)) },
            label = { Text(stringResource(R.string.seek_percent)) }, modifier = Modifier.testTag("drag-percent"))
        FilterChip(selected = tools.dragByTime, onClick = { actions.tool(ToolEvent.DragMode(true)) },
            label = { Text(stringResource(R.string.seek_time)) }, modifier = Modifier.testTag("drag-time"))
    }
    PanelSlider(stringResource(if (tools.dragByTime) R.string.seek_drag_seconds else R.string.seek_drag_percent),
        (if (tools.dragByTime) tools.dragSeconds else tools.dragPercent).toFloat(),
        if (tools.dragByTime) 1f..60f else 1f..200f, "seek-drag-setting", { actions.tool(ToolEvent.DragAmount(it.roundToInt())) })
}
