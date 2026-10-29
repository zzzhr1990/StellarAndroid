package cn.coldlake.stellar.opensource.medialibrary.library

import com.stellar.player.ui.LocalPageActive
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import kotlin.math.roundToInt

internal fun LibraryPathType.label() = when (this) {
    LibraryPathType.LOCAL -> R.string.library_type_local
    LibraryPathType.WEBDAV -> R.string.server_webdav
    LibraryPathType.SMB -> R.string.server_smb
    LibraryPathType.NFS -> R.string.server_nfs
    LibraryPathType.GOOGLE_DRIVE -> R.string.server_google_drive
    LibraryPathType.ONEDRIVE -> R.string.server_onedrive
}

@Composable
private fun LibraryAvatar() {
    Surface(Modifier.size(40.dp), shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.material_symbol_folder), null, Modifier.size(24.dp)) }
    }
}

/** Physical right swipe only reveals an explicit action. A gesture never deletes a row. */
@Composable
internal fun RevealDeleteRow(id: String, enabled: Boolean, onDelete: () -> Unit,
    shape: Shape = RoundedCornerShape(16.dp), content: @Composable () -> Unit) {
    val width = with(LocalDensity.current) { 72.dp.toPx() }
    var offset by remember(id, width) { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().clip(shape).testTag("library-swipe-$id")) {
        if (offset > 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer)) {
            IconButton(onClick = onDelete, enabled = enabled && offset >= width / 2,
                modifier = Modifier.align(AbsoluteAlignment.CenterLeft).width(72.dp)
                    .testTag("library-swipe-delete-$id")) {
                Icon(painterResource(R.drawable.material_symbol_delete), stringResource(R.string.server_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Box(Modifier.absoluteOffset { IntOffset(offset.roundToInt(), 0) }
            .draggable(rememberDraggableState { delta -> offset = (offset + delta).coerceIn(0f, width) },
                Orientation.Horizontal, enabled = enabled,
                onDragStopped = { velocity ->
                    offset = when { velocity > 600 -> width; velocity < -600 -> 0f
                        offset >= width / 2 -> width; else -> 0f }
                })) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryPathsScreen(state: LibraryState, controller: LibraryController) {
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale) }
    val runtime = remember { cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime.get(context) }
    val change by runtime.changes.collectAsState()
    var history by remember { mutableStateOf<Map<String, cn.coldlake.stellar.opensource.medialibrary.catalog.RootScanHistory>>(emptyMap()) }
    LaunchedEffect(change) { history = runtime.history() }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("library-paths")) {
        TopAppBar(title = { Text(stringResource(R.string.library_builtin)) }, navigationIcon = {
            IconButton(onClick = { controller.open(false) }, modifier = Modifier.testTag("library-back")) {
                Icon(painterResource(R.drawable.material_symbol_arrow_back), stringResource(R.string.server_back))
            }
        })
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxSize())
        }
        cn.coldlake.stellar.opensource.medialibrary.catalog.ScanControls(runtime, Modifier.padding(horizontal = 20.dp))
        Text(stringResource(R.string.scan_supported_sources), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        if (state.loadError && state.paths.isEmpty()) Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.library_error))
            TextButton(onClick = controller::reload) { Text(stringResource(R.string.server_retry)) }
        } else if (state.loaded && state.paths.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_empty), Modifier.padding(32.dp).testTag("library-empty"))
        } else LazyColumn(Modifier.fillMaxSize().testTag("library-path-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.paths, key = { it.id }, contentType = { "library-path" }) { entry ->
                RevealDeleteRow(entry.id, !state.busy, { controller.delete(entry.id) }) {
                    var expanded by remember { mutableStateOf(false) }
                    ListItem(modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { detailId = entry.id }
                        .testTag("library-path-${entry.id}").padding(vertical = 4.dp),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        headlineContent = { Text(entry.displayPath, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)) },
                        supportingContent = { Column {
                            Text(stringResource(entry.type.label()), color = MaterialTheme.colorScheme.primary)
                            history[entry.id]?.let { item ->
                                item.attempted?.let { Text(stringResource(R.string.scan_last_attempt, dateFormat.format(java.util.Date(it))), style = MaterialTheme.typography.bodySmall) }
                                item.completed?.let { Text(stringResource(R.string.scan_last_complete, dateFormat.format(java.util.Date(it))), style = MaterialTheme.typography.bodySmall) }
                            }
                        } },
                        leadingContent = { LibraryAvatar() }, trailingContent = { Box {
                            IconButton(onClick = { expanded = true }, enabled = !state.busy,
                                modifier = Modifier.testTag("library-path-menu-${entry.id}")) {
                                Icon(painterResource(R.drawable.material_symbol_more_vert), stringResource(R.string.library_actions))
                            }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.server_delete)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.material_symbol_delete), null) },
                                    modifier = Modifier.testTag("library-path-delete"),
                                    onClick = { expanded = false; controller.delete(entry.id) })
                            }
                        } })
                }
            }
        }
    }
    if (state.loadError && state.paths.isNotEmpty()) Snackbar(
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        action = { TextButton(onClick = controller::reload) { Text(stringResource(R.string.server_retry)) } }
    ) { Text(stringResource(R.string.library_error)) }
    }
    state.paths.firstOrNull { it.id == detailId }?.let { entry -> AlertDialog(onDismissRequest = { detailId = null },
        title = { Text(stringResource(entry.type.label())) },
        text = { Text(entry.displayPath, modifier = Modifier.verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)) },
        confirmButton = { TextButton(onClick = { detailId = null }) { Text(stringResource(R.string.library_ok)) } }) }
}

