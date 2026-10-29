package cn.coldlake.stellar.opensource.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.PlaybackBackend
import com.stellar.player.contract.SubtitleAppearanceOption
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.appearance.*
import cn.coldlake.stellar.opensource.cache.AppCaches
import cn.coldlake.stellar.opensource.cache.CacheRetention
import cn.coldlake.stellar.opensource.platform.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Stable route arguments identify settings; callbacks and page content are never saved. */
@Composable
internal fun SettingsDetailPage(route: String, onBack: () -> Unit, onNavigate: (String) -> Unit,
    language: AppLanguage, onLanguage: (AppLanguage) -> Unit, appearance: AppearanceStore,
    preview: SettingsPreviewStore, caches: AppCaches?, customDraft: String,
    onCustomDraft: (String) -> Unit, onPreview: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val kind = route.substringBefore(':')
    val argument = route.substringAfter(':', "")
    val spec = remember(route) {
        if (kind == "catalogue" || kind == "cache") settingsCatalogue.values.flatten().first { it.id == argument } else null
    }
    val subtitle = if (kind == "subtitle") SubtitleAppearanceOption.valueOf(argument) else null
    val colorRole = if (kind == "custom-color") CustomColorRole.valueOf(argument.substringAfter(':')) else null
    val title = when (kind) {
        "handedness" -> R.string.interaction_handedness
        "language" -> R.string.language
        "theme-mode" -> R.string.hs_theme_mode
        "theme-palette" -> R.string.hs_theme_color
        "theme-background" -> R.string.hs_background_style
        "library-scan-threads" -> R.string.library_scan_threads
        "subtitle-language" -> R.string.hs_subtitle_language
        "subtitle" -> optionTitle(requireNotNull(subtitle))
        "buffer-minimum" -> R.string.playback_buffer_minimum
        "buffer-maximum" -> R.string.playback_buffer_maximum
        "playback-retention" -> R.string.playback_cache_retention
        "catalogue", "cache" -> requireNotNull(spec).title
        "custom-color" -> requireNotNull(colorRole).label
        else -> R.string.hs_preview_title
    }
    Column(Modifier.fillMaxSize().testTag("settings-detail-$route")) {
        SettingsPageToolbar(title, onBack)
        when (kind) {
            "handedness" -> {
                val settings = remember(context) { HandednessSettings(context) }
                SettingsChoicePage(listOf(R.string.interaction_left_handed, R.string.interaction_right_handed)
                    .map { stringResource(it) }, if (rememberRightHanded()) 1 else 0,
                    tag = { if (it == 0) "handedness-left" else "handedness-right" },
                    onSelect = { settings.rightHanded = it == 1; onBack() })
            }
            "library-scan-threads" -> {
                val settings = remember(context) { LibraryScanSettings(context) }
                SettingsChoicePage((1..4).map { pluralStringResource(R.plurals.library_scan_threads_count, it, it) },
                    settings.scanThreads - 1, tag = { "library-scan-threads-${it + 1}" },
                    onSelect = { settings.scanThreads = it + 1; onBack() })
            }
            "language" -> SettingsChoicePage(
                listOf(R.string.language_system, R.string.language_hans, R.string.language_hant, R.string.language_english)
                    .map { stringResource(it) }, language.ordinal, onSelect = { onLanguage(AppLanguage.entries[it]) })
            "theme-mode" -> SettingsChoicePage(
                listOf(R.string.hs_system, R.string.hs_light, R.string.hs_dark).map { stringResource(it) },
                appearance.state.mode.ordinal, onSelect = { appearance.mode(ThemeMode.entries[it]); onBack() })
            "theme-palette" -> PalettePage(appearance, onBack, onNavigate)
            "theme-background" -> BackgroundStylePage(appearance, onBack)


            "subtitle-language" -> {
                val settings = remember(context) { SubtitleLanguageSettings(context) }
                SettingsChoicePage(listOf(R.string.subtitle_language_off, R.string.subtitle_language_current,
                    R.string.hs_simplified, R.string.hs_traditional).map { stringResource(it) }, settings.language.ordinal,
                    tag = { "subtitle-language-${SubtitleLanguage.entries[it].name}" },
                    onSelect = { settings.language = SubtitleLanguage.entries[it]; onBack() })
            }
            "subtitle" -> SubtitleAppearancePage(requireNotNull(subtitle), onBack)
            "buffer-minimum", "buffer-maximum" -> BufferChoicePage(kind == "buffer-minimum", onBack)
            "playback-retention" -> {
                val settings = remember(context) { PlaybackCacheSettings(context) }
                SettingsChoicePage(listOf(R.string.playback_cache_day_1, R.string.playback_cache_day_7,
                    R.string.playback_cache_day_30).map { stringResource(it) },
                    PlaybackCacheSettings.RETENTION_DAYS.indexOf(settings.retentionDays), onSelect = {
                        settings.retentionDays = PlaybackCacheSettings.RETENTION_DAYS[it]; onBack()
                    })
            }
            "cache" -> if (caches != null) {
                val settings by caches.settings.state.collectAsState()
                val maintenance by caches.maintenance.collectAsState()
                val image = argument == "image_expiry"
                SettingsChoicePage(requireNotNull(spec).options.map { stringResource(it) },
                    (if (image) settings.imageRetention else settings.dataRetention).ordinal,
                    enabled = !maintenance.busy, onSelect = {
                        caches.setRetention(image, CacheRetention.entries[it]); onBack()
                    })
            }
            "catalogue" -> CatalogueOptionPage(requireNotNull(spec), preview) { onBack(); onPreview() }
            "custom-color" -> {
                val dark = argument.substringBefore(':').toBoolean()
                val draft = remember(customDraft) { CustomPalette.decode(customDraft) }
                val role = requireNotNull(colorRole)
                ColorEditor(role, draft.colors(dark).color(role), onBack) {
                    onCustomDraft(draft.withColor(dark, role, it).encode()); onBack()
                }
            }
            else -> SettingsMessagePage(R.string.hs_unavailable, onBack)
        }
    }
}

