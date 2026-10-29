package cn.coldlake.stellar.opensource

import cn.coldlake.stellar.opensource.platform.FullscreenDeviceStatus

import android.content.Context
import android.app.PictureInPictureUiState
import android.os.Build
import android.graphics.Rect
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView
import com.stellar.player.mpv.MpvSurfaceView
import androidx.compose.ui.platform.LocalContext
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stellar.player.contract.*
import cn.coldlake.stellar.opensource.platform.*
import cn.coldlake.stellar.opensource.presentation.*
import cn.coldlake.stellar.opensource.settings.SettingsPage
import cn.coldlake.stellar.opensource.settings.SettingsScreen
import cn.coldlake.stellar.opensource.appearance.*
import cn.coldlake.stellar.opensource.medialibrary.MediaLibraryScreen
import cn.coldlake.stellar.opensource.medialibrary.servers.ServersViewModel
import cn.coldlake.stellar.opensource.medialibrary.library.localLibraryPath
import androidx.compose.ui.platform.LocalConfiguration
import com.stellar.player.ui.*
import com.stellar.player.ui.R as PlayerR

class MainActivity : androidx.activity.ComponentActivity() {
    private lateinit var rotationMonitor: PlayerRotationMonitor
    private var playerFullscreen by mutableStateOf(false)
    private lateinit var playbackConfiguration: Configuration
    val playerViewModel: PlayerViewModel by viewModels {
        viewModelFactory { initializer {
            val security = MediaSecuritySettings(applicationContext)
            PlayerViewModel(createSavedStateHandle(),
                controlsPreferences = applicationContext.getSharedPreferences("playback_controls", Context.MODE_PRIVATE),
                ignoreCertificateErrors = { security.ignoreCertificateErrors }, appContext = applicationContext)
        } }
    }
    internal val serversViewModel: ServersViewModel by viewModels()
    lateinit var pictureInPicture: PlayerPictureInPicture
        private set
    internal lateinit var windowHdr: PlayerWindowHdr
        private set
    private lateinit var deviceControls: PlayerDeviceControls
    internal lateinit var screenshots: PlayerScreenshots
        private set
    internal lateinit var animatedCapture: PlayerAnimatedCapture
        private set
    internal lateinit var subtitles: cn.coldlake.stellar.opensource.platform.PlayerSubtitles
        private set
    private var externalOpenJob: Job? = null
    private var externalOpenPending = false
    private val chooseMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        openPickedMedia(uris)
    }
    internal fun openPickedMedia(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        externalOpenJob?.cancel(); externalOpenPending = false
        externalOpenJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { uris.map { uri ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                readExternalMedia(applicationContext, Intent(Intent.ACTION_VIEW, uri).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            } } }
            lifecycle.withStarted {
                result.fold(onSuccess = { playerViewModel.openLocal(it) }, onFailure = {
                    android.widget.Toast.makeText(this@MainActivity,
                        if (it is UnsupportedExternalMedia) R.string.external_media_unsupported else R.string.external_media_unreadable,
                        android.widget.Toast.LENGTH_LONG).show()
                })
            }
        }
    }
    private val chooseDiscFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            externalOpenJob?.cancel(); externalOpenPending = false
            playerViewModel.openDiscFolder(this, uri)
        }
    }
    private val chooseLibraryFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            var acquired = false
            serversViewModel.library.add(onNotAdded = {
                if (acquired) runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }) {
                val alreadyGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                acquired = !alreadyGranted
                localLibraryPath(this, uri)
            }
        }
    }
    internal fun requestLibraryFolder(initial: android.net.Uri? = null) { chooseLibraryFolder.launch(initial) }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LanguagePreference.wrap(newBase)) }
    fun selectLanguage(language: AppLanguage) {
        if (LanguagePreference.read(this) == language) return
        playerViewModel.configurationChanged()
        LanguagePreference.save(this, language)
        recreate()
    }
    fun requestFullscreen(fullscreen: Boolean) {
        playerViewModel.configurationChanged()
        playerFullscreen = fullscreen
        updateFullscreenOrientation(resources.configuration)
    }
    private fun updateFullscreenOrientation(configuration: Configuration) {
        requestedOrientation = when {
            configuration.smallestScreenWidthDp >= 600 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerFullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackConfiguration = Configuration(resources.configuration)
        playerFullscreen = savedInstanceState?.getBoolean("playerFullscreen")
            ?: (playbackConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        playerViewModel.attachCatalog(this)
        pictureInPicture = PlayerPictureInPicture(this, playerViewModel)
        screenshots = PlayerScreenshots(this)
        subtitles = cn.coldlake.stellar.opensource.platform.PlayerSubtitles(this, savedInstanceState)
        animatedCapture = PlayerAnimatedCapture(this)
        windowHdr = PlayerWindowHdr(this, playerViewModel)
        PlayerScreenAwake(this, playerViewModel)
        deviceControls = PlayerDeviceControls(this, playerViewModel)
        rotationMonitor = PlayerRotationMonitor(this, playerViewModel::configurationChanged)
        // Saved playback restores itself. Only retry an incoming URI whose IO was still pending.
        if (savedInstanceState == null || savedInstanceState.getBoolean("externalOpenPending")) receiveSystemIntent(intent)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val appearance = rememberAppearanceStore(this@MainActivity)
            AppearanceTheme(appearance) {
                val reporting = remember { cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.get(this@MainActivity) }
                cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingDialogs(reporting)
                val ui by playerViewModel.ui.collectAsStateWithLifecycle()
                val navigation = playerViewModel.navigation
                val route = navigation.current
                val homeTab = navigation.homeTab
                val configuration = LocalConfiguration.current
                val playbackColors = remember(appearance.state.dynamic, appearance.state.palette, appearance.state.customPalette, configuration) {
                    if (appearance.state.dynamic && Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(this@MainActivity)
                    else appearanceColorScheme(appearance.state, true)
                }
                val remotePlayback by remember(playerViewModel) { playerViewModel.snapshot.map { it.remotePlayback }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
                val darkBars = LocalAppearanceDark.current || (route != AppRoute.LIBRARY && !remotePlayback)
                var libraryDestination by remember { mutableStateOf<cn.coldlake.stellar.opensource.navigation.LibraryDestination>(cn.coldlake.stellar.opensource.navigation.LibraryDestination.Home) }
                val libraryStatusBar = remember { mutableStateOf<cn.coldlake.stellar.opensource.medialibrary.MediaStatusBarStyle?>(null) }
                val embyStatusBar = remember { mutableStateOf<cn.coldlake.stellar.opensource.medialibrary.emby.EmbyStatusBarStyle?>(null) }
                val statusBarEntry = (serversViewModel.navigation.current as? cn.coldlake.stellar.opensource.navigation.MediaDestination.Emby)
                    ?.entry?.takeIf { route == AppRoute.LIBRARY && homeTab == HomeTab.MEDIA_LIBRARY }
                // enableEdgeToEdge re-applies its fixed dark style on every configuration change
                // (fold, rotation, resize); restart afterwards so light themes keep dark icons.
                LaunchedEffect(darkBars, statusBarEntry, libraryDestination, route, homeTab, configuration) {
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightNavigationBars = !darkBars
                    snapshotFlow {
                        val local = libraryStatusBar.value?.takeIf { route == AppRoute.LIBRARY && homeTab == HomeTab.MEDIA && it.pageKey == libraryDestination }
                        val server = embyStatusBar.value?.takeIf { statusBarEntry != null && it.pageKey == statusBarEntry }
                        (local ?: server)?.darkIcons ?: !darkBars
                    }
                        .distinctUntilChanged().collect { bars.isAppearanceLightStatusBars = it }
                }
            PlayerTheme(colorScheme = if (route == AppRoute.LIBRARY || remotePlayback) MaterialTheme.colorScheme else playbackColors) {
                val timeline = playerViewModel.snapshot.collectAsStateWithLifecycle()
                val snapshot by rememberPlaybackChrome(timeline)
                val panelState = playerViewModel.panels.collectAsStateWithLifecycle()
                val panels by rememberPanelChrome(panelState)
                val pip = pictureInPicture.active
                val hideControls = pictureInPicture.hideControls
                LaunchedEffect(remotePlayback) {
                    if (remotePlayback) {
                        playerViewModel.interactions.panel(false)
                        requestFullscreen(false)
                    }
                }
                val panelActions = remember(playerViewModel) {
                    object : PlayerPanelActions by playerViewModel {
                        override fun loadLocalSubtitle() { subtitles.local() }
                        override fun loadUrlSubtitle() { subtitles.remote() }
                        override fun showPanel(panel: CorePanel) {
                            if (panel == CorePanel.SNAPSHOT) screenshots.capture() else playerViewModel.showPanel(panel)
                        }
                        override fun tool(event: ToolEvent) {
                            if (event == ToolEvent.Rotate) { playerViewModel.dismissPanel(); requestFullscreen(false) }
                            else if (event == ToolEvent.Mini && !playerViewModel.snapshot.value.simulated) pictureInPicture.enter()
                            else playerViewModel.tool(event)
                        }
                    }
                }
                subtitles.Dialogs()
                playerViewModel.casting?.let { cn.coldlake.stellar.opensource.casting.CastingDialog(it) { playerViewModel.interactions.panel(false) } }
                val libraryScroll = rememberScrollState()
                val settingsStack = androidx.navigation3.runtime.rememberNavBackStack(
                    cn.coldlake.stellar.opensource.navigation.SettingsDestination(SettingsPage.HOME.name))
                val settingsPath = settingsStack.map { (it as cn.coldlake.stellar.opensource.navigation.SettingsDestination).route }
                var libraryMetadataOpen by rememberSaveable { mutableStateOf(false) }
                val settingsPage = settingsPath.last()
                val embyOpen = serversViewModel.navigation.current is cn.coldlake.stellar.opensource.navigation.MediaDestination.Emby
                val dvdLoading by playerViewModel.dvdLoading.collectAsStateWithLifecycle()
                if (dvdLoading) AlertDialog(onDismissRequest = playerViewModel::cancelDvdOpen,
                    text = { Column { Text(stringResource(R.string.dvd_opening)); LinearWavyProgressIndicator(Modifier.fillMaxWidth()) } },
                    confirmButton = { TextButton(onClick = playerViewModel::cancelDvdOpen) { Text(stringResource(R.string.media_library_cancel)) } })
                Surface(color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val landscape = maxWidth > maxHeight
                        val fullscreen = playerFullscreen && route == AppRoute.PLAYER && !remotePlayback
                        DisposableEffect(fullscreen) {
                            val controller = WindowCompat.getInsetsController(window, window.decorView)
                            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
                            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
                        }
                        LaunchedEffect(landscape) { playerViewModel.configurationChanged() }
                        val back = {
                            when {
                                panels.active != null -> playerViewModel.panelBack()
                                ui.feature != null -> playerViewModel.closeFeature()
                                fullscreen -> requestFullscreen(false)
                                else -> playerViewModel.back()
                            }
                        }
                        // Root fallback only. Child NavDisplays handle their own page history.
                        BackHandler(!hideControls && route == AppRoute.LIBRARY &&
                            (homeTab != HomeTab.MEDIA || panels.tools.mini || remotePlayback)) {
                            when {
                                panels.tools.mini -> playerViewModel.closeMini()
                                homeTab != HomeTab.MEDIA -> playerViewModel.selectHomeTab(HomeTab.MEDIA)
                                remotePlayback -> moveTaskToBack(true)
                            }
                        }
                        val localBack = panels.active != null || ui.feature != null || fullscreen
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                            // The video host is a stable sibling of NavDisplay. No scene animation,
                            // predictive transform or entry lifecycle owns either native Surface.
                            if (AppRoute.PLAYER in navigation.backStack && !remotePlayback) {
                                cn.coldlake.stellar.opensource.navigation.PageInput(active = route == AppRoute.PLAYER,
                                    modifier = if (fullscreen || pip) Modifier else Modifier.safeDrawingPadding()) {
                                    PlaybackScreen(snapshot, ui, playerViewModel, fullscreen, back,
                                        { requestFullscreen(!fullscreen) }, { feature ->
                                            if (feature == PlayerFeature.SNAPSHOT) screenshots.capture() else playerViewModel.feature(feature)
                                        }, pip, hideControls, pictureInPicture::sourceBounds, timeline,
                                        pictureInPicture.supported && pictureInPicture.enabled, { pictureInPicture.enter() },
                                        panelActions, screenshots, animatedCapture)
                                }
                            }
                            cn.coldlake.stellar.opensource.navigation.LibraryNavDisplay(navigation.backStack,
                                Modifier.fillMaxSize(), onBack = playerViewModel::back,
                                navigationEnabled = !hideControls && !localBack) { destination ->
                            // Each entry keeps its own insets even while previewed beneath playback.
                            Box(Modifier.fillMaxSize().then(if (
                                destination == AppRoute.LIBRARY ||
                                (destination == AppRoute.PLAYER && !remotePlayback)) Modifier else Modifier.safeDrawingPadding())
                                .then(if (destination == AppRoute.PLAYER) Modifier else Modifier.background(MaterialTheme.colorScheme.background))) {
                            when (destination) {
                                AppRoute.LIBRARY -> {
                                    AppearanceBackground(appearance, true)
                                    HomeNavigation(homeTab, playerViewModel::selectHomeTab,
                                        floating = appearance.state.floatingNavigation,
                                        edgeToEdgeTabs = if (embyOpen) setOf(HomeTab.MEDIA, HomeTab.MEDIA_LIBRARY) else setOf(HomeTab.MEDIA),
                                        hideNavigationLabels = appearance.state.hideNavigationLabels,
                                        playbackControls = {
                                            if (remotePlayback) playerViewModel.casting?.let { controller ->
                                                cn.coldlake.stellar.opensource.casting.CastMiniControls(controller, playerViewModel::showCastingControls)
                                            }
                                        },
                                        showBottomBar = (homeTab != HomeTab.SETTINGS || settingsPage == SettingsPage.HOME.name) &&
                                            (homeTab != HomeTab.MEDIA || !libraryMetadataOpen) &&
                                            (homeTab != HomeTab.MEDIA_LIBRARY || (serversViewModel.navigation.current == cn.coldlake.stellar.opensource.navigation.MediaDestination.Home && !embyOpen))) { homeTab ->
                                        when (homeTab) {
                                            HomeTab.MEDIA -> CompositionLocalProvider(cn.coldlake.stellar.opensource.medialibrary.LocalMediaStatusBar provides libraryStatusBar) {
                                            cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryScreen(
                                                onAdd = { chooseLibraryFolder.launch(null) },
                                                onPlay = { card -> playerViewModel.openCatalog(this@MainActivity, card) },
                                                onPlayAt = { card, position -> playerViewModel.openCatalog(this@MainActivity, card, position) },
                                                onMetadataOpen = { libraryMetadataOpen = it },
                                                onDestination = { libraryDestination = it }
                                            )
                                            }
                                            HomeTab.MEDIA_LIBRARY -> CompositionLocalProvider(
                                                cn.coldlake.stellar.opensource.medialibrary.emby.LocalEmbyStatusBar provides embyStatusBar) { MediaLibraryScreen(
                                                onLocalMedia = { chooseMedia.launch(arrayOf("video/*", "audio/*", "application/x-iso9660-image", "application/octet-stream")) },
                                                onNetworkMedia = { playerViewModel.openNetwork(it) },
                                                onDiscFolder = { chooseDiscFolder.launch(null) },
                                                onAddFolder = { chooseLibraryFolder.launch(null) },
                                                library = serversViewModel.library, servers = serversViewModel.controller, browser = serversViewModel.browser,
                                                emby = serversViewModel.emby, onEmbyMedia = playerViewModel::openEmby,
                                                onRemoteMedia = { profile, entry -> playerViewModel.openRemote(this@MainActivity, profile, entry) }) }
                                            HomeTab.SETTINGS -> SettingsScreen(settingsPage,
                                                navigationPath = settingsPath,
                                                onNavigateRoute = { if (settingsPath.last() != it) settingsStack.add(cn.coldlake.stellar.opensource.navigation.SettingsDestination(it)) },
                                                onBack = { if (settingsStack.size > 1) settingsStack.removeAt(settingsStack.lastIndex) },
                                                language = LanguagePreference.read(this@MainActivity), onLanguage = ::selectLanguage,
                                                pipEnabled = pictureInPicture.enabled, pipSupported = pictureInPicture.supported,
                                                onPip = pictureInPicture::updateEnabled,
                                                hdrEnabled = windowHdr.enabled, hdrSupport = windowHdr.support,
                                                onHdr = windowHdr::updateEnabled, appearance = appearance,

                                                onScreenshotFolder = screenshots::chooseFolder)
                                        }
                                        if (panels.tools.mini) MiniProgress(timeline, playerViewModel, Modifier.align(Alignment.BottomEnd))
                                    }
                                }
                                AppRoute.PLAYER -> if (remotePlayback) {
                                    playerViewModel.casting?.let { controller ->
                                        cn.coldlake.stellar.opensource.casting.CastRemoteScreen(controller, playerViewModel::back, subtitles::local, subtitles::remote,
                                            subtitles.busy, screenshots.playerMessage(snapshot.sessionId))
                                    }
                                }

                            }
                            }
                            }
                            }
                            androidx.compose.animation.AnimatedVisibility(remotePlayback && route != AppRoute.PLAYER && route != AppRoute.LIBRARY,
                                enter = androidx.compose.animation.fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                    androidx.compose.animation.expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                                exit = androidx.compose.animation.fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                    androidx.compose.animation.shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                                playerViewModel.casting?.let { controller ->
                                    cn.coldlake.stellar.opensource.casting.CastMiniControls(controller, playerViewModel::showCastingControls)
                                }
                            }
                            }
                        }
                        // Only transient player UI bypasses page navigation. Cancelling a system
                        // gesture never invokes this callback; a commit consumes exactly one level.
                        BackHandler(!hideControls && localBack, onBack = back)
                        if (route == AppRoute.LIBRARY) ScreenshotFeedback(screenshots.settingsMessage(), Modifier.align(Alignment.Center))
                        if (!hideControls) {
                        ui.feature?.let { feature -> FeatureNotice(feature, playerViewModel::closeFeature) }
                        ActivePlayerPanels(timeline, panelState, panelActions, emptyList(), fullscreen) { panelWindow ->
                            val controller = WindowCompat.getInsetsController(panelWindow, panelWindow.decorView)
                            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                        }
                    }
                }
            }
        }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep a pending file's URI/grant for Activity recreation. A launcher or
        // notification intent is navigation only and must not replace that request.
        if (intent.action == Intent.ACTION_VIEW) setIntent(intent)
        receiveSystemIntent(intent)
    }
    private fun receiveSystemIntent(intent: Intent) {
        when (intent.action) {
            cn.coldlake.stellar.opensource.casting.CastingService.OPEN -> playerViewModel.showCastingControls()
            Intent.ACTION_VIEW -> receiveExternalMedia(intent)
        }
    }
    private fun receiveExternalMedia(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        externalOpenJob?.cancel()
        externalOpenPending = true
        externalOpenJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readExternalMedia(applicationContext, intent) } }
            // A slow provider must not start background playback after the user leaves the app.
            lifecycle.withStarted {
                externalOpenPending = false
                result.fold(onSuccess = { playerViewModel.openExternalLocal(it) }, onFailure = {
                    android.widget.Toast.makeText(this@MainActivity,
                        if (it is UnsupportedExternalMedia) R.string.external_media_unsupported else R.string.external_media_unreadable,
                        android.widget.Toast.LENGTH_LONG).show()
                })
            }
        }
    }
    override fun onStart() {
        super.onStart(); playerViewModel.foreground(true)
        lifecycleScope.launch {
            val runtime = cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime.get(applicationContext)
            runtime.awaitReady()
            val pending = runtime.metadata.resumeJob()
            if (pending != null && !runtime.status.value.running && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                cn.coldlake.stellar.opensource.medialibrary.catalog.LibraryScanService.identify(this@MainActivity, pending.locale, resume = true)
        }
    }
    override fun onResume() { super.onResume(); pictureInPicture.onResume() }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); pictureInPicture.onUserLeaveHint() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.onModeChanged(isInPictureInPictureMode)
        deviceControls.pictureInPicture(isInPictureInPictureMode)
    }
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) { pictureInPicture.onTransitionStarted(); deviceControls.pictureInPicture(true) }
    }
    override fun onDestroy() { subtitles.close(); animatedCapture.close(); screenshots.close(); rotationMonitor.close(); deviceControls.close(); pictureInPicture.close(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!pictureInPicture.hideControls && !isInPictureInPictureMode) {
            val wasCompact = playbackConfiguration.smallestScreenWidthDp < 600
            val compact = newConfig.smallestScreenWidthDp < 600
            if (wasCompact != compact) {
                // Folding/resizing keeps the playback mode; only the orientation policy changes.
                updateFullscreenOrientation(newConfig)
            } else if (compact && playbackConfiguration.orientation != newConfig.orientation) {
                // Retain the phone's existing rotate-to-fullscreen behavior.
                playerFullscreen = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            playbackConfiguration = Configuration(newConfig)
        }
        // Compose observes updated window/theme configuration. Retain the independent
        // video Surface across rotation and system night-mode changes.
        playerViewModel.configurationChanged()
    }
    override fun onPause() {
        playerViewModel.saveCommittedState()
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("playerFullscreen", playerFullscreen)
        outState.putBoolean("externalOpenPending", externalOpenPending)
        subtitles.saveState(outState)
        serversViewModel.emby.saveNavigation()
        playerViewModel.saveCommittedState()
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        animatedCapture.background()
        if (!isChangingConfigurations && !screenshots.awaitingCapturePermission && !subtitles.pickerActive) playerViewModel.foreground(false)
        super.onStop()
    }
}