@Composable
internal fun FolderLibraryActions(candidate: LibraryPath, state: LibraryState, controller: LibraryController, tag: String,
    available: Boolean = true) {
    var expanded by remember(candidate.sourceId, candidate.pathKey) { mutableStateOf(false) }
    val member = state.paths.firstOrNull { it.sameLocation(candidate) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (member != null) Icon(painterResource(R.drawable.material_symbol_star), stringResource(R.string.library_member),
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).testTag("library-star-$tag"))
        Box {
            IconButton(onClick = { expanded = true }, enabled = available && state.loaded && !state.busy,
                modifier = Modifier.testTag("library-folder-menu-$tag")) {
                Icon(painterResource(R.drawable.material_symbol_more_vert), stringResource(R.string.library_actions))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(if (state.loadError) R.string.server_retry
                    else if (member == null) R.string.library_add else R.string.library_remove)) },
                    modifier = Modifier.testTag("library-folder-toggle"), onClick = {
                        expanded = false
                        if (state.loadError) controller.reload()
                        else if (member == null) controller.add(candidate) else controller.delete(member.id)
                    })
            }
        }
    }
}

@Composable
internal fun LibraryFeedback(state: LibraryState, controller: LibraryController) {
    val notice = state.notice ?: return
    if (notice == LibraryNotice.ADDED || notice == LibraryNotice.DELETED) {
        val snackbar = remember { SnackbarHostState() }
        val text = stringResource(if (notice == LibraryNotice.ADDED) R.string.library_added else R.string.library_removed)
        LaunchedEffect(notice) { snackbar.showSnackbar(text); controller.dismissNotice() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.padding(16.dp)) }
    } else AlertDialog(onDismissRequest = controller::dismissNotice, modifier = Modifier.testTag("library-notice"),
        title = { Text(stringResource(if (notice == LibraryNotice.DUPLICATE || notice == LibraryNotice.PARENT)
            R.string.library_already_title else R.string.library_failed_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(when (notice) {
                LibraryNotice.DUPLICATE -> R.string.library_duplicate
                LibraryNotice.PARENT -> R.string.library_parent
                LibraryNotice.PERMISSION -> R.string.library_permission
                else -> R.string.library_error
            }))
            if (state.coveredPath.isNotEmpty()) Text(state.coveredPath, Modifier.padding(top = 12.dp))
        } }, confirmButton = { TextButton(onClick = controller::dismissNotice, modifier = Modifier.testTag("library-notice-ok")) {
            Text(stringResource(R.string.library_ok))
        } })
}
