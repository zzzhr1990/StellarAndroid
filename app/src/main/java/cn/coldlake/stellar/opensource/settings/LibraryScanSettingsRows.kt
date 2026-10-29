package cn.coldlake.stellar.opensource.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R

@Composable
internal fun LibraryScanSettingsRows(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { LibraryScanSettings(context) }
    var enabled by remember { mutableStateOf(settings.quickHashEnabled) }
    var threads by remember { mutableIntStateOf(settings.scanThreads) }
    DisposableEffect(settings) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = settings.quickHashEnabled; threads = settings.scanThreads
        }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    ListItem(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
        .clip(MaterialTheme.shapes.large).testTag("library-quick-hash").toggleable(enabled, role = Role.Switch) { settings.quickHashEnabled = it; enabled = it },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        supportingContent = { Text(stringResource(R.string.library_quick_hash_hint)) },
        trailingContent = { Switch(enabled, null) }) { Text(stringResource(R.string.library_quick_hash)) }
    ListItem(onClick = { onNavigate("library-scan-threads") },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().testTag("library-scan-threads"),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        supportingContent = { Text(stringResource(R.string.library_scan_threads_hint)) },
        trailingContent = { Text(java.text.NumberFormat.getIntegerInstance().format(threads)) }) { Text(stringResource(R.string.library_scan_threads)) }
}