@Composable
private fun PlaybackScreen(snapshot: PlaybackSnapshot, ui: PlayerUiState, vm: PlayerViewModel, fullscreen: Boolean,
    onBack: () -> Unit, onFullscreen: () -> Unit, onFeature: (PlayerFeature) -> Unit,
    pip: Boolean, hideControls: Boolean, onVideoBounds: (Rect) -> Unit, timeline: State<PlaybackSnapshot>, pipAvailable: Boolean,
    onPip: () -> Unit, panelActions: PlayerPanelActions, screenshots: PlayerScreenshots, animatedCapture: PlayerAnimatedCapture) {
    val panelState = vm.panels.collectAsStateWithLifecycle()
    val panels by rememberPanelChrome(panelState)
    val liveInteraction = vm.interactions.state.collectAsStateWithLifecycle()
    val interaction by rememberInteractionChrome(liveInteraction)
    // Pass the holder without reading its value. Only the seek Canvas observes buffer changes.
    val buffering = vm.buffering.collectAsStateWithLifecycle()
    val discNavigation by vm.discNavigation.collectAsStateWithLifecycle()
    val castState = vm.casting?.state?.collectAsStateWithLifecycle()?.value
    val fallbackContext = LocalContext.current

    val portraitState = rememberSaveableStateHolder()
    val portraitActions = remember(vm, panelActions) {
        object : PlayerPanelActions by panelActions {
            override fun captureAnimation() { animatedCapture.open() }
            override fun showPanel(panel: CorePanel) {
                if (panel == CorePanel.SNAPSHOT) panelActions.showPanel(panel) else vm.showPortraitSubpanel(panel)
            }
        }
    }
    LaunchedEffect(fullscreen) { vm.interactions.fullscreen(fullscreen) }
    LaunchedEffect(fullscreen, pip, snapshot.sessionId) {
        if (fullscreen || pip || (animatedCapture.dialogSession != null && animatedCapture.dialogSession != snapshot.sessionId))
            animatedCapture.cancel(automatic = true)
    }
    AnimatedCaptureDialog(animatedCapture, snapshot)
    Column(Modifier.fillMaxSize().testTag("playback")) {
        PlayerSurface(snapshot, fullscreen, vm::command, onBack, onFullscreen, { vm.adjacent(-1) }, { vm.adjacent(1) },
            hasPrevious = panels.queue.size > 1, hasNext = panels.queue.size > 1, onFeature = onFeature,
            previousEnabled = !panels.queuePreparing && (!panels.episodeQueue || panels.queue.indexOfFirst { it.id == snapshot.media?.id } > 0),
            nextEnabled = !panels.queuePreparing && (!panels.episodeQueue || panels.queue.indexOfFirst { it.id == snapshot.media?.id } in 0 until panels.queue.lastIndex),
            interaction = interaction, interactions = vm.interactions,
            modifier = (if (fullscreen || pip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    onVideoBounds(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
                },
            frameContent = {
                        AndroidView(factory = { MpvSurfaceView(it, vm.mpv).also { view -> screenshots.surface = view } }, modifier = Modifier.fillMaxSize().testTag("mpv-surface"),
                            onRelease = { if (screenshots.surface === it) screenshots.surface = null; it.release() }, update = {})
                    NativeTransform(vm, panels.tools, liveInteraction, snapshot.sessionId)
                    if (snapshot.media?.kind == MediaKind.AUDIO) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(painterResource(PlayerR.drawable.icon_music_bg), null, Modifier.size(96.dp))
                    }
            }, overlayContent = {
                if (castState?.device != null) Box(Modifier.matchParentSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.casting_on_device, castState.device.name), color = Color.White,
                        modifier = Modifier.clickable { vm.casting?.openPanel() }.padding(24.dp).testTag("casting-active"))
                }

                SeekFailureFeedback(snapshot.sessionId,
                    snapshot.seekResult?.takeIf { snapshot.pendingSeek == null && !it.succeeded }?.id,
                    Modifier.align(Alignment.Center))
                PlaybackStatusOverlay(snapshot, {
                    if (snapshot.status == PlaybackStatus.ENDED) vm.command(PlaybackCommand.Play(snapshot.sessionId)) else vm.retry()
                }, Modifier.align(Alignment.Center).fillMaxWidth(if (fullscreen) 0.55f else 0.95f))
                if (panels.tools.message == ToolMessage.TIMER_EXPIRED) Text(stringResource(PlayerR.string.timer_expired),
                    fontSize = 12.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).background(PlayerColors.Panel).padding(12.dp).testTag("timer-expired"))
                ScreenshotFeedback(screenshots.playerMessage(snapshot.sessionId), Modifier.align(Alignment.Center))
            }, repeatMode = panels.repeat, tools = panels.tools, hideControls = hideControls || discNavigation.active, buffering = buffering, timeline = timeline, liveInteraction = liveInteraction,
            activePanel = panels.active, onPanel = vm::showPanel, onSpeed = { vm.setSpeed(it) },
            onPip = { if (snapshot.simulated) vm.tool(ToolEvent.Mini) else onPip() }, pipAvailable = snapshot.simulated || pipAvailable,
            fullscreenStatus = { FullscreenDeviceStatus(interaction.options) },
            rightHanded = cn.coldlake.stellar.opensource.settings.rememberRightHanded(),
            diagnosticsContent = {
                if (!pip && panels.tools.diagnostics) PlayerDiagnosticsHost(vm.diagnostics, snapshot.sessionId)
            },
            navigationContent = { if (!pip) DiscMenuOverlay(discNavigation, interaction.controlsVisible,
                vm::openDiscMenu, vm::exitDiscMenu, onBack, onFullscreen, vm::discMenuInput, fullscreen, (snapshot.status == PlaybackStatus.PLAYING),
                { vm.command(if((snapshot.status == PlaybackStatus.PLAYING)) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId)) }) })
        if(discNavigation.active && !fullscreen && !pip) DiscMenuControls(vm::exitDiscMenu,onBack,onFullscreen,vm::discMenuInput,(snapshot.status == PlaybackStatus.PLAYING),
            { vm.command(if((snapshot.status == PlaybackStatus.PLAYING)) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId)) })
        if (animatedCapture.busy && !pip) AnimatedCaptureProgress(animatedCapture)
        if (!fullscreen && !hideControls) portraitState.SaveableStateProvider("portrait-details") {
            PortraitPlayerDetails(snapshot, panels, portraitActions, Modifier.weight(1f).fillMaxWidth(),
                moreContent = {
                    PortraitMoreTools(snapshot, panelState, portraitActions)
                }) {
                if (snapshot.simulated) Text(stringResource(R.string.local_sample), color = PlayerColors.Secondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                ui.notice?.let { Text(rejectionLabel(it), color = Color(0xFFFFCF99)) }
                if (!snapshot.simulated && panels.tools.message == ToolMessage.UNAVAILABLE)
                    Text(stringResource(PlayerR.string.real_unavailable), color = PlayerColors.Secondary)
            }
        }
    }
}

