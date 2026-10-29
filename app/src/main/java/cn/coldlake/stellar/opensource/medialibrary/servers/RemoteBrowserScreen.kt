package cn.coldlake.stellar.opensource.medialibrary.servers

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import androidx.compose.runtime.saveable.rememberSaveable
import cn.coldlake.stellar.opensource.medialibrary.ScrollPosition
import cn.coldlake.stellar.opensource.medialibrary.library.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteBrowserScreen(state: BrowserState, controller: RemoteBrowserController,
    onPlay: (ServerProfile, RemoteEntry) -> Unit, libraryState: LibraryState, library: LibraryController,
    onReconnect: () -> Unit = {}) {
    val profile = state.profile ?: return
    val folder = state.folder ?: return
    Column(Modifier.fillMaxSize().testTag("remote-browser")) {
        TopAppBar(title = { Column {
            Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.path.isNotEmpty()) Text(profile.name, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }, navigationIcon = {
            IconButton(onClick = controller::back, modifier = Modifier.testTag("browser-back")) {
                Icon(painterResource(R.drawable.material_symbol_arrow_back), stringResource(R.string.browser_back))
            }
        }, actions = {
            if (profile.kind.remote && folder.entries.any { it.name.equals("VIDEO_TS.IFO", true) }) {
                TextButton(onClick = { onPlay(profile, folder.entries.first { it.name.equals("VIDEO_TS.IFO", true) }) },
                    enabled = !state.loading, modifier = Modifier.testTag("dvd-play-folder")) { Text(stringResource(R.string.dvd_play)) }
            }
            if (profile.kind.remote && folder.entries.any { it.name.equals("index.bdmv", true) || it.name.equals("BDMV", true) }) {
                TextButton(onClick = { onPlay(profile, folder.entries.first { it.name.equals("index.bdmv", true) || it.name.equals("BDMV", true) }) },
                    enabled = !state.loading, modifier = Modifier.testTag("bluray-play-folder")) { Text(stringResource(R.string.bluray_play)) }
            }
            TextButton(onClick = { controller.refresh() }, enabled = !state.loading,
                modifier = Modifier.testTag("browser-refresh")) { Text(stringResource(R.string.browser_refresh)) }
            if (profile.kind.cloud) cn.coldlake.stellar.opensource.medialibrary.cloud.CloudFolderActions(profile, folder.location, libraryState, library,
                folder.loaded && !state.loading && state.error == null)
            else FolderLibraryActions(remoteLibraryPath(profile, folder.location), libraryState, library, "current",
                available = folder.loaded && !state.loading && state.error == null)
        })
        Text(if (state.path.isEmpty()) stringResource(R.string.browser_root)
            else state.path.joinToString(" / "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("browser-path"))
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxSize().testTag("browser-loading"))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (folder.loaded && folder.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.browser_empty), Modifier.padding(24.dp).testTag("browser-empty"))
            }
        } else if (folder.loaded) {
            RemoteDirectoryList(folder.location, folder.entries, folder.scroll, profile, controller, onPlay, libraryState, library)
        }
        state.error?.let { error ->
            val reconnect = profile.kind.cloud && error in setOf(ServerError.DRIVE_AUTH, ServerError.ONEDRIVE_AUTH)
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("browser-error"),
                action = { TextButton(onClick = if (reconnect) onReconnect else controller::refresh) {
                    Text(stringResource(if (reconnect) (if (profile.kind == ServerKind.ONEDRIVE) R.string.onedrive_connect else R.string.drive_connect) else R.string.server_retry))
                } }) { Text(stringResource(error.message())) }
        }
        }
    }
    if (state.unsupported) AlertDialog(onDismissRequest = { controller.unsupported(false) }, modifier = Modifier.testTag("browser-unsupported"),
        text = { Text(stringResource(R.string.browser_unsupported)) },
        confirmButton = { TextButton(onClick = { controller.unsupported(false) }) { Text(stringResource(R.string.media_library_cancel)) } })
}

/** Loading/errors are consumed by the surrounding chrome, not by every directory row. */
@Composable private fun RemoteDirectoryList(location: String, entries: List<RemoteEntry>, scroll: ScrollPosition, profile: ServerProfile,
    controller: RemoteBrowserController, onPlay: (ServerProfile, RemoteEntry) -> Unit,
    libraryState: LibraryState, library: LibraryController) {
    val context = LocalContext.current
    val list = rememberSaveable(location, saver = LazyListState.Saver) {
        scroll.current.let { (index, offset) -> LazyListState(index, offset) }
    }
    DisposableEffect(scroll, list) {
        val detach = scroll.attach { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        onDispose { detach() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("browser-list"), state = list,
        contentPadding = PaddingValues(bottom = 24.dp)) {
        items(entries, key = { it.location }, contentType = { it.kind }) { entry ->
            val folderEntry = entry.kind == RemoteFileKind.FOLDER
            val media = entry.kind == RemoteFileKind.VIDEO || entry.kind == RemoteFileKind.AUDIO
            ListItem(headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(when {
                    folderEntry -> stringResource(R.string.browser_folder)
                    entry.size != null -> Formatter.formatShortFileSize(context, entry.size)
                    else -> stringResource(R.string.browser_file)
                }) },
                leadingContent = { Icon(painterResource(if (folderEntry) R.drawable.material_symbol_folder
                    else if (media) R.drawable.material_symbol_play_arrow else R.drawable.material_symbol_insert_drive_file), null) },
                trailingContent = if (folderEntry && !profile.kind.cloud) { {
                    FolderLibraryActions(remoteLibraryPath(profile, entry.location), libraryState, library, entry.name,
                        available = true)
                } } else null,
                modifier = Modifier.clickable {
                    when { folderEntry -> controller.enter(entry); media -> onPlay(profile, entry); else -> controller.unsupported(true) }
                }.testTag("remote-entry-${entry.name}"))
        }
    }

}