@Composable
internal fun SettingsChoicePage(labels: List<String>, selected: Int, enabled: Boolean = true,
    tag: (Int) -> String = { "settings-choice-$it" }, onSelect: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().selectableGroup().testTag("settings-choice-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(labels, key = { index, _ -> index }, contentType = { _, _ -> "choice" }) { index, label ->
            ListItem(selected = selected == index, onClick = { onSelect(index) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(tag(index)).semantics { role = Role.RadioButton },
                shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                leadingContent = { RadioButton(selected == index, onClick = null, enabled = enabled) }) {
                Text(label, style = MaterialTheme.typography.bodyLargeEmphasized)
            }
        }
    }
}

@Composable
private fun PalettePage(store: AppearanceStore, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val state = store.state
    val names = listOf(R.string.hs_color_lavender, R.string.hs_color_blue, R.string.hs_color_green,
        R.string.hs_color_pink, R.string.ap_color_stellar, R.string.ap_color_custom, R.string.ap_color_golden_age,
        R.string.ap_color_red_impressions)
    val indices = listOf(STELLAR_PALETTE_INDEX, GOLDEN_AGE_PALETTE_INDEX, RED_IMPRESSIONS_PALETTE_INDEX,
        0, 1, 2, 3, CUSTOM_PALETTE_INDEX)
    LazyColumn(Modifier.fillMaxSize().selectableGroup().testTag("appearance-palette-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        itemsIndexed(indices, key = { _, it -> it }) { _, index ->
            val color = if (index == CUSTOM_PALETTE_INDEX) Color(state.customPalette.dark.primary) else palettePreview.getValue(index)
            val selected = !state.dynamic && state.palette == index
            ListItem(selected = selected, onClick = {
                if (index == CUSTOM_PALETTE_INDEX) onNavigate(SettingsPage.CUSTOM_THEME.name)
                else { store.palette(index); onBack() }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("appearance-palette-$index")
                .semantics { role = Role.RadioButton },
                shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                leadingContent = {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        if (selected) Symbol(R.drawable.material_symbol_check, tint = readableOn(color))
                    }
                }) { Text(stringResource(names[index]), style = MaterialTheme.typography.bodyLargeEmphasized) }
        }
    }
}

@Composable
private fun BackgroundStylePage(store: AppearanceStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; failed = false
            try { store.importImage(uri); onBack() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { importing = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.ap_image_error), modifier = Modifier.padding(16.dp))
        SettingsChoicePage(listOf(R.string.ap_background_default, R.string.hs_dynamic, R.string.ap_background_image)
            .map { stringResource(it) }, store.state.background.ordinal, enabled = !importing, onSelect = {
                val selected = BackgroundStyle.entries[it]
                if (selected == BackgroundStyle.IMAGE && store.file(store.state.imageFile) == null) picker.launch(arrayOf("image/*"))
                else { store.background(selected); onBack() }
            })
    }
}

