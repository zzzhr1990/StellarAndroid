package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.platform.ScreenshotDestination
import cn.coldlake.stellar.opensource.platform.ScreenshotPreferences

@Composable
internal fun ScreenshotSettings(onChooseFolder: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { ScreenshotPreferences(context) }
    var destination by remember { mutableStateOf(store.destination) }
    var folder by remember { mutableStateOf(store.folderLabel) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            destination = store.destination; folder = store.folderLabel
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp).selectableGroup()) {
        ScreenshotDestination.entries.forEachIndexed { index, option ->
            SegmentedListItem(onClick = { store.destination = option },
                shapes = ListItemDefaults.segmentedShapes(index, 2),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)
                    .testTag("screenshot-destination-${option.name}").semantics { role = Role.RadioButton; selected = destination == option },
                leadingContent = { RadioButton(selected = destination == option, onClick = null) }) {
                Text(stringResource(if (option == ScreenshotDestination.ALBUM) R.string.screenshot_album else R.string.screenshot_folder))
            }
        }
        Spacer(Modifier.height(16.dp))
        SegmentedListItem(onClick = onChooseFolder, enabled = destination == ScreenshotDestination.FOLDER,
            shapes = ListItemDefaults.segmentedShapes(0, 1),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("screenshot-choose-path"),
            supportingContent = { Text(folder ?: stringResource(R.string.screenshot_path_unselected)) }) {
            Text(stringResource(R.string.screenshot_choose_path))
        }
    }
}
