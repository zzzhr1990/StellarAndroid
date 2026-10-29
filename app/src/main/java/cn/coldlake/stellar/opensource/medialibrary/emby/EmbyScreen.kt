package cn.coldlake.stellar.opensource.medialibrary.emby

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.cache.cacheNamespace
import com.stellar.player.ui.LocalPageActive
import com.stellar.player.ui.PageMotion
import com.stellar.player.ui.PageMotionContent
import cn.coldlake.stellar.opensource.medialibrary.rememberPageList
import cn.coldlake.stellar.opensource.medialibrary.rememberPageGrid
import cn.coldlake.stellar.opensource.medialibrary.MediaDetailTopBar
import cn.coldlake.stellar.opensource.medialibrary.rememberFloatingHomeScrollConnection
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

private val CatalogSections = listOf(EmbySection.FOLDER, EmbySection.RESUME, EmbySection.FAVORITES,
    EmbySection.GENRES, EmbySection.TAGS, EmbySection.COLLECTIONS, EmbySection.DIRECTORIES)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmbyScreen(state: EmbyBrowserState, controller: EmbyBrowserController,
    onLogin: (ServerProfile) -> Unit, onPlay: (EmbyPlayback) -> Unit) {
    val active = LocalPageActive.current
    LaunchedEffect(state.pageKey, active) { if (active && state.profile != null) controller.screenVisible() }
    val headerState = rememberTopAppBarState()
    EmbyPage(state, controller, onLogin, onPlay, headerState)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbyPage(state: EmbyBrowserState, controller: EmbyBrowserController,
    onLogin: (ServerProfile) -> Unit, onPlay: (EmbyPlayback) -> Unit, headerState: TopAppBarState) {
    val profile = state.profile ?: return
    CompositionLocalProvider(LocalEmbyImageSession provides remember(profile) { profile.cacheNamespace() },
        LocalEmbyRows provides state.rows) {
    val root = state.detail == null && state.location.parent == null && state.location.section in
        setOf(EmbySection.LIBRARIES, EmbySection.FAVORITES, EmbySection.SEARCH)
    val immersive = state.detail != null || state.location.section == EmbySection.LIBRARIES
    val home = root && state.location.section == EmbySection.LIBRARIES
    // The home shelves need one viewport on each side: short direction changes
    // otherwise recreate an entire nested row in a single scroll frame.
    val immersiveList = if (immersive && !state.restoring) rememberPageList(state.scroll, if (home) 1f else .5f) else null
    val refresh = key(state.pageKey) { rememberPullToRefreshState() }
    // Keep this state while opening details and switching root tabs of the same server.
    val scrollConnection = rememberFloatingHomeScrollConnection(headerState,
        canScroll = { immersiveList?.let { it.canScrollForward || it.canScrollBackward } == true })
    val active = LocalPageActive.current
    LaunchedEffect(state.error, active) { if (active && state.error != null) headerState.heightOffset = 0f }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("emby-screen")
        .then(if (home) Modifier.nestedScroll(scrollConnection) else Modifier)) {
        val detailActions: @Composable RowScope.() -> Unit = {
                if (state.detail != null) {
                    if (state.detail.type != "Person") EmbyIcon(R.drawable.material_symbol_check_circle,
                        if (state.detail.played) R.string.emby_ui_mark_unplayed else R.string.emby_ui_mark_played,
                        "emby-watched", { controller.flag(false) }, !state.loading && !state.preparing,
                        if (state.detail.played) MaterialTheme.colorScheme.primary else LocalContentColor.current, outlined = true)
                    EmbyIcon(R.drawable.material_symbol_favorite,
                        if (state.detail.favorite) R.string.emby_ui_favorite_remove else R.string.emby_ui_favorite_add,
                        "emby-favorite", { controller.flag(true) }, !state.loading && !state.preparing,
                        if (state.detail.favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current, outlined = true)
                }
        }
        val notices: @Composable ColumnScope.() -> Unit = {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.loading || state.preparing || state.restoring) LinearProgressIndicator(Modifier.fillMaxSize().testTag("emby-loading"))
            }
            if (state.detail?.userStateKnown == false) Text(stringResource(R.string.cache_user_state_pending),
                Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)
            if (state.stale && state.cachedAt != null) Text(stringResource(R.string.cache_showing_saved,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(state.cachedAt))),
                Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("cache-stale"),style=MaterialTheme.typography.labelSmall)
            if (state.pagingUnstable) TextButton(onClick=controller::refresh) { Text(stringResource(R.string.cache_list_changed)) }
            if (state.error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp).testTag("emby-error")) {
                    Text(stringResource(if (state.error == ServerError.AUTHENTICATION) R.string.emby_auth_expired else state.error.message()))
                    TextButton(onClick = {
                        if (state.error == ServerError.AUTHENTICATION) { controller.close(); onLogin(profile) } else controller.refresh()
                    }, modifier = Modifier.testTag("emby-retry")) {
                        Text(stringResource(if (state.error == ServerError.AUTHENTICATION) R.string.emby_login_again else R.string.server_retry))
                    }
                }
            }
        }
        val toolbar: @Composable ColumnScope.() -> Unit = {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                EmbyIcon(R.drawable.material_symbol_arrow_back, R.string.server_back, "emby-back", controller::back, outlined = home)
                EmbyToolbarTitle(state.location.title.ifBlank { state.location.parent?.name ?: when {
                    root && state.location.section == EmbySection.FAVORITES -> stringResource(R.string.emby_ui_favorites)
                    root && state.location.section == EmbySection.SEARCH -> stringResource(R.string.emby_ui_search)
                    else -> profile.name } },
                    modifier = Modifier.weight(1f), outlined = home)
                if (root) EmbyRootActions(state.location.section, controller::switchRoot, outlined = home)
                EmbyIcon(R.drawable.material_symbol_refresh, R.string.emby_refresh, "emby-refresh", controller::refresh, !state.loading, outlined = home)
            }
            notices()
        }
        when {
            state.restoring -> Unit // Do not attach a saved deep index to a temporary short tree.
            state.detail != null -> key(state.pageKey) {
                EmbyDetails(state, controller, onPlay, checkNotNull(immersiveList)) { color ->
                    MediaDetailTopBar(immersiveList, color,
                        if (state.detail.type in setOf("Episode", "Season")) state.detail.seriesName.ifBlank { state.detail.name } else state.detail.name,
                        statusBarAppearance = { collapsed -> EmbyStatusBar(state.pageKey, overArtwork = !collapsed, bodyColor = color) },
                        logo = if (state.detail.logoTag.isNotEmpty()) { modifier ->
                            EmbyImage(state.detail, controller, modifier, "Logo", scale = androidx.compose.ui.layout.ContentScale.Fit,
                                alignment = Alignment.CenterStart)
                        } else null,
                        navigation = { EmbyIcon(R.drawable.material_symbol_arrow_back, R.string.server_back, "emby-back", controller::back, outlined = true) },
                        actionsWhenCollapsed = state.detail.type != "Person", actions = detailActions, notices = notices)
                }
            }
            state.location.section == EmbySection.LIBRARIES -> key(state.pageKey) { EmbyHome(state, controller, checkNotNull(immersiveList), refresh) }
            else -> {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp)) { EmbyCatalog(state, controller) }
            }
        }
        if (home) {
            EmbyHomeHeader(headerState, immersiveList, state.hero.isNotEmpty(), state.pageKey,
                Modifier.align(Alignment.TopCenter).testTag("emby-home-header"), content = toolbar)
            // Keep the whole circle visible even during the first few pixels of a pull.
            EmbyRefreshIndicator(state = refresh, isRefreshing = state.loading && state.contentReady,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().testTag("emby-pull-refresh"))
        } else if (state.detail == null || state.restoring) {
            cn.coldlake.stellar.opensource.medialibrary.MediaDetailToolbarBackground(immersiveList,
                Modifier.align(Alignment.TopCenter), content = toolbar)
        }
        // Transient refresh notices never participate in the scrolling layout.
        val notice = remember { SnackbarHostState() }
        val message = when {
            state.contentReady && state.refreshError != null -> stringResource(R.string.cache_refresh_failed)
            state.reportError != null -> stringResource(R.string.emby_progress_error)
            state.detail?.progressPending == true -> stringResource(R.string.playback_progress_pending)
            state.flagError != null -> stringResource(R.string.emby_ui_flag_error)
            state.auxiliaryError != null -> stringResource(R.string.emby_ui_partial_error)
            else -> null
        }
        val retry = stringResource(R.string.server_retry)
        LaunchedEffect(message, active) {
            if(active && message != null && notice.showSnackbar(message,retry,withDismissAction=true)==SnackbarResult.ActionPerformed) controller.refresh()
        }
        SnackbarHost(notice,Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(bottom=12.dp).testTag("emby-refresh-notice"))
    }
    }
}

