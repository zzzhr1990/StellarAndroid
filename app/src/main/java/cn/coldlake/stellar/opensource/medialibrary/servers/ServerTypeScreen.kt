package cn.coldlake.stellar.opensource.medialibrary.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.settings.SettingsPageToolbar

private val storageKinds = listOf(ServerKind.WEBDAV, ServerKind.SMB, ServerKind.NFS,
    ServerKind.GOOGLE_DRIVE, ServerKind.ONEDRIVE)
private val mediaServerKinds = listOf(ServerKind.EMBY, ServerKind.JELLYFIN)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ServerTypeScreen(mediaServers: Boolean, controller: ServersController) {
    val kinds = if (mediaServers) mediaServerKinds else storageKinds
    val tag = if (mediaServers) "server-type" else "storage-type"
    Column(Modifier.fillMaxSize().testTag("$tag-picker")) {
        SettingsPageToolbar(if (mediaServers) R.string.server_add_media_server else R.string.server_add_remote,
            controller::back, "$tag-back")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(kinds, key = { _, kind -> kind.name }, contentType = { _, _ -> "server-type" }) { index, kind ->
                val name = stringResource(kind.label())
                SegmentedListItem(
                    onClick = { controller.edit(ServerProfile(kind = kind, name = if (kind.cloud) name else "")) },
                    shapes = ListItemDefaults.segmentedShapes(index, kinds.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    leadingContent = { ServerServiceIcon(kind) },
                    modifier = Modifier.fillMaxWidth().testTag("choose-${kind.name}"),
                ) { Text(name, style = MaterialTheme.typography.bodyLargeEmphasized) }
            }
        }
    }
}
