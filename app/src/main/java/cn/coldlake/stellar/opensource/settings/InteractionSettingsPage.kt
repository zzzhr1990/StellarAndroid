package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import java.text.NumberFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InteractionSettingsPage(onNavigate: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { PlaybackInteractionSettings(context) }
    var values by remember(settings) { mutableStateOf(settings.read()) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> values = settings.read() }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings-list-INTERACTION")) {
        val rightHanded = rememberRightHanded()
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("setting-handedness").clickable(role = Role.Button) { onNavigate("handedness") }
            .heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText(R.string.interaction_handedness,
                stringResource(if (rightHanded) R.string.interaction_right_handed else R.string.interaction_left_handed),
                Modifier.weight(1f))
            Symbol(R.drawable.material_symbol_chevron_right)
        }
        settingsCatalogue.getValue(SettingsPage.INTERACTION).forEach { spec -> key(spec.id) {
            val enabled = !spec.indent || values.longPress
            val subtitle = spec.subtitle?.let { stringResource(it) }
            val base = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
                .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
            if (spec.kind == SettingKind.Toggle) {
                val checked = remember(values, spec.id) { settings.boolean(spec) }
                Row(base.testTag("setting-${spec.id}").toggleable(checked, enabled = enabled, role = Role.Switch,
                    onValueChange = { settings.save(spec.id, it) })
                    .heightIn(min = 72.dp).padding(start = if (spec.indent) 32.dp else 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RowText(spec.title, subtitle, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(checked, onCheckedChange = null, enabled = enabled)
                }
            } else {
                val saved = remember(values, spec.id) { settings.number(spec) }
                var draft by remember(saved) { mutableFloatStateOf(saved) }
                val multiplier = spec.id == "hold_speed"
                val label = stringResource(spec.title)
                val locale = LocalConfiguration.current.locales[0]
                val value = if (multiplier) stringResource(spec.unit, NumberFormat.getNumberInstance(locale).apply {
                    minimumFractionDigits = 1; maximumFractionDigits = 1
                }.format(draft)) else stringResource(spec.unit, draft.roundToInt())
                Column(base.padding(start = if (spec.indent) 32.dp else 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                    RowText(spec.title, subtitle)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val slider = remember(spec.id) { SliderState(draft, trackRange = spec.range,
                            steps = if (multiplier) 74 else 58) }
                        slider.value = draft
                        Slider(state = slider, enabled = enabled, onValueChange = { draft = it },
                            onValueChangeFinished = { settings.save(spec.id, draft) },
                            modifier = Modifier.weight(1f).testTag("setting-${spec.id}").semantics { contentDescription = label })
                        Text(value, Modifier.widthIn(min = 64.dp).padding(start = 8.dp).testTag("setting-value-${spec.id}"))
                    }
                }
            }
        } }
        Text(stringResource(R.string.interaction_settings_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp).testTag("interaction-settings-note"))
        Spacer(Modifier.height(24.dp))
    }
}
