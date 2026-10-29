package com.stellar.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.MediaDescriptor
import com.stellar.player.contract.MediaKind

/** Swipe state belongs to the LazyColumn's stable media key, never to a row position. */
@Composable
internal fun PortraitQueueEntry(
    media: MediaDescriptor,
    selected: Boolean,
    index: Int,
    count: Int,
    actions: PlayerPanelActions,
    modifier: Modifier = Modifier,
) {
    val dismiss = rememberSwipeToDismissBoxState()
    var menuExpanded by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.remove_media, media.title)
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    Box(modifier.fillMaxWidth()) {
        SwipeToDismissBox(
            state = dismiss,
            enableDismissFromStartToEnd = false,
            onDismiss = { direction ->
                if (direction == SwipeToDismissBoxValue.EndToStart) actions.removeMedia(media.id)
            },
            backgroundContent = {
                if (dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    Box(Modifier.fillMaxSize().clip(shapes.shape).background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                        Icon(painterResource(R.drawable.icon_delete_all), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(24.dp))
                    }
                }
            },
        ) {
            SegmentedListItem(
                selected = selected,
                onClick = { actions.selectMedia(media.id) },
                onLongClick = { menuExpanded = true },
                onLongClickLabel = stringResource(R.string.queue_item_actions),
                shapes = shapes,
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().testTag("queue-${media.id}").semantics {
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { actions.removeMedia(media.id); true })
                },
                leadingContent = {
                    Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (media.kind == MediaKind.AUDIO) R.drawable.hills_music_note else R.drawable.hills_playlist_play),
                            contentDescription = null, modifier = Modifier.size(24.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = if (selected) {{
                    Icon(painterResource(R.drawable.hills_check), contentDescription = null, modifier = Modifier.size(24.dp))
                }} else null,
                supportingContent = { Text(formatTime(media.durationMs)) },
            ) {
                Text(media.title, style = MaterialTheme.typography.bodyLargeEmphasized,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenuPopup(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1),
                modifier = Modifier.widthIn(min = 180.dp, max = 320.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_queue)) },
                    onClick = { menuExpanded = false; actions.removeMedia(media.id) },
                    modifier = Modifier.testTag("remove-${media.id}"))
            }
        }
    }
}
