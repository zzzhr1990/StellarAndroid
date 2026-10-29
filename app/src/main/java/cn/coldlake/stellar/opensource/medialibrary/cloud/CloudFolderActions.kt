package cn.coldlake.stellar.opensource.medialibrary.cloud

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.library.*
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile

@Composable
internal fun CloudFolderActions(profile: ServerProfile, folder: String, state: LibraryState,
    controller: LibraryController, available: Boolean) {
    val context = LocalContext.current
    var expanded by remember(profile.id, folder) { mutableStateOf(false) }
    val member = state.paths.firstOrNull { it.type.cloud && it.sourceId == profile.id && (it.pathKey == folder || android.net.Uri.parse(it.path).getQueryParameter("browse") == folder) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = available && state.loaded && !state.busy,
            modifier = Modifier.testTag("library-folder-menu-current")) {
            Icon(painterResource(R.drawable.material_symbol_more_vert), stringResource(R.string.library_actions))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(if (member == null) R.string.library_add else R.string.library_remove)) },
                modifier = Modifier.testTag("library-folder-toggle"), onClick = {
                    expanded = false
                    if (member == null) controller.add { cloudLibraryPath(context.applicationContext, profile, folder) }
                    else controller.delete(member.id)
                })
        }
    }
}
