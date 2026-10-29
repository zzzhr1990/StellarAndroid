package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.platform.AppLanguage
import cn.coldlake.stellar.opensource.platform.WindowHdrSupport
import cn.coldlake.stellar.opensource.appearance.AppearanceStore
import cn.coldlake.stellar.opensource.appearance.rememberAppearanceStore
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt
import cn.coldlake.stellar.opensource.navigation.LibraryNavDisplay
import cn.coldlake.stellar.opensource.navigation.SettingsDestination
import cn.coldlake.stellar.opensource.navigation.rememberHorizontalNavigationMetadata
import com.stellar.player.ui.LocalPageActive

private val SettingsText: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val SettingsSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SettingsAccent: Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable
internal fun SettingsScreen(route: String, onNavigateRoute: (String) -> Unit, onBack: () -> Unit,
    language: AppLanguage, onLanguage: (AppLanguage) -> Unit,
    pipEnabled: Boolean, pipSupported: Boolean, onPip: (Boolean) -> Unit,
    hdrEnabled: Boolean, hdrSupport: WindowHdrSupport, onHdr: (Boolean) -> Unit,
    appearance: AppearanceStore = rememberAppearanceStore(LocalContext.current),
    caches: cn.coldlake.stellar.opensource.cache.AppCaches? = cn.coldlake.stellar.opensource.cache.AppCaches.current,
    onScreenshotFolder: () -> Unit = {},
    navigationPath: List<String> = listOf(SettingsPage.HOME.name, route).distinct()) {
    val page = SettingsPage.entries.firstOrNull { it.name == route }
    val onNavigate: (SettingsPage) -> Unit = { onNavigateRoute(it.name) }
    var customDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = rememberSettingsPreviewStore(context)
    val seekSettings = remember(context.applicationContext) { PlaybackSeekSettings(context) }
    var accurateSeek by remember(seekSettings) { mutableStateOf(seekSettings.accurateSeek) }
    DisposableEffect(seekSettings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            accurateSeek = seekSettings.accurateSeek
        }
        seekSettings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { seekSettings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val inLibrarySettings = SettingsPage.LIBRARY.name in navigationPath
    LaunchedEffect(inLibrarySettings, caches) { if (inLibrarySettings) caches?.refreshUsage() }
    val security = remember(context.applicationContext) { MediaSecuritySettings(context) }
    var ignoreCertificateErrors by remember(security) { mutableStateOf(security.ignoreCertificateErrors) }
    DisposableEffect(security) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            ignoreCertificateErrors = security.ignoreCertificateErrors
        }
        security.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { security.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val navigateBack = {
        if (page == SettingsPage.CUSTOM_THEME) customDraft = null
        onBack()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(route) { snackbar.currentSnackbarData?.dismiss() }
    val previewSaved = stringResource(R.string.hs_preview_saved)
    val notifyPreview = { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(previewSaved) }; Unit }
    val navigationMotion = rememberHorizontalNavigationMetadata()
    MaterialTheme {
        Surface(Modifier.fillMaxSize().testTag("settings-page-$route"), color = Color.Transparent, contentColor = SettingsText) {
        Box(Modifier.fillMaxSize()) {
            LibraryNavDisplay(navigationPath.map(::SettingsDestination), Modifier.fillMaxSize(), onBack = navigateBack,
                entryMetadata = { navigationMotion }) { destination ->
            val target = destination.route
            val page = SettingsPage.entries.firstOrNull { it.name == target }
            key(target) {
                if (page == null) {
                    SettingsDetailPage(target, navigateBack, onNavigateRoute, language, onLanguage,
                        appearance, store, caches, customDraft ?: appearance.state.customPalette.encode(),
                        onCustomDraft = { customDraft = it }, onPreview = notifyPreview)
                } else if (page == SettingsPage.HOME) {
                    SettingsHome(onNavigate, language, { onNavigateRoute("language") })
                } else {
                    Column(Modifier.fillMaxSize()) {
                        SettingsToolbar(page, navigateBack, onNavigate)
                        when (page) {
                            SettingsPage.ABOUT -> AboutPage(
                                onDeviceInfo = { onNavigate(SettingsPage.DEVICE_INFO) },
                                onLicenses = { onNavigate(SettingsPage.OPEN_SOURCE) },
                                onAction = { onNavigateRoute("notice") })
                            SettingsPage.OPEN_SOURCE -> OpenSourcePage()
                            SettingsPage.DEVICE_INFO -> DeviceInfoPage()
                            SettingsPage.CUSTOM_THEME -> CustomThemePage(appearance,
                                customDraft ?: appearance.state.customPalette.encode(), { customDraft = it },
                                onEditColor = { dark, role -> onNavigateRoute("custom-color:$dark:${role.name}") },
                                onSaved = navigateBack)
                            SettingsPage.SCREENSHOT -> ScreenshotSettings(onScreenshotFolder)
                            SettingsPage.PLAYBACK_CACHE -> PlaybackCachePage(onNavigateRoute, caches)
                            SettingsPage.INTERACTION -> InteractionSettingsPage(onNavigateRoute)
                            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings-list-${page.name}")) {
                                if (page == SettingsPage.THEME) ThemeSettings(appearance, onNavigateRoute) { message ->
                                    scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(resources.getString(message)) }
                                } else settingsCatalogue[page].orEmpty().forEach { spec ->
                                    key(spec.id) {
                                        if (spec.id == "force_sdr") PlaybackOutputRow()
                                        else if (spec.kind == SettingKind.SubtitleLanguage) SubtitleLanguageRow(onNavigateRoute)
                                        else if(spec.id in cacheSettingIds && caches!=null) CacheSettingsRow(spec,caches,onNavigateRoute)
                                        else CatalogueRow(spec, store, notifyPreview, onNavigateRoute, pipEnabled, pipSupported, onPip,
                                            hdrEnabled, hdrSupport, onHdr, ignoreCertificateErrors,
                                            onIgnoreCertificateErrors = { security.ignoreCertificateErrors = it },
                                            accurateSeek = accurateSeek, onAccurateSeek = { seekSettings.accurateSeek = it })
                                    }
                                }
                                if (page == SettingsPage.SUBTITLE) SubtitleAppearanceRows(onNavigateRoute)
                                if (page == SettingsPage.LIBRARY) LibraryScanSettingsRows(onNavigateRoute)
                                if(page==SettingsPage.LIBRARY && caches!=null) CacheUsageFooter(caches)
                                if (page != SettingsPage.THEME && page != SettingsPage.AUDIO) Text(stringResource(
                                    when(page) { SettingsPage.ADVANCED_FEATURES -> R.string.hs_advanced_features_note
                                        SettingsPage.PLAYER -> R.string.screenshot_player_note
                                        SettingsPage.SUBTITLE -> R.string.settings_subtitle_note
                                        SettingsPage.LIBRARY -> R.string.cache_library_preview_note
                                        else -> R.string.hs_preview_note }), color = SettingsSecondary,
                                    fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(16.dp).testTag("settings-preview-note"))
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))

        }
        }
    }
}

@Composable
internal fun Symbol(@DrawableRes icon: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color = SettingsSecondary) {
    Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = modifier.size(24.dp))
}

@Composable
private fun SettingsToolbar(page: SettingsPage, onBack: () -> Unit, onNavigate: (SettingsPage) -> Unit) {
    SettingsPageToolbar(page.title, onBack)
}

@Composable
internal fun SettingsPageToolbar(title: Int, onBack: () -> Unit, tag: String = "settings-page-back") {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, Modifier.size(56.dp).testTag(tag)) {
            Symbol(R.drawable.material_symbol_arrow_back, description = stringResource(R.string.back))
        }
        Text(stringResource(title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { heading() })
    }
}

@Composable
private fun SettingsHome(onNavigate: (SettingsPage) -> Unit, language: AppLanguage, onLanguage: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home-settings")) {
        Text(stringResource(R.string.home_settings), style = MaterialTheme.typography.headlineMediumEmphasized,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp).semantics { heading() })
        SectionTitle(R.string.hs_general)
        LanguageRow(language, onLanguage)
        SettingsNavigationRow(R.string.hs_theme, R.drawable.material_symbol_palette, "THEME", groupIndex = 1, groupCount = 3, iconColor = SettingsIconColor.ORANGE) { onNavigate(SettingsPage.THEME) }
        SettingsNavigationRow(R.string.hs_library, R.drawable.material_symbol_video_library, "LIBRARY", groupIndex = 2, groupCount = 3, iconColor = SettingsIconColor.BLUE) { onNavigate(SettingsPage.LIBRARY) }
        Spacer(Modifier.height(16.dp))
        SectionTitle(R.string.hs_player)
        SettingsNavigationRow(R.string.hs_interaction, R.drawable.material_symbol_touch_app, "INTERACTION", groupIndex = 0, groupCount = 7, iconColor = SettingsIconColor.ORANGE) { onNavigate(SettingsPage.INTERACTION) }
        SettingsNavigationRow(R.string.hs_player, R.drawable.material_symbol_play_circle, "PLAYER", groupIndex = 1, groupCount = 7, iconColor = SettingsIconColor.BLUE) { onNavigate(SettingsPage.PLAYER) }
        SettingsNavigationRow(R.string.playback_cache_title, R.drawable.material_symbol_hard_drive, "PLAYBACK_CACHE", groupIndex = 2, groupCount = 7, iconColor = SettingsIconColor.GREEN) { onNavigate(SettingsPage.PLAYBACK_CACHE) }
        SettingsNavigationRow(R.string.settings_audio, com.stellar.player.ui.R.drawable.hills_headphones, "AUDIO", groupIndex = 3, groupCount = 7, iconColor = SettingsIconColor.PINK) { onNavigate(SettingsPage.AUDIO) }
        SettingsNavigationRow(R.string.settings_subtitle, R.drawable.material_symbol_subtitles, "SUBTITLE", groupIndex = 4, groupCount = 7, iconColor = SettingsIconColor.PURPLE) { onNavigate(SettingsPage.SUBTITLE) }
        SettingsNavigationRow(R.string.screenshot_title, com.stellar.player.ui.R.drawable.hills_photo_camera, "SCREENSHOT", groupIndex = 5, groupCount = 7, iconColor = SettingsIconColor.ORANGE) { onNavigate(SettingsPage.SCREENSHOT) }
        SettingsNavigationRow(R.string.hs_advanced_features, R.drawable.material_symbol_science, "ADVANCED_FEATURES", groupIndex = 6, groupCount = 7, iconColor = SettingsIconColor.CYAN) { onNavigate(SettingsPage.ADVANCED_FEATURES) }
        Spacer(Modifier.height(16.dp))
        SectionTitle(R.string.hs_about)
        SettingsNavigationRow(R.string.hs_about, R.drawable.material_symbol_info, "ABOUT", iconColor = SettingsIconColor.BLUE) { onNavigate(SettingsPage.ABOUT) }
        Spacer(Modifier.height(cn.coldlake.stellar.opensource.LocalHomeNavigationBottomInset.current))
    }
}

