package cn.coldlake.stellar.opensource.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.appearance.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeSettings(store: AppearanceStore, onNavigate: (String) -> Unit, onError: (Int) -> Unit) {
    val value = store.state
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try { store.importFont(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError(R.string.ap_font_error) }
            finally { importing = false }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try { store.importImage(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError(R.string.ap_image_error) }
            finally { importing = false }
        }
    }
    fun chooseFont() = fontPicker.launch(arrayOf("*/*"))
    fun chooseImage() = imagePicker.launch(arrayOf("image/*"))
    val specs = settingsCatalogue.getValue(SettingsPage.THEME).associateBy { it.id }
    val paletteNames = listOf(R.string.hs_color_lavender, R.string.hs_color_blue, R.string.hs_color_green,
        R.string.hs_color_pink, R.string.ap_color_stellar, R.string.ap_color_custom, R.string.ap_color_golden_age,
        R.string.ap_color_red_impressions)

    Box {
        ThemeRow(specs.getValue("theme_mode"), stringResource(listOf(R.string.hs_system, R.string.hs_light, R.string.hs_dark)[value.mode.ordinal]),
            iconColor = SettingsIconColor.PURPLE, onClick = { onNavigate("theme-mode") })

    }
    Box {
        ThemeRow(specs.getValue("theme_color"), stringResource(if (value.dynamic) R.string.ap_system_colors_active else paletteNames[value.palette]),
            iconColor = SettingsIconColor.ORANGE, onClick = { onNavigate("theme-palette") }) {
            Box(Modifier.size(32.dp).testTag("appearance-primary-swatch").clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
    }

    ThemeToggle(specs.getValue("material_you"), value.dynamic, stringResource(R.string.hs_material_you_hint),
        iconColor = SettingsIconColor.CYAN, enabled = Build.VERSION.SDK_INT >= 31, change = store::dynamic)
    ThemeToggle(specs.getValue("app_font"), value.customFont,
        if (value.customFont) value.fontName else stringResource(R.string.hs_app_font_hint),
        iconColor = SettingsIconColor.PINK, enabled = !importing) { enabled ->
        if (!enabled) store.fontEnabled(false)
        else if (store.file(value.fontFile) != null) store.fontEnabled(true)
        else chooseFont()
    }
    if (value.customFont) TextButton(onClick = ::chooseFont, enabled = !importing,
        modifier = Modifier.padding(start = 48.dp).testTag("appearance-choose-font")) { Text(stringResource(R.string.ap_choose_font)) }
    ThemeToggle(specs.getValue("floating_nav"), value.floatingNavigation, null,
        iconColor = SettingsIconColor.BLUE, change = store::floating)
    ThemeToggle(specs.getValue("hide_nav_labels"), value.hideNavigationLabels,
        stringResource(R.string.hs_hide_nav_labels_hint), iconColor = SettingsIconColor.BLUE, change = store::hideNavigationLabels)
    Box {
        val labels = listOf(R.string.ap_background_default, R.string.hs_dynamic, R.string.ap_background_image)
        ThemeRow(specs.getValue("background_style"), stringResource(labels[value.background.ordinal]),
            iconColor = SettingsIconColor.GREEN, enabled = !importing,
            onClick = { onNavigate("theme-background") })

    }
    if (value.background == BackgroundStyle.IMAGE) TextButton(onClick = ::chooseImage, enabled = !importing,
        modifier = Modifier.padding(start = 48.dp).testTag("appearance-choose-image")) {
        Text(stringResource(R.string.ap_choose_image))
    }
    if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("appearance-importing"))
}

@Composable
private fun ThemeRow(spec: SettingSpec, subtitle: String?, iconColor: SettingsIconColor, enabled: Boolean = true, onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}) {
    ListItem(onClick = onClick, enabled = enabled,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().testTag("setting-${spec.id}"),
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { SettingsCategoryIcon(requireNotNull(spec.icon), iconColor, enabled) },
        supportingContent = subtitle?.let { { Text(it) } }, trailingContent = trailing,
    ) { Text(stringResource(spec.title), style = MaterialTheme.typography.bodyLargeEmphasized) }
}

@Composable
private fun ThemeToggle(spec: SettingSpec, checked: Boolean, subtitle: String?, iconColor: SettingsIconColor,
    enabled: Boolean = true, change: (Boolean) -> Unit) {
    ListItem(checked = checked, onCheckedChange = change, enabled = enabled,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
            .testTag("setting-${spec.id}").semantics { role = Role.Switch },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { SettingsCategoryIcon(requireNotNull(spec.icon), iconColor, enabled) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(stringResource(spec.title), style = MaterialTheme.typography.bodyLargeEmphasized) }
}