@Composable
private fun ScreenshotFeedback(message: Int?, modifier: Modifier = Modifier) {
    if (message != null) Text(stringResource(message), color = Color.White, fontSize = 14.sp,
        modifier = modifier.widthIn(max = 280.dp).background(PlayerColors.Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("screenshot-feedback")
            .semantics { liveRegion = LiveRegionMode.Polite })
}





@Composable
private fun MiniPlayer(snapshot: PlaybackSnapshot, vm: PlayerViewModel, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp).widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(PlayerColors.Panel).border(1.dp, PlayerColors.Accent, RoundedCornerShape(12.dp))
        .pointerInput(Unit) { detectTapGestures {} }.testTag("mini-player")) {
        Box(Modifier.fillMaxWidth().height(116.dp).background(Color.Black))
        Text(stringResource(PlayerR.string.mini_hint), color = PlayerColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Row {
            PanelButton(stringResource(PlayerR.string.mini_restore), "mini-restore", vm::restoreMini, modifier = Modifier.weight(1f))
            PanelButton(stringResource(if (snapshot.status == PlaybackStatus.PLAYING) PlayerR.string.pause else PlayerR.string.play), "mini-toggle", {
                vm.command(if (snapshot.status == PlaybackStatus.PLAYING) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId))
            }, modifier = Modifier.weight(1f))
            PanelButton(stringResource(PlayerR.string.mini_close), "mini-close", vm::closeMini, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FeatureNotice(feature: PlayerFeature, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(PlayerColors.Panel).verticalScroll(rememberScrollState()).padding(24.dp).testTag("feature-notice")) {
            Text(stringResource(when (feature) {
                PlayerFeature.CAST -> PlayerR.string.cast
                PlayerFeature.SUBTITLES -> PlayerR.string.subtitles
                PlayerFeature.AUDIO -> PlayerR.string.audio_tracks
                PlayerFeature.MORE -> PlayerR.string.more
                PlayerFeature.REPEAT -> PlayerR.string.repeat
                PlayerFeature.PLAYLIST -> PlayerR.string.playlist
                PlayerFeature.SPEED -> PlayerR.string.speed
                PlayerFeature.LOCK -> PlayerR.string.lock
                PlayerFeature.SNAPSHOT -> PlayerR.string.snapshot
            }), fontSize = 20.sp)
            Text(stringResource(if (feature == PlayerFeature.CAST) R.string.cast_pending else R.string.feature_pending), color = PlayerColors.Secondary, modifier = Modifier.padding(vertical = 16.dp))
            NoticeButton(stringResource(R.string.close), onClose, Modifier.testTag("notice-close"))
        }
    }
}

@Composable
private fun NoticeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(modifier.padding(vertical = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) PlayerColors.Accent.copy(alpha = 0.22f) else Color(0xFF303042))
        .semantics { this.selected = selected; role = Role.Button }
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(text, fontSize = 14.sp, color = if (selected) Color(0xFF8AB9FF) else Color.White)
    }
}