@Composable
private fun EmbyRootActions(selected: EmbySection, onSelect: (EmbySection) -> Unit, outlined: Boolean) {
    // Preserve the existing three root checkpoints; only their entry points move.
    if (selected != EmbySection.LIBRARIES) EmbyIcon(R.drawable.material_symbol_home,
        R.string.emby_ui_home, "emby-section-LIBRARIES", { onSelect(EmbySection.LIBRARIES) })
    EmbyIcon(R.drawable.material_symbol_favorite, R.string.emby_ui_favorites, "emby-section-FAVORITES",
        { onSelect(EmbySection.FAVORITES) }, outlined = outlined, selected = selected == EmbySection.FAVORITES)
    EmbyIcon(R.drawable.material_symbol_search, R.string.emby_ui_search, "emby-section-SEARCH",
        { onSelect(EmbySection.SEARCH) }, outlined = outlined, selected = selected == EmbySection.SEARCH)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EmbyHome(state: EmbyBrowserState, controller: EmbyBrowserController, scroll: LazyListState,
    refresh: PullToRefreshState) {
    val active = LocalPageActive.current
    // Resolve an empty hero only at the top, at rest. A delayed response must not remove
    // a screen-height slot above the finger during a drag/fling or a background refresh.
    var heroExpanded by rememberSaveable { mutableStateOf(true) }
    val heroResolved = !state.loading && state.shelves.isNotEmpty() &&
        embyHeroShelves(state.items).all { state.shelves.getOrNull(it)?.let { shelf -> shelf.loaded && !shelf.error } != false }
    val hasHero = state.hero.isNotEmpty()
    LaunchedEffect(heroResolved, hasHero, active) {
        if (!active || !heroResolved || heroExpanded == hasHero) return@LaunchedEffect
        snapshotFlow { !scroll.isScrollInProgress && scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0 }
            .first { it }
        heroExpanded = hasHero
    }
    LaunchedEffect(scroll,state.shelves.size,active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.mapNotNull { embyHomeShelfAt(it.index) }.toSet() }
            .distinctUntilChanged().collect { controller.visibleShelves(it) }
    }
    val refreshing = state.loading && state.contentReady
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val layout = embyHomeLayout(availableWidth, maxHeight)
        CompositionLocalProvider(LocalEmbyGutter provides layout.gutter) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = controller::refresh, state = refresh,
            enabled = active && !state.restoring, modifier = Modifier.fillMaxSize(), indicator = {}) {
            LazyColumn(state = scroll, overscrollEffect = null,
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize().testTag("emby-home")) {
                item(key = "hero", contentType = "hero") {
                    if (heroExpanded && hasHero) EmbyHomeHero(state.hero, controller, state.heroScroll, scroll, layout)
                    else if (heroExpanded) Spacer(Modifier.fillMaxWidth().height(layout.heroHeight)
                        .background(MaterialTheme.colorScheme.surfaceContainer))
                    else Spacer(Modifier.statusBarsPadding().height(68.dp))
                }
                // Continue Watching comes first, so it is what peeks out under the hero.
                state.shelves.firstOrNull()?.let { shelf -> item(key = shelf.key, contentType = "shelf") {
                    EmbyHomeShelf(shelf, controller, availableWidth, layout.gutter)
                } }
                item(key = "libraries", contentType = "shelf") {
                    EmbyHeading(stringResource(R.string.emby_libraries))
                    EmbyHomeShelfRow(state.items, controller, availableWidth, layout.gutter, wide = true,
                        library = true, rowKey = "libraries", loading = state.loading)
                }
                items(state.shelves.drop(1), key = { it.key }, contentType = { "shelf" }) { shelf ->
                    EmbyHomeShelf(shelf, controller, availableWidth, layout.gutter)
                }
                if (state.items.isEmpty() && !state.loading && state.error == null) item { EmbyEmpty() }
            }
        }
        }
        // Like the transparent floating header, the status-bar area stays open to
        // the scrolling artwork and shelves in both light and dark themes.
    }
}

