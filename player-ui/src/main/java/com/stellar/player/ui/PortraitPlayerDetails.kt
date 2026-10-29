package com.stellar.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.MediaKind
import com.stellar.player.contract.PlaybackSnapshot

/** Page-local selection and list anchors; playback and modal tools keep their existing owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortraitPlayerDetails(
    snapshot: PlaybackSnapshot,
    state: PlayerPanelState,
    actions: PlayerPanelActions,
    modifier: Modifier = Modifier,
    moreContent: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val tabs = PlaybackTabs
    var savedTab by rememberSaveable { mutableStateOf(CorePanel.PLAYLIST.name) }
    val selected = when (savedTab) {
        CorePanel.SUBTITLES.name, CorePanel.AUDIO.name -> CorePanel.AUDIO_SUBTITLES
        CorePanel.SPEED.name -> CorePanel.PLAYBACK
        else -> tabs.firstOrNull { it.name == savedTab } ?: CorePanel.PLAYLIST
    }
    val contentState = rememberSaveableStateHolder()
    Column(modifier.testTag("details")) {
        Text(snapshot.media?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(selected), edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEach { tab ->
                Tab(selected = tab == selected,
                    onClick = {
                        if (tab != selected) { actions.dismissPanel(); savedTab = tab.name }
                    },
                    modifier = Modifier.testTag("portrait-${tab.name}"),
                    text = { Text(stringResource(tab.titleResource()), maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis) })
            }
        }
        PageMotionContent(selected, Modifier.weight(1f).fillMaxWidth(),
            motion = { from, to -> if (tabs.indexOf(to) > tabs.indexOf(from)) PageMotion.LateralForward else PageMotion.LateralBackward }) { selected ->
        contentState.SaveableStateProvider(selected.name) {
            LazyColumn(Modifier.fillMaxSize().testTag("portrait-content-${selected.name}"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                if (selected == CorePanel.PLAYLIST) {
                    item(key = "queue-header", contentType = "header") {
                        val current = state.queue.indexOfFirst { it.id == snapshot.media?.id }
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(pluralStringResource(R.plurals.queue_count, state.queue.size, state.queue.size),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, modifier = Modifier.testTag("queue-count"))
                            TextButton(onClick = { actions.adjacentMedia(-1) }, modifier = Modifier.testTag("queue-previous"),
                                enabled = state.queue.size > 1 && !state.queuePreparing && (!state.episodeQueue || current > 0)) {
                                Text(stringResource(R.string.previous_episode), maxLines = 1)
                            }
                            TextButton(onClick = { actions.adjacentMedia(1) }, modifier = Modifier.testTag("queue-next"),
                                enabled = state.queue.size > 1 && !state.queuePreparing && (!state.episodeQueue || current in 0 until state.queue.lastIndex)) {
                                Text(stringResource(R.string.next_episode), maxLines = 1)
                            }
                        }
                        if (state.queue.isEmpty()) Text(stringResource(R.string.queue_empty),
                            modifier = Modifier.padding(vertical = 24.dp).testTag("queue-empty"))
                    }
                    itemsIndexed(state.queue, key = { _, media -> "media:${media.id}" }, contentType = { _, _ -> "media" }) { index, media ->
                        PortraitQueueEntry(media, snapshot.media?.id == media.id, index, state.queue.size, actions,
                            Modifier.animateItem().padding(bottom = ListItemDefaults.SegmentedGap))
                    }
                    if (state.queue.isNotEmpty()) item(key = "queue-footer", contentType = "actions") { QueueFooter(actions) }
                } else {
                    item(key = selected.name, contentType = "options") {
                        Column {
                            when (selected) {
                                CorePanel.AUDIO_SUBTITLES -> AudioSubtitleSettings(snapshot, state, actions)
                                CorePanel.PLAYBACK -> PlaybackSettings(snapshot, state.tools, actions)
                                CorePanel.MORE -> moreContent()
                                else -> Unit
                            }
                        }
                    }
                }
                item(key = "footer", contentType = "footer") {
                    Column {
                        state.message?.let { message ->
                            Text(stringResource(when (message) {
                                PanelMessage.SPEED_UNAVAILABLE -> R.string.reason_speed
                                PanelMessage.INVALID_SPEED -> R.string.invalid_speed
                                PanelMessage.TRACK_UNAVAILABLE -> R.string.reason_track
                                PanelMessage.EPISODES_UNAVAILABLE -> R.string.episodes_unavailable
                                PanelMessage.EPISODE_UNAVAILABLE -> R.string.episode_unavailable
                            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("panel-message"))
                        }
                        if (state.tools.background) Text(stringResource(R.string.background_hint), color = PlayerColors.Secondary)
                        if (state.tools.castDevice != null) Text(stringResource(R.string.cast_connected), color = PlayerColors.Accent)
                        footer()
                    }
                }
            }
        }
        }
    }
}

private val PlaybackTabs = listOf(CorePanel.PLAYLIST, CorePanel.AUDIO_SUBTITLES, CorePanel.PLAYBACK, CorePanel.MORE)