@Composable
private fun BufferChoicePage(minimum: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { PlaybackBufferSettings(context) }
    val options = settings.options
    val choices = if (minimum) PlaybackBufferSettings.MINIMUM_SECONDS.filter { it * 1_000L <= options.maxMs }
        else PlaybackBufferSettings.MAXIMUM_SECONDS.filter { it * 1_000L >= options.minMs }
    val selected = ((if (minimum) options.minMs else options.maxMs) / 1_000).toInt()
    SettingsChoicePage(choices.map { if (it == 0) stringResource(R.string.playback_buffer_no_wait)
        else stringResource(R.string.playback_buffer_seconds, it) }, choices.indexOf(selected),
        tag = { "playback-buffer-choice-${choices[it]}" }, onSelect = {
            val latest = settings.options
            settings.options = if (minimum) latest.copy(minMs = choices[it] * 1_000L) else latest.copy(maxMs = choices[it] * 1_000L)
            onBack()
        })
}

@Composable
private fun SubtitleAppearancePage(option: SubtitleAppearanceOption, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SubtitleAppearanceSettings(context) }
    val selected = settings[option]
    if (option.choices.isNotEmpty()) {
        SettingsChoicePage(optionLabels(option).map { stringResource(it) }, option.choices.indexOf(selected),
            tag = { "subtitle-appearance-choice-$it" }, onSelect = { settings[option] = option.choices[it]; onBack() })
    } else {
        var draft by rememberSaveable(option) { mutableFloatStateOf(selected.toFloat()) }
        val range = requireNotNull(option.range)
        val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0])
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(format.format(option.validated(draft.toString()).toFloat()), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("subtitle-appearance-draft"))
            Slider(value = draft, onValueChange = { draft = it }, valueRange = range,
                steps = (kotlin.math.round((range.endInclusive - range.start) / option.step).toInt() - 1).coerceAtLeast(0),
                modifier = Modifier.testTag("subtitle-appearance-slider").semantics { contentDescription = format.format(draft) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.subtitle_appearance_cancel)) }
                Button(onClick = { settings[option] = draft.toString(); onBack() },
                    modifier = Modifier.testTag("subtitle-appearance-apply")) { Text(stringResource(R.string.subtitle_appearance_apply)) }
            }
        }
    }
}

@Composable
private fun CatalogueOptionPage(spec: SettingSpec, store: SettingsPreviewStore, onSaved: () -> Unit) {
    when (spec.kind) {
        SettingKind.Choice -> SettingsChoicePage(spec.options.map { stringResource(it) }, store.choice(spec),
            onSelect = { store.saveChoice(spec.id, it); onSaved() })
        SettingKind.Edit -> {
            var input by rememberSaveable(spec.id) { mutableStateOf(store.text(spec)) }
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                spec.subtitle?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("settings-edit-input"), minLines = 2)
                Button(onClick = { store.save(spec.id, input); onSaved() }, modifier = Modifier.fillMaxWidth()
                    .testTag("settings-edit-save")) { Text(stringResource(R.string.hs_done)) }
            }
        }
        SettingKind.Color -> SettingsChoicePage(listOf(R.string.hs_color_lavender, R.string.hs_color_blue,
            R.string.hs_color_green, R.string.hs_color_pink).map { stringResource(it) }, store.number(spec).toInt(),
            onSelect = { store.save(spec.id, it.toFloat()); onSaved() })
        else -> Unit
    }
}

@Composable
private fun SettingsMessagePage(message: Int, onBack: () -> Unit, confirmLabel: Int = R.string.hs_done,
    confirmTag: String = "settings-notice-close", cancelTag: String = "settings-message-cancel", onConfirm: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onConfirm != null) TextButton(onClick = onBack, modifier = Modifier.testTag(cancelTag)) {
                Text(stringResource(R.string.hs_cancel))
            }
            Button(onClick = onConfirm ?: onBack, modifier = Modifier.testTag(confirmTag)) { Text(stringResource(confirmLabel)) }
        }
    }
}
