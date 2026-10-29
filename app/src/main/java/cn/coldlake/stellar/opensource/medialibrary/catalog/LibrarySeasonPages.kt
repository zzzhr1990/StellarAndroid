package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*
import com.stellar.player.ui.LocalPageActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LibraryRelationWindow : ViewModel() {
    var record by mutableStateOf<MetadataRecord?>(null)
    var children by mutableStateOf<LibraryChildren?>(null)
    var failed by mutableStateOf(false)
    var loadedKey: String? = null
    var cacheProbed by mutableStateOf(false)
}

@Composable internal fun librarySeasonData(runtime: LocalLibraryRuntime, entity: Long, source: String?, number: Int,
    publicId: String?, locale: String, retry: Int): LibraryRelationWindow {
    val state: LibraryRelationWindow = viewModel(key = "season-data:$number:$locale")
    val active = LocalPageActive.current
    LaunchedEffect(entity, source, publicId, number, locale, active, retry) {
        if (!active) return@LaunchedEffect
        val series = runtime.home { publicRecord(entity, source) } ?: run { state.cacheProbed = true; return@LaunchedEffect }
        val saved = runtime.home { publicRecord(entity, source, number) }
        if (state.record == null) state.record = saved
        val id = publicId ?: saved?.ids?.get("catalog") ?: run { state.cacheProbed = true; return@LaunchedEffect }
        if (state.loadedKey == "$id:$retry") return@LaunchedEffect
        try {
            val cached = withContext(Dispatchers.IO) { runtime.metadata.provider.cachedDetails(id, locale, "season") }
            if (cached?.ids?.get("catalog-series") == series.ids["catalog"] && cached?.season == number) state.record = cached
            state.cacheProbed = true
            val record = withContext(Dispatchers.IO) { runtime.metadata.provider.details(id, locale, "season") }
            if (record.ids["catalog-series"] != series.ids["catalog"] || record.season != number) throw MetadataFailure("identity")
            state.record = record; state.loadedKey = "$id:$retry"; state.failed = false
        } catch (c: CancellationException) { throw c } catch (_: Exception) { state.failed = true; state.cacheProbed = true }
    }
    return state
}

@Composable private fun libraryChildren(runtime: LocalLibraryRuntime, owner: MetadataRecord?, locale: String,
    cursor: String?, retry: Int): LibraryRelationWindow {
    val state: LibraryRelationWindow = viewModel(key = "children:${owner?.identity}:$locale")
    val active = LocalPageActive.current
    LaunchedEffect(owner?.identity, locale, cursor, retry, active) {
        val id = owner?.ids?.get("catalog") ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        val key = "$id:$locale:${cursor.orEmpty()}:$retry"
        if (state.loadedKey == key) return@LaunchedEffect
        val provider = runtime.metadata.provider
        try {
            val cached = withContext(Dispatchers.IO) { provider.cachedChildren(id, owner.kind, locale, cursor) }
            if (cached != null) state.children = cached
            state.cacheProbed = true
            state.children = withContext(Dispatchers.IO) { LibraryChildren.parse(provider.children(id, owner.kind, locale, cursor), id, owner.kind) }
            state.loadedKey = key; state.failed = false
        } catch (c: CancellationException) { throw c } catch (_: Exception) { state.failed = true; state.cacheProbed = true }
    }
    return state
}