@Composable private fun EmbyHomeShelf(shelf: EmbyShelf, controller: EmbyBrowserController, availableWidth: Dp, gutter: Dp) {
    // An unrelated shelf delivery can skip this entire section, including its heading.
    EmbyHeading(shelf.title.ifEmpty { stringResource(R.string.emby_continue) }, onClick = shelf.location?.let {
        { controller.navigate(if (it.section == EmbySection.LATEST) it.copy(section = EmbySection.FOLDER) else it) }
    })
    Box(Modifier.testTag("emby-shelf-${shelf.key}")) {
        EmbyHomeShelfRow(shelf.items, controller, availableWidth, gutter, shelf.wide, rowKey = shelf.key, loading = !shelf.loaded, error = shelf.error)
    }
}

@Composable private fun EmbyHomeShelfRow(items: List<EmbyItem>, controller: EmbyBrowserController, availableWidth: Dp,
    gutter: Dp, wide: Boolean = false, library: Boolean = false, rowKey: String, loading: Boolean = false, error: Boolean = false) {
    // Decide before outer precomposition, avoiding another measure-time subcomposition.
    val tileWidth = if (wide) 168.dp else 112.dp
    val fits = items.isNotEmpty() && tileWidth * items.size + 8.dp * (items.size - 1) + gutter * 2 <= availableWidth
    EmbyShelfRow(items, controller, wide, library, rowKey, loading, error, fitStaticRow = fits)
}