@Composable
private fun MiniProgress(timeline: State<PlaybackSnapshot>, vm: PlayerViewModel, modifier: Modifier) {
    MiniPlayer(timeline.value, vm, modifier)
}

@Composable
private fun ActivePlayerPanels(timeline: State<PlaybackSnapshot>, livePanels: State<PlayerPanelState>, actions: PlayerPanelActions,
    samples: List<TrackDescriptor>, landscape: Boolean, configureWindow: (android.view.Window) -> Unit) {
    val active by remember(livePanels) { derivedStateOf { livePanels.value.active } }
    if (active == null) return
    val panels by remember(livePanels) { derivedStateOf {
        val p = livePanels.value
        p.copy(tools = p.tools.copy(remainingMs = p.tools.remainingMs?.let { it / 1000 * 1000 }))
    } }
    val snapshot by remember(timeline, panels.active) { derivedStateOf {
        val s = timeline.value
        val position = when (panels.active) {
            CorePanel.FRAME, CorePanel.SNAPSHOT -> s.positionMs
            CorePanel.SUBTITLE_DELAY, CorePanel.SHARE -> s.positionMs / 1000 * 1000
            else -> 0L
        }
        s.copy(positionMs = position)
    } }
    PlayerPanels(snapshot, panels, actions, samples, landscape,
        rightHanded = cn.coldlake.stellar.opensource.settings.rememberRightHanded(), configureWindow = configureWindow)
}

@Composable
private fun rememberPanelChrome(live: State<PlayerPanelState>): State<PlayerPanelState> =
    remember(live) { derivedStateOf { live.value.copy(tools = live.value.tools.copy(remainingMs = null)) } }

@Composable
private fun NativeTransform(vm: PlayerViewModel, tools: PlayerToolsState, live: State<PlayerInteractionState>, session: Long) {
    val scale by remember(live) { derivedStateOf { live.value.scale } }
    LaunchedEffect(tools.fit, tools.flipHorizontal, tools.flipVertical, scale, session) {
        vm.transform(tools.fit.ordinal, scale * if (tools.flipHorizontal) -1 else 1,
            scale * if (tools.flipVertical) -1 else 1)
    }
}
