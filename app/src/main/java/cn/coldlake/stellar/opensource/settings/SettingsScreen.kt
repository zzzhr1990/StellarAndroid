package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.platform.AppLanguage
import cn.coldlake.stellar.opensource.platform.WindowHdrSupport
import cn.coldlake.stellar.opensource.appearance.AppearanceStore
import cn.coldlake.stellar.opensource.appearance.rememberAppearanceStore
import kotlinx.coroutines.launch
import cn.coldlake.stellar.opensource.navigation.LibraryNavDisplay
import cn.coldlake.stellar.opensource.navigation.SettingsDestination
import cn.coldlake.stellar.opensource.navigation.rememberHorizontalNavigationMetadata

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
                        appearance, caches, customDraft ?: appearance.state.customPalette.encode(),
                        onCustomDraft = { customDraft = it })
                } else if (page == SettingsPage.HOME) {
                    SettingsHome(onNavigate, language, { onNavigateRoute("language") })
                } else {
                    Column(Modifier.fillMaxSize()) {
                        SettingsToolbar(page, navigateBack, onNavigate)
                        when (page) {
                            SettingsPage.ABOUT -> AboutPage(
                                onDeviceInfo = { onNavigate(SettingsPage.DEVICE_INFO) },
                                onLicenses = { onNavigate(SettingsPage.OPEN_SOURCE) },
                                onVersion = { onNavigateRoute("version") })
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
                                        else if (spec.id in cacheSettingIds) caches?.let { CacheSettingsRow(spec, it, onNavigateRoute) }
                                        else RuntimeToggleRow(spec, pipEnabled, pipSupported, onPip,
                                            hdrEnabled, hdrSupport, onHdr, ignoreCertificateErrors,
                                            onIgnoreCertificateErrors = { security.ignoreCertificateErrors = it },
                                            accurateSeek = accurateSeek, onAccurateSeek = { seekSettings.accurateSeek = it })
                                    }
                                }
                                if (page == SettingsPage.SUBTITLE) SubtitleAppearanceRows(onNavigateRoute)
                                if (page == SettingsPage.LIBRARY) LibraryScanSettingsRows(onNavigateRoute)
                                if(page==SettingsPage.LIBRARY && caches!=null) CacheUsageFooter(caches)
                                val note = when (page) {
                                    SettingsPage.ADVANCED_FEATURES -> R.string.hs_advanced_features_note
                                    SettingsPage.SUBTITLE -> R.string.settings_subtitle_note
                                    else -> null
                                }
                                note?.let { Text(stringResource(it), color = SettingsSecondary,
                                    fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(16.dp).testTag("settings-note")) }
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
        SettingsNavigationRow(R.string.hs_interaction, R.drawable.material_symbol_touch_app, "INTERACTION", groupIndex = 0, groupCount = 6, iconColor = SettingsIconColor.ORANGE) { onNavigate(SettingsPage.INTERACTION) }
        SettingsNavigationRow(R.string.hs_player, R.drawable.material_symbol_play_circle, "PLAYER", groupIndex = 1, groupCount = 6, iconColor = SettingsIconColor.BLUE) { onNavigate(SettingsPage.PLAYER) }
        SettingsNavigationRow(R.string.playback_cache_title, R.drawable.material_symbol_hard_drive, "PLAYBACK_CACHE", groupIndex = 2, groupCount = 6, iconColor = SettingsIconColor.GREEN) { onNavigate(SettingsPage.PLAYBACK_CACHE) }
        SettingsNavigationRow(R.string.settings_subtitle, R.drawable.material_symbol_subtitles, "SUBTITLE", groupIndex = 3, groupCount = 6, iconColor = SettingsIconColor.PURPLE) { onNavigate(SettingsPage.SUBTITLE) }
        SettingsNavigationRow(R.string.screenshot_title, com.stellar.player.ui.R.drawable.hills_photo_camera, "SCREENSHOT", groupIndex = 4, groupCount = 6, iconColor = SettingsIconColor.ORANGE) { onNavigate(SettingsPage.SCREENSHOT) }
        SettingsNavigationRow(R.string.hs_advanced_features, R.drawable.material_symbol_science, "ADVANCED_FEATURES", groupIndex = 5, groupCount = 6, iconColor = SettingsIconColor.CYAN) { onNavigate(SettingsPage.ADVANCED_FEATURES) }
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

@Composable
private fun RuntimeToggleRow(spec: SettingSpec,
    pipEnabled: Boolean, pipSupported: Boolean, onPip: (Boolean) -> Unit,
    hdrEnabled: Boolean, hdrSupport: WindowHdrSupport, onHdr: (Boolean) -> Unit,
    ignoreCertificateErrors: Boolean, onIgnoreCertificateErrors: (Boolean) -> Unit,
    accurateSeek: Boolean, onAccurateSeek: (Boolean) -> Unit) {
    val subtitle = if (spec.kind == SettingKind.WindowHdr) stringResource(when (hdrSupport) {
        WindowHdrSupport.SUPPORTED -> R.string.hs_force_hdr_hint
        WindowHdrSupport.ANDROID_VERSION -> R.string.hs_force_hdr_android
        WindowHdrSupport.DISPLAY_UNSUPPORTED -> R.string.hs_force_hdr_display
    }) else spec.subtitle?.let { stringResource(it) }
    val checked = when (spec.kind) {
        SettingKind.Pip -> pipEnabled
        SettingKind.WebDavTls -> ignoreCertificateErrors
        SettingKind.WindowHdr -> hdrEnabled
        SettingKind.AccurateSeek -> accurateSeek
        else -> error("Missing runtime setting: ${spec.id}")
    }
    val enabled = when (spec.kind) {
        SettingKind.Pip -> pipSupported
        SettingKind.WindowHdr -> hdrEnabled || hdrSupport == WindowHdrSupport.SUPPORTED
        else -> true
    }
    val change: (Boolean) -> Unit = when (spec.kind) {
        SettingKind.Pip -> onPip
        SettingKind.WebDavTls -> onIgnoreCertificateErrors
        SettingKind.WindowHdr -> onHdr
        SettingKind.AccurateSeek -> onAccurateSeek
        else -> error("Missing runtime setting: ${spec.id}")
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().heightIn(min = 72.dp)
        .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .testTag(if (spec.kind == SettingKind.Pip) "pip-setting" else "setting-${spec.id}")
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
        .padding(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RowText(spec.title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