@Composable internal fun LibrarySeasonLinks(runtime: LocalLibraryRuntime, entity: Long, source: String?, local: List<Int>,
    locale: String, onSeason: (Int, String?) -> Unit) {
    var owner by remember { mutableStateOf<MetadataRecord?>(null) }
    val active = LocalPageActive.current
    LaunchedEffect(entity, source, active) { if (active) owner = runtime.home { publicRecord(entity, source) } }
    var cursor by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val state = libraryChildren(runtime, owner, locale, cursor, retry)
    val public = state.children?.items.orEmpty().associateBy { it.season }
    val numbers = remember(local, public) { (local + public.keys.filterNotNull()).distinct().sorted() }
    MediaDetailHeading(stringResource(R.string.emby_ui_seasons))
    LazyRow(contentPadding = PaddingValues(horizontal = LocalMediaDetailLayout.current.inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("home-seasons")) {
        items(numbers, key = { it }) { number ->
            OutlinedButton(onClick = { onSeason(number, public[number]?.ids?.get("catalog")) }, modifier = Modifier.testTag("home-open-season-$number")) {
                Text(public[number]?.title ?: stringResource(R.string.home_season, number))
            }
        }
    }
    RelationPaging(state, cursor, { cursor = it }, { retry++ })
}

internal data class LibraryEpisodePage(val missing: List<MetadataRecord>, val ready: Boolean,
    val state: LibraryRelationWindow, val cursor: String?, val onCursor: (String?) -> Unit, val retry: () -> Unit)

@Composable internal fun libraryPublicEpisodes(runtime: LocalLibraryRuntime, entity: Long, source: String?, season: MetadataRecord?, locale: String): LibraryEpisodePage {
    var cursor by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val state = libraryChildren(runtime, season, locale, cursor, retry)
    val page = state.children
    var owned by remember(page, source) { mutableStateOf<Set<Int>?>(null) }
    val active = LocalPageActive.current
    val updates = remember(runtime, source) { runtime.homeUpdates.map { it.revision(HomeSection.SERIES, source) }.distinctUntilChanged() }
    val initial = remember(updates) { runtime.homeUpdates.value.revision(HomeSection.SERIES, source) }
    val revision by updates.collectAsStateWithLifecycle(initial)
    LaunchedEffect(page, source, active, revision) {
        if (active && page != null && season != null) owned = runtime.home { ownedEpisodes(entity, source,
            season.ids.getValue("catalog-series"), checkNotNull(season.season), page.items.flatMap { it.episodes }) }
    }
    val missing = if (owned == null) emptyList() else page?.items.orEmpty().filter { it.episodes.none(checkNotNull(owned)::contains) }
    return LibraryEpisodePage(missing, season == null || (state.cacheProbed && (page == null || owned != null)),
        state, cursor, { cursor = it }, { retry++ })
}

/** Public episodes are separate lazy rows, with no playable file locator. */
internal fun LazyListScope.libraryPublicEpisodeItems(page: LibraryEpisodePage, runtime: LocalLibraryRuntime) {
    if (page.missing.isNotEmpty()) item("missing-episodes", "heading") { MediaDetailHeading(stringResource(R.string.library_not_owned_episodes)) }
    items(page.missing, key = { "public:${it.identity}" }, contentType = { "public-episode" }) { episode ->
        MediaDetailEpisodeRow(episode.title, Modifier.testTag("home-unowned-${episode.ids["catalog"]}"), image = { modifier ->
            episode.details.poster?.let { LibraryArtworkImage(it, runtime, modifier) } ?: Spacer(modifier)
        }) {
            Text(stringResource(R.string.library_not_owned), style = MaterialTheme.typography.labelMedium)
            episode.details.premiere?.let { Text(mediaDetailDate(it), style = MaterialTheme.typography.bodySmall) }
            episode.plot?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
        }
    }
    item("public-paging", "actions") { RelationPaging(page.state, page.cursor, page.onCursor, page.retry) }
}

@Composable private fun RelationPaging(state: LibraryRelationWindow, cursor: String?, onCursor: (String?) -> Unit, retry: () -> Unit) {
    if (state.failed || state.children?.next != null || cursor != null) Row(Modifier.padding(horizontal = LocalMediaDetailLayout.current.inset)) {
        if (state.failed) TextButton(onClick = retry) { Text(stringResource(R.string.scan_retry)) }
        if (cursor != null) TextButton(onClick = { onCursor(null) }) { Text(stringResource(R.string.scan_first)) }
        state.children?.next?.let { next -> TextButton(onClick = { onCursor(next) }, Modifier.testTag("home-public-next")) { Text(stringResource(R.string.scan_next)) } }
    }
}

@Composable internal fun LibraryArtworkImage(art: LibraryArtwork, runtime: LocalLibraryRuntime, modifier: Modifier,
    onColor: ((Int) -> Unit)? = null) {
    val card = remember(art) { CatalogCard(0, "", "", "", "file", null, null, null, null, null, art.uri, art.version, null) }
    LibraryPoster(card, runtime, modifier, onColor = onColor, wholeImageColor = true)
}
