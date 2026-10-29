package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*
import cn.coldlake.stellar.opensource.navigation.LibraryDestination
import com.stellar.player.ui.LocalPageActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LibraryPersonWindow : ViewModel() {
    var credit by mutableStateOf<LibraryCredit?>(null)
    var origin by mutableStateOf<HomeCard?>(null)
    var publicId by mutableStateOf<String?>(null)
    var person by mutableStateOf<LibraryPersonData?>(null)
    var works by mutableStateOf<LibraryFilmography?>(null)
    var owned by mutableStateOf<Map<String, Long>>(emptyMap())
    var identityLoaded by mutableStateOf(false)
    var personLoaded = false
    var worksLoaded = false
    var personReady by mutableStateOf(false)
    var worksReady by mutableStateOf(false)
    var personFailed by mutableStateOf(false)
    var worksFailed by mutableStateOf(false)
}

@Composable internal fun LibraryPersonPage(runtime: LocalLibraryRuntime, route: LibraryDestination.Person,
    onBack: () -> Unit, onWork: (Long) -> Unit) {
    val locale = metadataLanguage(LocalConfiguration.current.locales[0].toLanguageTag())
    val state: LibraryPersonWindow = viewModel(key = "person:$locale")
    val active = LocalPageActive.current
    val provider = runtime.metadata.provider
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(route, active) {
        if (!active || state.identityLoaded) return@LaunchedEffect
        state.origin = runtime.home { byIds(listOf(route.entity), route.source).firstOrNull() }
        var record = runtime.home { publicRecord(route.entity, route.source, route.season) }
        if (route.seasonId != null && route.season != null) {
            try {
                val fresh = withContext(Dispatchers.IO) { provider.details(route.seasonId, locale, "season") }
                val parent = runtime.home { publicRecord(route.entity, route.source) }
                if (fresh.season == route.season && fresh.ids["catalog-series"] == parent?.ids?.get("catalog")) record = fresh
            } catch (c: CancellationException) { throw c } catch (_: Exception) { /* The saved origin is still usable. */ }
        }
        val local = runtime.home { details(route.entity, route.source) }.people.firstOrNull { it.navigationKey() == route.credit }
        state.credit = if (route.season == null) local else record?.details?.people?.firstOrNull { it.navigationKey() == route.credit } ?: local
        state.publicId = state.credit?.takeIf { credit -> record?.details?.people?.any { it.navigationKey() == credit.navigationKey() } == true }
            ?.id?.takeIf { runCatching { MetadataProvider.owned(it) }.isSuccess }
        state.identityLoaded = true
    }
    val id = state.publicId
    // Independent groups: a filmography failure cannot hide a biography or the source work.
    LaunchedEffect(id, locale, active, retry) {
        if (!active || id == null || state.personLoaded) return@LaunchedEffect
        try {
            if (state.person == null) state.person = withContext(Dispatchers.IO) { provider.cachedPerson(id, locale) }
            if (state.person != null) state.personReady = true
            state.person = withContext(Dispatchers.IO) { provider.person(id, locale) }
            state.personLoaded = true; state.personFailed = false
            state.personReady = true
        } catch (c: CancellationException) { throw c } catch (_: Exception) { state.personFailed = true; state.personReady = true }
    }
    LaunchedEffect(id, locale, active, retry) {
        if (!active || id == null || state.worksLoaded) return@LaunchedEffect
        try {
            if (state.works == null) state.works = withContext(Dispatchers.IO) { provider.cachedFilmography(id, locale) }
            if (state.works != null) state.worksReady = true
            val works = withContext(Dispatchers.IO) { provider.filmography(id, locale) }
            state.works = works
            if (works.items.isNotEmpty()) {
                val cards = withContext(Dispatchers.IO) { provider.cards(works.items.map { it.ids.getValue("catalog") }, locale) }
                    .getJSONArray("items").objects(40).filter { it.optString("state") == "ready" }
                    .map { MetadataPresentation.entity(it.getJSONObject("card")) }.associateBy { it.identity }
                state.works = works.copy(items = works.items.map { cards[it.identity]?.takeIf { card -> card.kind == it.kind } ?: it })
            }
            state.worksLoaded = true; state.worksFailed = false; state.worksReady = true
        } catch (c: CancellationException) { throw c } catch (_: Exception) { state.worksFailed = true; state.worksReady = true }
    }
    val updates = remember(runtime, route.source) { runtime.homeUpdates.map { it.revision(HomeSection.RECENT, route.source) }.distinctUntilChanged() }
    val initial = remember(updates) { runtime.homeUpdates.value.revision(HomeSection.RECENT, route.source) }
    val revision by updates.collectAsStateWithLifecycle(initial)
    LaunchedEffect(state.works, active, revision) {
        if (active) {
            state.owned = runtime.home { ownedPublic(state.works?.items.orEmpty(), route.source) }
            if (state.identityLoaded) state.origin = runtime.home { byIds(listOf(route.entity), route.source).firstOrNull() }
        }
    }
    val list = rememberLazyListState()
    // A restored deep anchor must never attach to the temporary biography-only layout.
    // Stale cached groups are ready immediately, without waiting for network revalidation.
    val ready = state.identityLoaded && (id == null || (state.personReady && state.worksReady))
    val shownList = if (ready) list else rememberLazyListState()
    val credit = state.credit
    val name = state.person?.name ?: credit?.name.orEmpty()
    var color by rememberSaveable(credit?.image) { mutableStateOf<Int?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("home-person-page")) {
        val layout = mediaDetailLayout(with(LocalDensity.current) { constraints.maxWidth.toDp() })
        MediaDetailTheme(color) { top, bottom ->
            CompositionLocalProvider(LocalMediaDetailLayout provides layout) {
                MediaDetailPage(shownList, top, bottom, Modifier.testTag("home-person-list"),
                    image = credit?.image?.let { art -> { modifier -> LibraryArtworkImage(art, runtime, modifier, onColor = { color = it }) } },
                    toolbar = {
                        MediaDetailTopBar(if (ready) list else null, top, name, Modifier.align(Alignment.TopCenter),
                            statusBarAppearance = { collapsed -> MediaStatusBar(route, credit?.image != null && !collapsed, top) }, navigation = {
                                IconButton(onClick = onBack, Modifier.testTag("home-person-back")) {
                                    val painter = painterResource(R.drawable.material_symbol_arrow_back)
                                    Icon(painter, stringResource(R.string.scan_back), Modifier.artworkIconOutline(painter, LocalContentColor.current))
                                }
                            })
                    }) {
                    item("person", "metadata") {
                        MediaDetailMetadata(name.ifBlank { stringResource(R.string.home_loading) }, person = true, modifier = Modifier.testTag("home-person-name")) {
                            credit?.let { Text(it.role ?: mediaDetailCreditRole(it.type), style = MaterialTheme.typography.bodyMedium) }
                            state.person?.birthDate?.let { Text(stringResource(R.string.emby_ui_born, mediaDetailDate(it))) }
                            state.person?.birthplace?.let { Text(stringResource(R.string.emby_ui_birth_place, it)) }
                        }
                    }
                    state.person?.biography?.let { biography -> item("biography", "text") {
                        MediaDetailOverview(biography, "home-person-biography", "home-person-full-biography", maxLines = 5)
                    } }
                    state.origin?.let { origin -> item("origin", "shelf") {
                        MediaDetailHeading(stringResource(R.string.library_person_origin))
                        Box(Modifier.padding(horizontal = layout.inset)) {
                            PersonWork(origin.card.title, origin.card.poster?.let { LibraryArtwork(it, origin.card.posterVersion) },
                                runtime, true, "home-person-origin") { onWork(origin.id) }
                        }
                    } }
                    val groups = state.works?.items.orEmpty().groupBy { it.kind }
                    for (kind in listOf("movie", "series")) {
                        val works = groups[kind].orEmpty()
                        if (works.isNotEmpty()) item("works:$kind", "shelf") {
                            MediaDetailHeading(stringResource(if (kind == "movie") R.string.emby_ui_movies else R.string.emby_ui_shows))
                            LazyRow(contentPadding = PaddingValues(horizontal = layout.inset), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.testTag("home-person-works-$kind")) {
                                items(works, key = { it.identity }, contentType = { "poster" }) { work ->
                                    val owned = state.owned[work.ids["catalog"]]
                                    PersonWork(work.title, work.details.poster, runtime, owned != null, "home-person-work-${work.ids["catalog"]}") {
                                        owned?.let(onWork)
                                    }
                                }
                            }
                        }
                    }
                    if (state.works?.truncated == true) item("limited", "text") {
                        Text(stringResource(R.string.library_person_works_limited), Modifier.padding(horizontal = layout.inset), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.personFailed || state.worksFailed) item("retry", "action") {
                        TextButton(onClick = { retry++ }, Modifier.testTag("home-person-retry")) { Text(stringResource(R.string.scan_retry)) }
                    }
                }
            }
        }
    }
}

@Composable private fun PersonWork(title: String, art: LibraryArtwork?, runtime: LocalLibraryRuntime, available: Boolean,
    tag: String, onClick: () -> Unit) {
    Column(Modifier.width(124.dp).clickable(enabled = available, onClick = onClick).testTag(tag)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(title, Modifier.padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            art?.let { LibraryArtworkImage(it, runtime, Modifier.fillMaxSize()) }
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(if (available) R.string.library_owned else R.string.library_not_owned), style = MaterialTheme.typography.labelSmall)
    }
}