@Composable
internal fun SectionTitle(title: Int) {
    Text(stringResource(title), color = SettingsAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
internal fun SettingsNavigationRow(title: Int, icon: Int, tag: String, subtitle: String? = null,
    groupIndex: Int = 0, groupCount: Int = 1, iconColor: SettingsIconColor? = null, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick, shapes = ListItemDefaults.segmentedShapes(groupIndex, groupCount),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth().testTag("settings-open-$tag"),
        leadingContent = {
            if (iconColor == null) Symbol(icon, tint = SettingsAccent)
            else SettingsCategoryIcon(icon, iconColor)
        },
        supportingContent = subtitle?.let { { Text(it) } },
    ) { Text(stringResource(title), style = MaterialTheme.typography.bodyLargeEmphasized) }
}

@Composable
private fun LanguageRow(language: AppLanguage, onOpen: () -> Unit) {
    val options = listOf(R.string.language_system, R.string.language_hans, R.string.language_hant, R.string.language_english)
    SettingsNavigationRow(R.string.hs_language, R.drawable.material_symbol_language, "LANGUAGE", stringResource(options[language.ordinal]),
        groupIndex = 0, groupCount = 3, iconColor = SettingsIconColor.CYAN, onClick = onOpen)
}

@Composable
internal fun RowText(title: Int, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        }
        if (!subtitle.isNullOrEmpty()) Text(subtitle, color = SettingsSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogueRow(spec: SettingSpec, store: SettingsPreviewStore, onPreview: () -> Unit, onDetail: (String) -> Unit,
    pipEnabled: Boolean, pipSupported: Boolean, onPip: (Boolean) -> Unit,
    hdrEnabled: Boolean, hdrSupport: WindowHdrSupport, onHdr: (Boolean) -> Unit,
    ignoreCertificateErrors: Boolean, onIgnoreCertificateErrors: (Boolean) -> Unit,
    accurateSeek: Boolean, onAccurateSeek: (Boolean) -> Unit) {
    if (spec.kind == SettingKind.Section) {
        if (spec.id == "backup_section") Spacer(Modifier.height(24.dp))
        SectionTitle(spec.title)
        return
    }
    val isToggle = spec.kind in setOf(SettingKind.Toggle, SettingKind.Pip, SettingKind.WebDavTls, SettingKind.WindowHdr, SettingKind.AccurateSeek)
    val textValue = if (spec.kind == SettingKind.Edit) store.text(spec) else ""
    val subtitle = when {
        spec.kind == SettingKind.WindowHdr -> stringResource(when (hdrSupport) {
            WindowHdrSupport.SUPPORTED -> R.string.hs_force_hdr_hint
            WindowHdrSupport.ANDROID_VERSION -> R.string.hs_force_hdr_android
            WindowHdrSupport.DISPLAY_UNSUPPORTED -> R.string.hs_force_hdr_display
        })
        spec.kind == SettingKind.Choice -> stringResource(spec.options[store.choice(spec)])
        spec.kind == SettingKind.Edit && textValue.isNotBlank() -> textValue
        spec.subtitle != null -> stringResource(spec.subtitle)
        else -> null
    }
    val checked = when (spec.kind) {
        SettingKind.Pip -> pipEnabled
        SettingKind.WebDavTls -> ignoreCertificateErrors
        SettingKind.WindowHdr -> hdrEnabled
        SettingKind.AccurateSeek -> accurateSeek
        SettingKind.Toggle -> store.boolean(spec)
        else -> false
    }
    val changeToggle: (Boolean) -> Unit = {
        when (spec.kind) {
            SettingKind.Pip -> onPip(it)
            SettingKind.WebDavTls -> onIgnoreCertificateErrors(it)
            SettingKind.WindowHdr -> onHdr(it)
            SettingKind.AccurateSeek -> onAccurateSeek(it)
            else -> { store.save(spec.id, it); onPreview() }
        }
    }
    Box {
        if (spec.kind == SettingKind.Slider) {
            var draft by remember(spec.id, store.number(spec)) { mutableFloatStateOf(store.number(spec)) }
            val locale = LocalConfiguration.current.locales[0]
            val value = if (spec.unit == R.string.hs_multiplier) stringResource(spec.unit,
                NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(draft))
                else stringResource(spec.unit, draft.roundToInt())
            val sliderLabel = stringResource(spec.title)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().heightIn(min = 96.dp)
                .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(start = if (spec.indent) 32.dp else 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                spec.icon?.let { Symbol(it); Spacer(Modifier.width(16.dp)) }
                Column(Modifier.weight(1f)) {
                    RowText(spec.title, subtitle)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val sliderState = remember(spec.id, spec.range) { SliderState(draft, trackRange = spec.range) }
                        sliderState.value = draft
                        Slider(state = sliderState, onValueChange = { draft = it },
                            onValueChangeFinished = { store.save(spec.id, draft); onPreview() },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("setting-${spec.id}").semantics { contentDescription = sliderLabel }, interactionSource = interaction,
                            thumb = { Box(Modifier.size(4.dp, 44.dp).clip(CircleShape).background(SettingsAccent)) },
                            track = { state -> SliderDefaults.Track(state, Modifier.height(16.dp),
                                colors = SliderDefaults.colors(activeTrackColor = SettingsAccent, inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
                                thumbTrackGapSize = 4.dp, drawStopIndicator = null) })
                        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 52.dp).padding(start = 8.dp).testTag("setting-value-${spec.id}"))
                    }
                }
            }
        } else {
            val action = {
                onDetail(if (spec.kind in setOf(SettingKind.Choice, SettingKind.Edit, SettingKind.Color))
                    "catalogue:${spec.id}" else "notice")
            }
            val enabled = when (spec.kind) {
                SettingKind.Pip -> pipSupported
                SettingKind.WindowHdr -> hdrEnabled || hdrSupport == WindowHdrSupport.SUPPORTED
                else -> true
            }
            val minimumHeight = when {
                spec.kind == SettingKind.Color -> 64.dp
                spec.kind == SettingKind.Action && subtitle == null -> 56.dp
                else -> 72.dp
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().heightIn(min = minimumHeight)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .testTag(if (spec.kind == SettingKind.Pip) "pip-setting" else "setting-${spec.id}")
                .then(if (isToggle) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = changeToggle)
                    else Modifier.clickable(role = Role.Button, onClick = action))
                .padding(start = if (spec.indent) 32.dp else 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                spec.icon?.let { Symbol(it); Spacer(Modifier.width(16.dp)) }
                RowText(spec.title, subtitle, Modifier.weight(1f))
                if (isToggle) {
                    Spacer(Modifier.width(12.dp))
                    Switch(checked, onCheckedChange = null, enabled = enabled,
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = SettingsAccent,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant, uncheckedBorderColor = MaterialTheme.colorScheme.outline))
                }
                if (spec.kind == SettingKind.Color) {
                    Spacer(Modifier.width(16.dp))
                    val palette = listOf(SettingsAccent, Color(0xFFAAC7FF), Color(0xFFA8D5BA), Color(0xFFF3B7C8))
                    Box(Modifier.size(32.dp).clip(CircleShape).background(palette[store.number(spec).roundToInt().coerceIn(0,3)])
                        .border(1.dp, SettingsSecondary, CircleShape))
                }
            }
        }
    }
}
