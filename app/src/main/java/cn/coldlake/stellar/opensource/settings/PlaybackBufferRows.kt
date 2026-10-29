package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackBufferRows(onNavigate: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { PlaybackBufferSettings(context) }
    var options by remember(settings) { mutableStateOf(settings.options) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> options = settings.options }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    for (minimum in listOf(true, false)) {
        val seconds = ((if (minimum) options.minMs else options.maxMs) / 1_000).toInt()
        ListItem(onClick = { onNavigate(if (minimum) "buffer-minimum" else "buffer-maximum") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
                .testTag(if (minimum) "playback-buffer-minimum" else "playback-buffer-maximum"),
            supportingContent = { Text(stringResource(if (minimum) R.string.playback_buffer_minimum_hint else R.string.playback_buffer_maximum_hint)) },
            trailingContent = { Text(stringResource(R.string.playback_buffer_seconds, seconds), color = MaterialTheme.colorScheme.primary) }) {
            Text(stringResource(if (minimum) R.string.playback_buffer_minimum else R.string.playback_buffer_maximum))
        }
    }
    ListItem(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
        .clip(MaterialTheme.shapes.large).testTag("playback-buffer-drop")
        .toggleable(options.drop, role = Role.Switch) { settings.options = options.copy(drop = it) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        supportingContent = { Text(stringResource(R.string.playback_buffer_drop_hint)) },
        trailingContent = { Switch(options.drop, onCheckedChange = null) }) {
        Text(stringResource(R.string.playback_buffer_drop))
    }
    Text(stringResource(R.string.playback_buffer_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
    TextButton(onClick = { settings.reset() }, modifier = Modifier.padding(horizontal = 16.dp)
        .testTag("playback-buffer-reset")) { Text(stringResource(R.string.playback_buffer_reset)) }

}
