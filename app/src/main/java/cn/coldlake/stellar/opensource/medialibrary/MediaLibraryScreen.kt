package cn.coldlake.stellar.opensource.medialibrary

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.stellar.player.ui.PageMotion
import com.stellar.player.ui.PageMotionContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.navigation.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.medialibrary.library.*
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.settings.SectionTitle
import cn.coldlake.stellar.opensource.settings.SettingsCategoryIcon
import cn.coldlake.stellar.opensource.settings.SettingsIconColor

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private enum class MediaLibraryTab(@StringRes val title: Int) {
    LOCAL(R.string.media_library_local_url), REMOTE(R.string.media_library_remote), MEDIA_SERVERS(R.string.media_library_media_servers)
}

@Composable
internal fun MediaLibraryScreen(onLocalMedia: () -> Unit, onNetworkMedia: (String) -> Unit,
                         onAddFolder: () -> Unit = {}, onDiscFolder: () -> Unit = {},
                         library: LibraryController = viewModel<ServersViewModel>().library,
                         servers: ServersController = viewModel<ServersViewModel>().controller,
                         browser: RemoteBrowserController = viewModel<ServersViewModel>().browser,
                         emby: EmbyBrowserController = viewModel<ServersViewModel>().emby,
                         navigation: MediaNavigation = servers.navigation,
                         onEmbyMedia: (EmbyPlayback) -> Unit = {},
                         onRemoteMedia: (ServerProfile, RemoteEntry) -> Unit = { _, _ -> }) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var driveOpening by rememberSaveable { mutableStateOf<String?>(null) }
    var driveOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var driveRevision by rememberSaveable { mutableLongStateOf(0L) }
    val driveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val id = driveOpening; driveOpening = null
        val origin = driveOrigin; driveOrigin = null
        val profile = servers.state.value.servers.firstOrNull { it.id == id && it.kind.cloud && it.updatedAt == driveRevision }
        if (result.resultCode == android.app.Activity.RESULT_OK && profile != null &&
            navigation.current.toString() == origin &&
            (if (profile.kind == ServerKind.ONEDRIVE) result.data?.getStringExtra("account_id") == profile.cloudAccountId
            else result.data?.getStringExtra("account").equals(profile.username, ignoreCase = true))) {
            if (navigation.current is MediaDestination.Remote) browser.refresh() else browser.open(profile)
        }
    }
    fun openDrive(profile: ServerProfile) {
        if (driveOpening != null) return
        driveOpening = profile.id; driveOrigin = navigation.current.toString(); driveRevision = profile.updatedAt
        val target = if (profile.kind == ServerKind.ONEDRIVE) cn.coldlake.stellar.opensource.medialibrary.onedrive.OneDriveAuthorizationActivity::class.java
            else cn.coldlake.stellar.opensource.medialibrary.drive.DriveAuthorizationActivity::class.java
        driveLauncher.launch(android.content.Intent(context, target)
            .putExtra("account", profile.username).putExtra("account_id", profile.cloudAccountId))
    }
    val stack = navigation.backStack.toList()
    val directoryMotion = rememberHorizontalNavigationMetadata(shortDistance = true)
    Box(Modifier.fillMaxSize()) {
        LibraryNavDisplay(stack, Modifier.fillMaxSize(), retainedRoots = (stack + emby.retainedRoots).distinct(),
            entryMetadata = { destination ->
                if (destination is MediaDestination.Remote ||
                    destination is MediaDestination.Emby && emby.isDirectoryDestination(destination)) directoryMotion
                else emptyMap()
            }, onBack = {
            when (navigation.current) {
                is MediaDestination.Form, MediaDestination.Storage, MediaDestination.MediaServerTypes -> servers.back()
                is MediaDestination.Remote -> browser.back()
                is MediaDestination.Emby -> emby.back()
                else -> navigation.pop()
            }
        }) { destination ->
            when (destination) {
                MediaDestination.Home -> MediaLibraryHome(onLocalMedia, onNetworkMedia, onAddFolder, onDiscFolder,
                    library, servers, navigation) { profile ->
                    if (profile.kind.mediaServer) emby.open(profile)
                    else if (profile.kind.cloud) openDrive(profile) else browser.open(profile)
                }
                MediaDestination.Settings -> MediaLibrarySettingsScreen(navigation::pop, { library.open(true) }, onAddFolder,
                    { navigation.push(MediaDestination.CatalogMetadata) })
                MediaDestination.CatalogMetadata -> {
                    val runtime = remember(context) { cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime.get(context) }
                    cn.coldlake.stellar.opensource.medialibrary.catalog.MetadataScreen(runtime, onBack = navigation::pop)
                }

                MediaDestination.Paths -> {
                    val state by library.state.collectAsStateWithLifecycle()
                    LibraryPathsScreen(state, library)
                }
                MediaDestination.Storage -> ServerTypeScreen(mediaServers = false, controller = servers)
                MediaDestination.MediaServerTypes -> ServerTypeScreen(mediaServers = true, controller = servers)
                is MediaDestination.Form -> ServerFormEntry(destination, servers)
                is MediaDestination.Remote -> {
                    val state by remember(browser, destination) { browser.page(destination) }.collectAsStateWithLifecycle()
                    val libraryState by library.state.collectAsStateWithLifecycle()
                    RemoteBrowserScreen(state, browser, onRemoteMedia, libraryState, library,
                        onReconnect = { state.profile?.let { openDrive(it) } })
                }
                is MediaDestination.Emby -> {
                    val state by remember(emby, destination) { emby.page(destination) }.collectAsStateWithLifecycle()
                    EmbyScreen(state, emby, servers::edit, onEmbyMedia)
                }
            }
        }
        LibraryNoticeHost(library)
    }
}

