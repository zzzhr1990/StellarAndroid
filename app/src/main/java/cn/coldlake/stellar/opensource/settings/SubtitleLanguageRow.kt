package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleLanguageRow(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { SubtitleLanguageSettings(context.applicationContext) }
    var selected by remember(settings) { mutableStateOf(settings.language) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> selected = settings.language }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val labels = listOf(R.string.subtitle_language_off, R.string.subtitle_language_current,
        R.string.hs_simplified, R.string.hs_traditional)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
        .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .testTag("setting-subtitle_language").clickable(role = Role.Button) { onNavigate("subtitle-language") }.padding(16.dp)) {
        Text(stringResource(R.string.hs_subtitle_language), style = MaterialTheme.typography.bodyLargeEmphasized)
        Text(stringResource(labels[selected.ordinal]), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("subtitle-language-value"))
        Text(stringResource(R.string.subtitle_language_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}