internal val embySorts = listOf("DateLastContentAdded" to R.string.emby_ui_updated, "DateCreated" to R.string.emby_ui_added,
    "SortName" to R.string.emby_ui_title, "CommunityRating" to R.string.emby_ui_rating,
    "CriticRating" to R.string.emby_ui_critic, "ProductionYear" to R.string.emby_ui_year,
    "PremiereDate" to R.string.emby_ui_premiere, "OfficialRating" to R.string.emby_ui_official,
    "DatePlayed" to R.string.emby_ui_date_played, "Runtime" to R.string.emby_ui_runtime, "Random" to R.string.emby_ui_shuffle)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ColumnScope.EmbyCatalog(state: EmbyBrowserState, controller: EmbyBrowserController) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val width = this.maxWidth
        val inset = if (width < 600.dp) 16.dp else 24.dp
        Column(Modifier.fillMaxSize()) { EmbyCatalogContent(state, controller, inset, width) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ColumnScope.EmbyCatalogContent(state: EmbyBrowserState, controller: EmbyBrowserController, inset: Dp, width: Dp) {
    var query by rememberSaveable { mutableStateOf(state.location.query) }
    var sortMenu by remember { mutableStateOf(false) }; var filter by remember { mutableStateOf(false) }
    val location = state.location
    val categories = location.section in setOf(EmbySection.GENRES, EmbySection.TAGS)
    if (location.section == EmbySection.SEARCH) {
        // M3 search bar: filled, fully rounded, no underline; capped width on wide windows.
        val submit = { if (query.isNotBlank()) controller.replace(location.copy(query = query.trim())) }
        TextField(query, { query = it }, placeholder = { Text(stringResource(R.string.emby_ui_search_hint)) }, singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            leadingIcon = { Icon(painterResource(R.drawable.material_symbol_search), null, Modifier.size(22.dp)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = { Row {
                if (query.isNotEmpty()) EmbyIcon(R.drawable.material_symbol_close, R.string.emby_ui_search_clear, "emby-search-clear", { query = "" })
                EmbyIcon(R.drawable.material_symbol_arrow_forward, R.string.emby_search, "emby-search-submit", submit, query.isNotBlank())
            } },
            modifier = Modifier.padding(horizontal = inset, vertical = 8.dp).widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 56.dp).testTag("emby-search"))
    }
    if (location.parent?.type == "CollectionFolder" && location.section != EmbySection.RELATED) {
        val tabs = listOf(EmbySection.FOLDER to R.string.emby_ui_all, EmbySection.RESUME to R.string.emby_continue,
            EmbySection.FAVORITES to R.string.emby_ui_favorites, EmbySection.GENRES to R.string.emby_ui_genres,
            EmbySection.TAGS to R.string.emby_ui_tags, EmbySection.COLLECTIONS to R.string.emby_ui_collections,
            EmbySection.DIRECTORIES to R.string.emby_ui_folders)
        val selectedTab = tabs.indexOfFirst { it.first == location.section }.coerceAtLeast(0)
        val density = LocalDensity.current
        var underlineWidth by remember { mutableStateOf(28.dp) }
        ScrollableTabRow(selectedTab, edgePadding = 0.dp, divider = {}, indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selectedTab]).wrapContentWidth(Alignment.CenterHorizontally).width(underlineWidth), height = 2.dp)
        }) {
            tabs.forEach { (section, label) -> Tab(selected = section == location.section,
                selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { controller.replace(EmbyLocation(section, location.parent)) }, text = {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        onTextLayout = { if (section == location.section) underlineWidth = with(density) { it.size.width.toDp() } })
                }, modifier = Modifier.testTag("emby-tab-${section.name}")) }
        }
    }
    if (location.section != EmbySection.SEARCH || location.query.isNotBlank()) Row(Modifier.fillMaxWidth().padding(start = inset - 12.dp, end = inset), verticalAlignment = Alignment.CenterVertically) {
        if (categories) Spacer(Modifier.weight(1f)) else {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { sortMenu = true }, Modifier.testTag("emby-sort")) {
                Icon(painterResource(R.drawable.material_symbol_sort), null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(embySorts.firstOrNull { it.first == location.sort }?.second ?: R.string.emby_ui_sort), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(sortMenu, { sortMenu = false }) { embySorts.forEach { (sort, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { sortMenu = false; controller.replace(location.copy(sort = sort)) }) } }
        }
        EmbyIcon(R.drawable.material_symbol_arrow_downward, R.string.emby_ui_direction, "emby-order", { controller.replace(location.copy(descending = !location.descending)) })
        EmbyIcon(if (state.listMode) R.drawable.material_symbol_view_list else R.drawable.material_symbol_grid_view, R.string.emby_ui_layout, "emby-layout", controller::listMode)
        EmbyIcon(R.drawable.material_symbol_filter_alt, R.string.emby_ui_filter, "emby-filter", { controller.loadFilters(); filter = true })
        }
        Text(pluralStringResource(R.plurals.emby_ui_count, state.total, state.total), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
    if (state.pageStart>0) TextButton(onClick=controller::previousPage,enabled=!state.loading) { Text(stringResource(R.string.cache_previous_page)) }
    // Tabs and filters remain in-page UI; only their content changes identity.
    PageMotionContent(state, Modifier.weight(1f).fillMaxWidth(), contentKey = { it.location }, motion = { from, to ->
        if (from.location.parent?.id == to.location.parent?.id && from.location.parent?.type == "CollectionFolder" &&
            from.location.section != to.location.section && from.location.section in CatalogSections && to.location.section in CatalogSections) {
            if (CatalogSections.indexOf(to.location.section) > CatalogSections.indexOf(from.location.section))
                PageMotion.LateralForward else PageMotion.LateralBackward
        } else PageMotion.TopLevel
    }) { page ->
    val state = page
    val location = state.location
    val categories = location.section in setOf(EmbySection.GENRES, EmbySection.TAGS)
    Column(Modifier.fillMaxSize()) {
    when {
        location.section == EmbySection.SEARCH && location.query.isBlank() ->
            EmbyEmptyState(R.drawable.material_symbol_search, stringResource(R.string.emby_ui_search_empty), Modifier.weight(1f))
        state.items.isEmpty() && !state.loading && state.error == null ->
            EmbyEmptyState(if (location.section == EmbySection.FAVORITES) R.drawable.material_symbol_favorite else R.drawable.material_symbol_movie,
                stringResource(R.string.emby_empty), Modifier.weight(1f).testTag("emby-empty"))
        state.listMode -> {
            val list = rememberPageList(state.scroll)
            LazyColumn(state = list, overscrollEffect = null, modifier = Modifier.weight(1f).testTag("emby-grid"), contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp)) {
                items(state.items, key = { it.id }, contentType = { "episode" }) { EmbyEpisodeRow(it, controller) }
                if (state.nextIndex < state.total) item { TextButton(onClick = controller::nextPage, modifier = Modifier.testTag("emby-more")) { Text(stringResource(R.string.emby_more)) } }
            }
        }
        else -> {
            val grid = rememberPageGrid(state.scroll)
            Box(Modifier.weight(1f)) {
                // Posters of ~104–150dp: 3 columns on phones, 5–6 on the Fold inner screen,
                // instead of tiny fixed 120dp cells. Gutters follow the window-class inset.
                val gap = if (width < 600.dp) 12.dp else 16.dp
                val columns = ((width - inset * 2 + gap) / (140.dp + gap)).toInt().coerceIn(3, 8)
                LazyVerticalGrid(GridCells.Fixed(columns), state = grid, overscrollEffect = null, contentPadding = PaddingValues(start = inset, end = inset, top = 8.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(if (categories) 28.dp else 20.dp), modifier = Modifier.fillMaxSize().testTag("emby-grid")) {
                    items(state.items, key = { it.id }, contentType = { if (categories) "category" else "poster" }) { EmbyTile(it, controller, Modifier.fillMaxWidth()) }
                    if (state.nextIndex < state.total) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = controller::nextPage, enabled = !state.loading, modifier = Modifier.testTag("emby-more")) { Text(stringResource(R.string.emby_more)) } }
                }
            }
        }
    }
    }
    }
    if (filter) EmbyFilterSheet(location, state.filters, { filter = false }) { filter = false; controller.replace(it) }
}

