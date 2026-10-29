package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.mediaCaptionMinHeight
import cn.coldlake.stellar.opensource.medialibrary.*
import kotlinx.coroutines.flow.first
import cn.coldlake.stellar.opensource.medialibrary.rememberFloatingHomeScrollConnection
import cn.coldlake.stellar.opensource.appearance.ExpressiveMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.stellar.player.ui.LocalPageActive
import androidx.navigation3.runtime.rememberNavBackStack
import cn.coldlake.stellar.opensource.navigation.LibraryDestination
import cn.coldlake.stellar.opensource.navigation.LibraryNavDisplay
import cn.coldlake.stellar.opensource.navigation.rememberHorizontalNavigationMetadata

internal fun HomeSection.label() = when (this) {
    HomeSection.CONTINUE -> R.string.home_continue; HomeSection.RECENT -> R.string.home_recent
    HomeSection.MOVIES -> R.string.home_movies; HomeSection.SERIES -> R.string.home_series
    HomeSection.UNRECOGNIZED -> R.string.scan_unrecognized
}

@Composable internal fun LocalLibraryScreen(onAdd: () -> Unit, onPlay: (CatalogCard) -> Unit,
    onMetadataOpen: (Boolean) -> Unit = {}, onPlayAt: (CatalogCard, Long) -> Unit = { card, _ -> onPlay(card) },
    library: LocalLibraryRuntime? = null, onDestination: (LibraryDestination) -> Unit = {}) {
    val context = LocalContext.current
    val runtime = remember(library) { library ?: LocalLibraryRuntime.get(context) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    val stack = rememberNavBackStack(LibraryDestination.Home)
    val notify by rememberUpdatedState(onMetadataOpen)
    val notifyDestination by rememberUpdatedState(onDestination)
    val active = LocalPageActive.current
    LaunchedEffect(stack.last(), active) { if (active) { notify(stack.size > 1); notifyDestination(stack.last() as LibraryDestination) } }
    DisposableEffect(Unit) { onDispose { notify(false) } }
    val back = { if (stack.size > 1) stack.removeAt(stack.lastIndex); Unit }
    val push: (LibraryDestination) -> Unit = { target ->
        // Bound retained route/window state, including cycles through a person's works.
        if (stack.size >= 32) stack.removeAt(1)
        stack.add(target)
    }
    val directoryMotion = rememberHorizontalNavigationMetadata(shortDistance = true)
    LibraryNavDisplay(stack, Modifier.fillMaxSize(), onBack = back,
        entryMetadata = { if (it is LibraryDestination.Grid) directoryMotion else emptyMap() }) { shown ->
        when (shown) {
            is LibraryDestination.Metadata -> {
                val card by produceState<CatalogCard?>(null, shown.card) {
                    if (shown.card != 0L) value = runtime.refreshCards(listOf(shown.card)).firstOrNull()
                }
                Box(Modifier.safeDrawingPadding()) {
                    if (shown.card == 0L || card != null) MetadataScreen(runtime, card, onBack = back)
                }
            }
            is LibraryDestination.Detail -> LibraryDetail(runtime, shown.entity, shown.source, onBack = back,
                onEdit = { push(LibraryDestination.Metadata(it)) }, onPlay = onPlayAt, pageKey = shown,
                onSeason = { number, id -> push(LibraryDestination.Season(shown.entity, shown.source, number, id)) },
                onPerson = { push(LibraryDestination.Person(shown.entity, shown.source, it.navigationKey())) })
            is LibraryDestination.Season -> LibraryDetail(runtime, shown.entity, shown.source, onBack = back,
                onEdit = { push(LibraryDestination.Metadata(it)) }, onPlay = onPlayAt, pageKey = shown,
                fixedSeason = shown.number, seasonId = shown.publicId,
                onPerson = { push(LibraryDestination.Person(shown.entity, shown.source, it.navigationKey(), shown.number, shown.publicId)) },
                onSeries = { back() })
            is LibraryDestination.Person -> LibraryPersonPage(runtime, shown, back,
                onWork = { push(LibraryDestination.Detail(it, shown.source)) })
            is LibraryDestination.Grid -> Box(Modifier.safeDrawingPadding()) {
                LibraryGrid(runtime, shown.section, shown.source, onBack = back,
                    onCard = { push(LibraryDestination.Detail(it, shown.source)) })
            }
            LibraryDestination.Home -> LibraryHome(runtime, source, { source = it }, onAdd,
                onSection = { push(LibraryDestination.Grid(it, source)) },
                onCard = { push(LibraryDestination.Detail(it, source)) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LibraryHome(runtime: LocalLibraryRuntime, source: String?, onSource: (String?) -> Unit,
    onAdd: () -> Unit,
    onSection: (HomeSection) -> Unit, onCard: (Long) -> Unit, pageKey: Any = LibraryDestination.Home) {
    val list = rememberLazyListState()
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    val sourceChanges = remember(runtime) { runtime.homeUpdates.map { it.sources }.distinctUntilChanged() }
    val initialSourceRevision = remember(runtime) { runtime.homeUpdates.value.sources }
    val change by sourceChanges.collectAsStateWithLifecycle(initialSourceRevision)
    val presentation: LibraryPresentation = viewModel()
    var sources by presentation::sources
    var sourcesLoaded by presentation::sourcesLoaded
    val active = LocalPageActive.current
    LaunchedEffect(change, active) {
        if (active) { sources = runtime.home { sources() }; sourcesLoaded = true }
    }
    LaunchedEffect(sources, sourcesLoaded, source, active) {
        if (active && sourcesLoaded && source != null && sources.none { it.id == source }) onSource(null)
    }
    val hero = libraryHomeHero(runtime, source, refresh)
    val heroScroll = key(source) { rememberLazyListState() }
    var heroExpanded by rememberSaveable(source) { mutableStateOf(true) }
    LaunchedEffect(hero.loaded, hero.rows.isNotEmpty(), active) {
        if (!active || !hero.loaded || heroExpanded == hero.rows.isNotEmpty()) return@LaunchedEffect
        snapshotFlow { !list.isScrollInProgress && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }.first { it }
        heroExpanded = hero.rows.isNotEmpty()
    }
    val headerState = rememberTopAppBarState()
    val scrollConnection = rememberFloatingHomeScrollConnection(headerState,
        canScroll = { list.canScrollForward || list.canScrollBackward })
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("local-library").nestedScroll(scrollConnection)) {
        val layout = mediaHomeLayout(with(density) { constraints.maxWidth.toDp() }, with(density) { constraints.maxHeight.toDp() })
        LazyColumn(state = list, overscrollEffect = null, modifier = Modifier.fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero", contentType = "hero") {
                if (heroExpanded && hero.rows.isNotEmpty()) LibraryHomeHero(hero, runtime, heroScroll, list, layout, onCard)
                else if (heroExpanded) Spacer(Modifier.fillMaxWidth().height(layout.heroHeight).background(MaterialTheme.colorScheme.surfaceContainer))
                else Spacer(Modifier.statusBarsPadding().height(56.dp))
            }
            item(key = "controls", contentType = "controls") {
                Column {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            FilledTonalButton(onClick = { menu = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.testTag("home-source")) {
                                Text(sources.firstOrNull { it.id == source }?.label ?: stringResource(R.string.home_all_sources), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ExpressiveMenu(menu, { menu = false }, Modifier.testTag("home-source-menu")) {
                                SelectableDropdownMenuItem(selected = source == null,
                                    text = { Text(stringResource(R.string.home_all_sources)) },
                                    shapes = MenuDefaults.itemShape(0, sources.size + 1),
                                    selectedLeadingIcon = { Icon(painterResource(R.drawable.material_symbol_check), null) },
                                    modifier = Modifier.testTag("home-source-all"),
                                    onClick = { menu = false; onSource(null) })
                                sources.forEachIndexed { index, s ->
                                    key(s.id) {
                                        SelectableDropdownMenuItem(selected = source == s.id,
                                            text = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            shapes = MenuDefaults.itemShape(index + 1, sources.size + 1),
                                            selectedLeadingIcon = { Icon(painterResource(R.drawable.material_symbol_check), null) },
                                            modifier = Modifier.testTag("home-source-${s.id}"),
                                            onClick = { menu = false; onSource(s.id) })
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { refresh++ }, Modifier.testTag("home-refresh")) { Text(stringResource(R.string.home_refresh)) }
                    }
                }
            }
            if (sourcesLoaded && sources.isEmpty()) item(key = "empty", contentType = "empty") {
                Column(Modifier.fillParentMaxHeight(.65f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.home_empty_section))
                    Button(onClick = onAdd, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 16.dp).testTag("home-add-source")) { Text(stringResource(R.string.scan_add)) }
                }
            } else items(HomeSection.entries, key = { it.name }, contentType = { "shelf" }) { section ->
                HomeShelf(runtime, section, source, refresh, { onSection(section) }, onCard)
            }
        }
        MediaHomeHeader(headerState, list, hero.rows.isNotEmpty(), pageKey,
            Modifier.align(Alignment.TopCenter).testTag("home-floating-header")) {
            // Match the server home's transparent floating row; TopAppBar would
            // override the artwork foreground supplied by MediaHomeHeader.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MediaToolbarTitle(stringResource(R.string.scan_home), Modifier.weight(1f).testTag("home-floating-title"), outlined = hero.rows.isNotEmpty())
            }
        }
    }
}

@Composable private fun HomeShelf(runtime: LocalLibraryRuntime, section: HomeSection, source: String?, refresh: Int,
    onAll: () -> Unit, onCard: (Long) -> Unit) {
    val updates = remember(runtime, section, source) { runtime.homeUpdates.map { it.revision(section, source) }.distinctUntilChanged() }
    val initialRevision = remember(updates) { runtime.homeUpdates.value.revision(section, source) }
    val change by updates.collectAsStateWithLifecycle(initialRevision)
    val presentation: LibraryPresentation = viewModel()
    val window = presentation.window(section, source)
    var ids by rememberSaveable(source) { mutableStateOf(arrayListOf<Long>()) }
    var generation by rememberSaveable(source) { mutableIntStateOf(refresh) }
    var rows by window::rows
    var loaded by window::loaded
    var failed by remember(source) { mutableStateOf(false) }
    var newer by rememberSaveable(source) { mutableStateOf(false) }
    val list = key(source) { rememberLazyListState() }
    val active = LocalPageActive.current
    LaunchedEffect(change, source, refresh, active) {
        if (!active || (loaded && window.revision == change && generation == refresh)) return@LaunchedEffect
        try {
            val fresh = runtime.home { page(section, source) }
            val replacement = ids.isEmpty() || generation != refresh
            // Reuse the fetched window when its membership matches; only read missing saved IDs.
            val next = if (replacement) fresh else {
                val byId = fresh.associateBy { it.id }
                val missing = ids.filterNot(byId::containsKey)
                val older = if (missing.isEmpty()) emptyMap() else runtime.home { byIds(missing, source) }.associateBy { it.id }
                ids.mapNotNull { byId[it] ?: older[it] }
            }
            val anchor = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key
            val offset = list.firstVisibleItemScrollOffset
            rows = next
            if (replacement) {
                ids = ArrayList(rows.map { it.id }); generation = refresh
                rows.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }?.let { list.requestScrollToItem(it, offset) }
            }
            newer = fresh.map { it.id } != ids; loaded = true; failed = false; window.revision = change
        } catch (c: CancellationException) { throw c } catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxWidth().testTag("home-section-${section.name}")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(section.label()), Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
            TextButton(onClick = onAll, modifier = Modifier.testTag("home-all-${section.name}")) { Text(stringResource(R.string.home_view_all)) }
        }
        // This slot never inserts/removes vertical space when a scan or image completes.
        Box(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 20.dp)) {
            if ((failed && rows.isNotEmpty()) || newer) Text(stringResource(if (failed) R.string.home_refresh_failed else R.string.scan_new),
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (rows.isEmpty()) Column(Modifier.fillMaxWidth()) {
            // Keep the poster/caption geometry, but draw a skeleton only during initial loading.
            Box(Modifier.fillMaxWidth().height(124.dp / (2f / 3f)), contentAlignment = Alignment.Center) {
                if (!loaded && !failed) Spacer(Modifier.width(124.dp).fillMaxHeight().clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh))
                Text(stringResource(when {
                    failed -> R.string.home_load_failed
                    !loaded -> R.string.home_loading
                    section == HomeSection.CONTINUE -> R.string.home_no_progress
                    else -> R.string.home_empty_section
                }), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(top = 8.dp).height(mediaCaptionMinHeight(MaterialTheme.typography.bodyMedium)))
            Spacer(Modifier.height(mediaCaptionMinHeight(MaterialTheme.typography.labelSmall)))
        } else LazyRow(state = list, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("home-row-${section.name}")) {
            items(rows, key = { it.id }, contentType = { "poster" }) { row -> HomePoster(row, runtime,
                Modifier.width(124.dp).testTag("home-card-${section.name}-${row.id}")) { onCard(row.id) } }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun HomePosterPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        Spacer(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Spacer(Modifier.padding(top = 8.dp).height(mediaCaptionMinHeight(MaterialTheme.typography.bodyMedium)))
        Spacer(Modifier.height(mediaCaptionMinHeight(MaterialTheme.typography.labelSmall)))
    }
}

@Composable private fun HomePoster(row: HomeCard, runtime: LocalLibraryRuntime, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(row.card.title, Modifier.padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (row.card.poster != null) LibraryPoster(row.card, runtime, Modifier.fillMaxSize())
            if (row.resume > 0 && row.duration > 0) LinearProgressIndicator(progress = { (row.resume.toFloat() / row.duration).coerceIn(0f,1f) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Text(row.card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = mediaCaptionMinHeight(MaterialTheme.typography.bodyMedium)))
        Text(if (row.card.kind == "series") stringResource(R.string.home_episode_count, row.episodes)
            else if (row.versions > 1) stringResource(R.string.home_version_count, row.versions)
            else row.card.year?.toString() ?: stringResource(if (row.card.kind == "file") R.string.scan_unrecognized else R.string.scan_local),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = mediaCaptionMinHeight(MaterialTheme.typography.labelSmall)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LibraryGrid(runtime: LocalLibraryRuntime, section: HomeSection, source: String?, onBack: () -> Unit, onCard: (Long) -> Unit) {
    val grid = rememberLazyGridState()
    var ids by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var order by rememberSaveable { mutableLongStateOf(Long.MAX_VALUE) }
    var cursor by rememberSaveable { mutableLongStateOf(Long.MAX_VALUE) }
    var tailOrder by rememberSaveable { mutableLongStateOf(Long.MAX_VALUE) }
    var tailId by rememberSaveable { mutableLongStateOf(Long.MAX_VALUE) }
    var hasNext by rememberSaveable { mutableStateOf(false) }
    val presentation: LibraryPresentation = viewModel()
    val window = presentation.window(section, source)
    var rows by window::rows
    var loaded by window::loaded
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var requested by remember { mutableStateOf<HomeCursor?>(null) }
    var busy by remember { mutableStateOf(false) }
    val active = LocalPageActive.current
    val updates = remember(runtime, section, source) { runtime.homeUpdates.map { it.revision(section, source) }.distinctUntilChanged() }
    val initialRevision = remember(updates) { runtime.homeUpdates.value.revision(section, source) }
    val change by updates.collectAsStateWithLifecycle(initialRevision)
    LaunchedEffect(change, requested, retry, active) {
        if (!active || (requested == null && loaded && window.revision == change && !failed)) return@LaunchedEffect
        busy = true
        try {
            val target = requested
            val newWindow = target != null || ids.isEmpty()
            val fresh = runtime.home {
                if (newWindow) page(section, source, target ?: HomeCursor(order, cursor), 60) else byIds(ids, source)
            }
            if (newWindow) {
                tailOrder = fresh.lastOrNull()?.order ?: 0; tailId = fresh.lastOrNull()?.id ?: 0
                hasNext = fresh.size == 60
            }
            // Commit the page and its cursor together. A failed request leaves the previous page usable.
            rows = fresh
            ids = ArrayList(fresh.map { it.id }); loaded = true; failed = false; window.revision = change
            if (target != null) {
                order = target.order; cursor = target.id; requested = null
                grid.requestScrollToItem(0)
            }
        } catch(c: CancellationException) { throw c } catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(section.label())) }, navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.material_symbol_arrow_back),stringResource(R.string.scan_back)) } })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty() && (loaded || failed)) Text(stringResource(
                if (failed) R.string.home_load_failed else R.string.home_empty_section), Modifier.padding(20.dp))
            else LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), state = if (loaded) grid else rememberLazyGridState(), modifier = Modifier.fillMaxSize().testTag("scan-grid"),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (loaded) items(rows, key = { it.id }, contentType = { "poster" }) { row -> HomePoster(row, runtime, Modifier.testTag("scan-card-${row.card.id}")) { onCard(row.id) } }
                else items(12, key = { "placeholder:$it" }, contentType = { "placeholder" }) { HomePosterPlaceholder() }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (failed) Surface(Modifier.align(Alignment.BottomCenter), shape = MaterialTheme.shapes.medium) {
                TextButton(onClick = { retry++ }, enabled = !busy) { Text(stringResource(R.string.scan_retry)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { requested = HomeCursor(); retry++ }, enabled = !busy,
                modifier = Modifier.testTag("scan-first-page")) { Text(stringResource(R.string.scan_first)) }
            TextButton(onClick = { requested = HomeCursor(tailOrder, tailId); retry++ }, enabled = hasNext && !busy,
                modifier = Modifier.testTag("scan-next-page")) { Text(stringResource(R.string.scan_next)) }
        }
    }
}
