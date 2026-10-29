package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R

@Composable
internal fun PlaybackOutputRow() {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { PlaybackOutputSettings(context) }
    var enabled by remember(settings) { mutableStateOf(settings.forceSdr) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enabled = settings.forceSdr }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
        .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .testTag("setting-force_sdr")
        .toggleable(enabled, role = Role.Switch) { settings.forceSdr = it; enabled = it }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.playback_force_sdr), fontSize = 16.sp, lineHeight = 24.sp)
            Text(stringResource(R.string.playback_force_sdr_hint), fontSize = 12.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}