@Composable private fun EmbyEmptyState(icon: Int, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(message, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun EmbyFilterSheet(location: EmbyLocation, filters: EmbyFilters, close: () -> Unit, apply: (EmbyLocation) -> Unit) {
    var draft by remember { mutableStateOf(location) }
    ModalBottomSheet(onDismissRequest = close, modifier = Modifier.testTag("emby-filter-sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.emby_ui_filter), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.emby_ui_play_state), Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to R.string.emby_ui_any, false to R.string.emby_ui_unplayed, true to R.string.emby_ui_watched).forEach { (value, label) ->
                    FilterChip(draft.played == value, { draft = draft.copy(played = value) }, { Text(stringResource(label)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.favoriteOnly, { draft = draft.copy(favoriteOnly = it) }); Text(stringResource(R.string.emby_ui_favorites)) }
            FilterChoices(R.string.emby_ui_genres, filters.genres, draft.genre) { draft = draft.copy(genre = it) }
            FilterChoices(R.string.emby_ui_years, filters.years, draft.year) { draft = draft.copy(year = it) }
            FilterChoices(R.string.emby_ui_studios, filters.studios, draft.studio) { draft = draft.copy(studio = it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { draft = draft.copy(genre = "", year = "", studio = "", played = null, favoriteOnly = false) }) { Text(stringResource(R.string.emby_ui_clear)) }
                Button(onClick = { apply(draft) }, modifier = Modifier.testTag("emby-filter-apply")) { Text(stringResource(R.string.emby_ui_apply)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FilterChoices(label: Int, choices: List<String>, selected: String, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(label), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected.ifEmpty { stringResource(R.string.emby_ui_any) }) }
        if (expanded) BasicAlertDialog(onDismissRequest = { expanded = false }) {
            val uniqueChoices = remember(choices) { choices.distinct() }
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.fillMaxWidth().height(320.dp).testTag("emby-filter-choices")) {
                        item(key = "any", contentType = "choice") {
                            DropdownMenuItem({ Text(stringResource(R.string.emby_ui_any)) }, { expanded = false; choose("") })
                        }
                        items(uniqueChoices, key = { "choice:$it" }, contentType = { "choice" }) { choice ->
                            DropdownMenuItem({ Text(choice) }, { expanded = false; choose(choice) })
                        }
                    }
                }
            }
        }
    }
}
