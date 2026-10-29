package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import com.stellar.player.contract.SubtitleAppearanceOption
import java.text.NumberFormat

@Composable
internal fun SubtitleAppearanceRows(onNavigate: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SubtitleAppearanceSettings(context) }
    Text(stringResource(R.string.subtitle_appearance_all), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(16.dp).semantics { heading() })
    SubtitleAppearanceOption.entries.forEach { option ->
        key(option) {
            if (option == SubtitleAppearanceOption.FONT_SIZE) {
                Text(stringResource(R.string.subtitle_appearance_text), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp).semantics { heading() })
                Text(stringResource(R.string.subtitle_appearance_text_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            SubtitleAppearanceRow(settings, option, onNavigate)
        }
    }
    TextButton(onClick = { settings.reset() }, modifier = Modifier.padding(8.dp)
        .testTag("subtitle-appearance-reset")) { Text(stringResource(R.string.subtitle_appearance_reset)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleAppearanceRow(settings: SubtitleAppearanceSettings, option: SubtitleAppearanceOption, onNavigate: (String) -> Unit) {
    var selected by remember(settings, option) { mutableStateOf(settings[option]) }
    DisposableEffect(settings, option) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == option.name) selected = settings[option]
        }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val labels = optionLabels(option)
    val value = if (option.choices.isNotEmpty()) stringResource(labels[option.choices.indexOf(selected)])
        else NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).format(selected.toFloat())
    val hint = when (option) {
        SubtitleAppearanceOption.SIZE -> R.string.subtitle_appearance_size_hint
        SubtitleAppearanceOption.SHADOW -> R.string.subtitle_appearance_shadow_hint
        SubtitleAppearanceOption.BOX -> R.string.subtitle_appearance_box_hint
        SubtitleAppearanceOption.MARGIN_X, SubtitleAppearanceOption.MARGIN_Y -> R.string.subtitle_appearance_margin_hint
        else -> null
    }
    ListItem(
        headlineContent = { Text(stringResource(optionTitle(option))) },
        supportingContent = if (!option.toggle) { {
            Column {
                Text(value, color = MaterialTheme.colorScheme.primary)
                hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        } } else null,
        trailingContent = if (option.toggle) { {
            Switch(checked = selected == "1", onCheckedChange = null)
        } } else null,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .testTag("subtitle-appearance-${option.name}")
            .semantics { if (option.toggle) { role = Role.Switch; toggleableState = if (selected == "1")
                androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off } }
            .clickable(role = if (option.toggle) Role.Switch else Role.Button) {
                if (option.toggle) settings[option] = if (selected == "1") "0" else "1" else onNavigate("subtitle:${option.name}")
            },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

internal fun optionTitle(option: SubtitleAppearanceOption): Int = when (option) {
    SubtitleAppearanceOption.SCALE -> R.string.subtitle_appearance_scale
    SubtitleAppearanceOption.SIZE -> R.string.subtitle_appearance_size
    SubtitleAppearanceOption.CC -> R.string.subtitle_appearance_cc
    SubtitleAppearanceOption.FONT_SIZE -> R.string.subtitle_appearance_font_size
    SubtitleAppearanceOption.SPACING -> R.string.subtitle_appearance_spacing
    SubtitleAppearanceOption.BOLD -> R.string.subtitle_appearance_bold
    SubtitleAppearanceOption.ITALIC -> R.string.subtitle_appearance_italic
    SubtitleAppearanceOption.COLOR -> R.string.subtitle_appearance_color
    SubtitleAppearanceOption.OUTLINE_COLOR -> R.string.subtitle_appearance_outline_color
    SubtitleAppearanceOption.BACKGROUND_COLOR -> R.string.subtitle_appearance_background_color
    SubtitleAppearanceOption.BORDER -> R.string.subtitle_appearance_border
    SubtitleAppearanceOption.SHADOW -> R.string.subtitle_appearance_shadow
    SubtitleAppearanceOption.BOX -> R.string.subtitle_appearance_box
    SubtitleAppearanceOption.ALIGNMENT_X -> R.string.subtitle_appearance_alignment_x
    SubtitleAppearanceOption.ALIGNMENT_Y -> R.string.subtitle_appearance_alignment_y
    SubtitleAppearanceOption.MARGIN_X -> R.string.subtitle_appearance_margin_x
    SubtitleAppearanceOption.MARGIN_Y -> R.string.subtitle_appearance_margin_y
    SubtitleAppearanceOption.BLUR -> R.string.subtitle_appearance_blur
}

internal fun optionLabels(option: SubtitleAppearanceOption): List<Int> = when (option) {
    SubtitleAppearanceOption.SIZE -> listOf(R.string.subtitle_appearance_video, R.string.subtitle_appearance_fit, R.string.subtitle_appearance_surface)
    SubtitleAppearanceOption.ALIGNMENT_X -> listOf(R.string.subtitle_appearance_left, R.string.subtitle_appearance_center, R.string.subtitle_appearance_right)
    SubtitleAppearanceOption.ALIGNMENT_Y -> listOf(R.string.subtitle_appearance_top, R.string.subtitle_appearance_center, R.string.subtitle_appearance_bottom)
    SubtitleAppearanceOption.COLOR -> listOf(R.string.subtitle_appearance_white, R.string.subtitle_appearance_yellow, R.string.subtitle_appearance_red,
        R.string.subtitle_appearance_green, R.string.subtitle_appearance_cyan, R.string.subtitle_appearance_black)
    SubtitleAppearanceOption.OUTLINE_COLOR -> listOf(R.string.subtitle_appearance_black, R.string.subtitle_appearance_white, R.string.subtitle_appearance_yellow,
        R.string.subtitle_appearance_red, R.string.subtitle_appearance_green, R.string.subtitle_appearance_cyan)
    SubtitleAppearanceOption.BACKGROUND_COLOR -> listOf(R.string.subtitle_appearance_transparent, R.string.subtitle_appearance_half_black,
        R.string.subtitle_appearance_black, R.string.subtitle_appearance_half_white, R.string.subtitle_appearance_white)
    else -> emptyList()
}