@Composable private fun LibraryNoticeHost(library: LibraryController) {
    val state by library.state.collectAsStateWithLifecycle()
    LibraryFeedback(state, library)
}

@Composable private fun ServerFormEntry(destination: MediaDestination.Form, servers: ServersController) {
    val state by servers.state.collectAsStateWithLifecycle()
    if (servers.formSession != destination.session || state.draft?.id != destination.profileId) return
    ServerForm(state, servers)
}

@Composable private fun MediaLibraryHome(onLocalMedia: () -> Unit, onNetworkMedia: (String) -> Unit,
    onAddFolder: () -> Unit, onDiscFolder: () -> Unit, library: LibraryController,
    servers: ServersController, navigation: MediaNavigation, onOpenServer: (ServerProfile) -> Unit) {
    var selectedName by rememberSaveable { mutableStateOf(MediaLibraryTab.LOCAL.name) }
    var showAddress by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var invalidAddress by rememberSaveable { mutableStateOf(false) }
    val selected = if (selectedName == "LIBRARY") MediaLibraryTab.MEDIA_SERVERS
        else MediaLibraryTab.entries.firstOrNull { it.name == selectedName } ?: MediaLibraryTab.LOCAL
    val libraryState by library.state.collectAsStateWithLifecycle()
    val serverState by remember(servers) {
        servers.state.map { it.copy(draft = null, busy = null, error = null) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(ServersState())
    Column(Modifier.fillMaxSize().testTag("home-media-library")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_media_library), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 28.sp, letterSpacing = 0.sp,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp).semantics { heading() })
            IconButton(onClick = {}, enabled = false, modifier = Modifier.size(48.dp).testTag("media-library-search"),
                colors = IconButtonDefaults.iconButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(painterResource(R.drawable.material_symbol_search), stringResource(R.string.media_library_search), Modifier.size(24.dp))
            }
            IconButton(onClick = { navigation.push(MediaDestination.Settings) }, modifier = Modifier.size(48.dp).testTag("media-library-settings")) {
                Icon(painterResource(R.drawable.material_symbol_settings), stringResource(R.string.media_library_settings), Modifier.size(24.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()) {
            MediaLibraryTab.entries.forEach { tab ->
                val active = selected == tab
                val color by animateColorAsState(if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant, label = "media-library-tab-color")
                Box(Modifier.heightIn(min = 48.dp).selectable(active, role = Role.Tab,
                    onClick = { selectedName = tab.name }).testTag("media-library-tab-${tab.name}")
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(tab.title), color = color, fontSize = 14.sp, lineHeight = 20.sp,
                            letterSpacing = 0.1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))
                            .background(if (active) color else Color.Transparent))
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
        val tabStates = rememberSaveableStateHolder()
        PageMotionContent(selected, Modifier.weight(1f).fillMaxWidth(),
            motion = { from, to -> if (to.ordinal > from.ordinal) PageMotion.LateralForward else PageMotion.LateralBackward }) { selected ->
        tabStates.SaveableStateProvider(selected.name) {
        if (selected == MediaLibraryTab.LOCAL) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)
                .testTag("media-library-local-content")) {
                SectionTitle(R.string.media_library_section_files)
                OpenMediaRow(R.string.media_library_open_local, R.drawable.material_symbol_folder,
                    SettingsIconColor.BLUE, "open-local-media", groupIndex = 0, groupCount = 2,
                    onClick = onLocalMedia)
                OpenMediaRow(R.string.disc_open_folder, R.drawable.material_symbol_album,
                    SettingsIconColor.PURPLE, "open-disc-folder", groupIndex = 1, groupCount = 2,
                    onClick = onDiscFolder)
                Spacer(Modifier.height(16.dp))
                SectionTitle(R.string.media_library_section_network)
                OpenMediaRow(R.string.media_library_open_url, R.drawable.material_symbol_language,
                    SettingsIconColor.CYAN, "open-network-media") { showAddress = true; invalidAddress = false }
                Spacer(Modifier.height(16.dp))
                SectionTitle(R.string.media_library_section_scan)
                OpenMediaRow(R.string.media_library_add_folder, R.drawable.material_symbol_document_scanner,
                    SettingsIconColor.GREEN, "add-local-library", enabled = libraryState.loaded && !libraryState.busy) {
                    if (libraryState.loadError) library.reload() else onAddFolder()
                }
            }
        } else ServerTab(selected == MediaLibraryTab.MEDIA_SERVERS, serverState, servers,
            onOpenServer)
        }
        }
    }
    if (showAddress) {
        val focus = remember { FocusRequester() }
        val dismiss = { showAddress = false; address = ""; invalidAddress = false }
        val submit = {
            val media = networkMedia(address)
            if (media == null) invalidAddress = true
            else { val url = address.trim(); dismiss(); onNetworkMedia(url) }
        }
        AlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("network-address-dialog"),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.media_library_open_url)) },
            text = {
                val windowFocused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(windowFocused) { if (windowFocused) focus.requestFocus() }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = address, onValueChange = { address = it; invalidAddress = false },
                        label = { Text(stringResource(R.string.media_library_url_label)) },
                        placeholder = { Text(stringResource(R.string.media_library_url_example), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingText = { Text(stringResource(if (invalidAddress) R.string.media_library_url_error else R.string.media_library_url_hint)) },
                        isError = invalidAddress, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                            autoCorrectEnabled = false), keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("network-address-input"))
                }
            },
            dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("network-address-cancel")) {
                Text(stringResource(R.string.media_library_cancel))
            } },
            confirmButton = { TextButton(onClick = { submit() }, enabled = address.isNotBlank(),
                modifier = Modifier.testTag("network-address-open")) { Text(stringResource(R.string.media_library_open)) } })
    }
}

@Composable
private fun OpenMediaRow(@StringRes label: Int, @DrawableRes icon: Int, iconColor: SettingsIconColor,
    tag: String, groupIndex: Int = 0, groupCount: Int = 1, enabled: Boolean = true, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick, enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(groupIndex, groupCount),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth().testTag(tag),
        leadingContent = { SettingsCategoryIcon(icon, iconColor, enabled = enabled) },
    ) { Text(stringResource(label), style = MaterialTheme.typography.bodyLargeEmphasized) }
}
