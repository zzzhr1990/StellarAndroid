package cn.coldlake.stellar.opensource.medialibrary.catalog

import com.stellar.player.ui.LocalPageActive
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*
import cn.coldlake.stellar.opensource.settings.rememberRightHanded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun LibraryDetail(runtime: LocalLibraryRuntime, entity: Long, source: String?, onBack: () -> Unit,
    onEdit: (Long) -> Unit, onPlay: (CatalogCard, Long) -> Unit,
    pageKey: Any = entity, fixedSeason: Int? = null, seasonId: String? = null,
    onSeason: (Int, String?) -> Unit = { _, _ -> }, onPerson: (LibraryCredit) -> Unit = {}, onSeries: () -> Unit = onBack) {
    val list = rememberLazyListState()
    val presentation: LibraryPresentation = viewModel()
    val window = presentation.detail(entity, source)
    var detail by window::card
    var rich by window::rich
    var richRevision by window::richRevision
    var versions by window::versions
    var seasons by window::seasons
    var season by rememberSaveable(entity) { mutableIntStateOf(fixedSeason ?: -1) }
    var selected by rememberSaveable(entity, source) { mutableLongStateOf(0) }
    var after by rememberSaveable(entity, source) { mutableLongStateOf(Long.MIN_VALUE) }
    var loaded by window::loaded
    var failed by remember { mutableStateOf(false) }
    var selectionFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedVersion by window::selectedVersion
    var options by rememberSaveable { mutableStateOf(false) }
    var chooser by rememberSaveable { mutableStateOf(false) }
    val chosen = selectedVersion?.takeIf { it.id == selected }
    var revisions by remember(entity, source) { mutableStateOf<Pair<Long, Long>?>(null) }
    val scope = rememberCoroutineScope()
    val metadataLocale = metadataLanguage(LocalConfiguration.current.locales[0].toLanguageTag())
    val pageActive = LocalPageActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Global publications perform just one indexed revision lookup for this open entity.
    // Unrelated entities and forced status publications do not restart the detail queries.
    LaunchedEffect(entity, source, pageActive, lifecycle) {
        if (pageActive) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runtime.changes.collect { revisions = runtime.home { detailRevisions(entity) } ?: (-1L to -1L) }
        }
    }
    val contentRevision = revisions?.first
    LaunchedEffect(entity, source, revisions, season, after, retry, pageActive) {
        if (revisions == null) return@LaunchedEffect
        if (!pageActive) return@LaunchedEffect
        try {
            val page = runtime.home { Triple(byIds(listOf(entity), source).firstOrNull(), if (richRevision != contentRevision || !loaded) seasons(entity, source) else seasons, versions(entity, source, after, season.takeIf { it >= 0 })) }
            if (richRevision != contentRevision) {
                rich = runtime.home { details(entity, source) }
                window.versionCount = runtime.home { versionCount(entity, source, fixedSeason) }
                richRevision = contentRevision
            }
            if (selected == 0L) selected = runtime.home { preferred(entity, source, fixedSeason) } ?: 0
            detail = page.first; seasons = page.second; versions = page.third
            loaded = true; failed = false
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
    LaunchedEffect(entity, source, selected, versions, revisions, retry, loaded, pageActive) {
        if (!loaded || !pageActive) return@LaunchedEffect
        try {
            val version = versions.firstOrNull { it.id == selected }
                ?: runtime.home { versions(entity, source, season = fixedSeason, exact = selected).firstOrNull() }
            if (version == null) {
                selectedVersion = null
                val preferred = runtime.home { preferred(entity, source, fixedSeason) } ?: versions.firstOrNull()?.id ?: 0
                if (selected != preferred) selected = preferred
            } else {
                selectedVersion = version
                if (!CatalogServerLocator.resolved(version.card.uri)) {
                    val variants = runtime.serverVersions(version.card)
                    if (variants.isEmpty()) throw java.io.IOException("No available media source")
                    selected = variants.first()
                }
            }
            selectionFailed = false
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { selectionFailed = true }
    }

    // The saved page is delivered first. This effect does not depend on cache/publication changes.
    LaunchedEffect(entity, source, loaded, metadataLocale, pageActive) {
        if (pageActive && loaded && !runtime.status.value.running) runtime.home { detailVersion(entity, source)?.first }?.takeIf { it > 0 }?.let {
            runtime.metadata.refresh(it, metadataLocale)
        }
    }

    var aggregateRatings by remember(entity, source) { mutableStateOf<List<LibraryRating>>(emptyList()) }
    var ratingsKey by remember(entity, source) { mutableStateOf<String?>(null) }
    LaunchedEffect(entity, source, contentRevision, pageActive, retry) {
        if (!pageActive || contentRevision == null) return@LaunchedEffect
        val target = runtime.home { ratingsTarget(entity, source) }
        if (ratingsKey != target?.key) { aggregateRatings = emptyList(); ratingsKey = target?.key }
        if (target != null) runtime.metadata.provider.loadRatings(target) { aggregateRatings = it }
    }

    val seasonState = if (fixedSeason != null) librarySeasonData(runtime, entity, source, fixedSeason, seasonId, metadataLocale, retry) else null
    val publicSeason = seasonState?.record
    val publicEpisodes = if (fixedSeason != null) libraryPublicEpisodes(runtime, entity, source, publicSeason, metadataLocale) else null
    val pageReady = loaded && (seasonState == null || (seasonState.cacheProbed && publicEpisodes?.ready == true))
    val seasonTitle = fixedSeason?.let { publicSeason?.title ?: stringResource(R.string.home_season, it) }
    val pageRich = if (fixedSeason == null) rich else publicSeason?.details ?: LibraryDetails(poster = rich.poster, backdrop = rich.backdrop)
    val pageHeader = detail?.let { header -> if (fixedSeason == null) header else header.copy(
        card = header.card.copy(title = checkNotNull(seasonTitle), kind = "season", plot = publicSeason?.plot, year = publicSeason?.year,
            season = fixedSeason), versions = window.versionCount) }
    val rightHanded = rememberRightHanded()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("home-detail")) {
        val width = with(LocalDensity.current) { constraints.maxWidth.toDp() }
        val layout = mediaVideoDetailLayout(width, rightHanded)
        val actionWidth = mediaDetailActionWidth(width, layout)
        val data = remember(pageHeader, pageRich, versions) { pageHeader?.let { libraryPresentation(it, pageRich, versions) } }
        val artwork = data?.heroArtwork(layout.portraitHeader)?.let { (it as? MediaPresentationImage.Library)?.artwork }
        val artworkKey = artwork?.let { "${it.uri}:${it.version}" }
        var sampledArtwork by rememberSaveable(entity, source) { mutableStateOf<String?>(null) }
        var savedColor by rememberSaveable(entity, source) { mutableStateOf<Int?>(null) }
        MediaDetailTheme(savedColor.takeIf { sampledArtwork == artworkKey }) { color, bottomColor ->
        CompositionLocalProvider(LocalMediaDetailLayout provides layout) {
        val header = pageHeader
        val toolbar: @Composable BoxScope.() -> Unit = {
            // The current entry owns its remaining system insets and status-bar foreground.
            MediaDetailTopBar(if (loaded) list else null, color, header?.card?.title.orEmpty(),
                Modifier.align(Alignment.TopCenter), statusBarPadding = true,
                statusBarAppearance = { collapsed -> MediaStatusBar(pageKey, overArtwork = artwork != null && !collapsed, bodyColor = color) },
                logo = pageRich.logo?.takeIf { header != null }?.let { logo -> { modifier ->
                    LibraryPoster(checkNotNull(header).card.copy(poster = logo.uri, posterVersion = logo.version), runtime,
                        modifier, scale = ContentScale.Fit, alignment = Alignment.CenterStart)
                } }, navigation = {
                    IconButton(onClick = onBack, Modifier.testTag("home-detail-back")) {
                        val painter = painterResource(R.drawable.material_symbol_arrow_back)
                        Icon(painter, stringResource(R.string.scan_back), Modifier.size(24.dp)
                            .artworkIconOutline(painter, LocalContentColor.current))
                    }
                })
        }
        // Attach saved deep list positions only when the entire local window is ready.
        if (header != null && data != null && pageReady) MediaDetailPage(list, color, bottomColor,
            Modifier.testTag("home-detail-list"), statusBarPadding = true, logoWidth = actionWidth,
            image = artwork?.let { image -> { modifier ->
                LibraryPoster(header.card.copy(poster = image.uri, posterVersion = image.version), runtime,
                    modifier.testTag("home-backdrop"), onColor = { sampledArtwork = artworkKey; savedColor = it },
                    alignment = if (layout.portraitHeader) Alignment.TopCenter else Alignment.Center, wholeImageColor = true)
            } }, logo = pageRich.logo?.let { logo -> { modifier ->
                LibraryPoster(header.card.copy(poster = logo.uri, posterVersion = logo.version), runtime,
                    modifier.testTag("home-logo"), scale = ContentScale.Fit,
                    alignment = if (layout.portraitHeader) Alignment.Center else androidx.compose.ui.AbsoluteAlignment.CenterLeft)
            } } ?: { modifier -> MediaDetailHeroTitle(header.card.title, modifier.testTag("home-detail-title")) }, toolbar = toolbar) {
            if (fixedSeason != null) item("series-link", "link") {
                TextButton(onClick = onSeries, modifier = Modifier.padding(horizontal = layout.inset).testTag("home-season-series")) {
                    Text(detail?.card?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item("summary", "summary") {
                LibraryDetailSummary(data, header, pageRich, chosen, selectionFailed,
                    wide = !layout.portraitHeader, actionWidth = actionWidth,
                    ratings = aggregateRatings.ifEmpty { pageRich.ratings },
                    ratingsForSeries = fixedSeason != null && aggregateRatings.isNotEmpty(),
                    onMore = { options = true }, onWatched = {
                        chosen?.let { version -> scope.launch {
                            val clear = version.watched || version.position > 0
                            runtime.recordProgress(version.id, if (clear) 0 else version.position, version.duration, !clear)
                        } }
                    }, onPlay = onPlay)
            }
            if (fixedSeason == null && header.card.kind == "series") item("season-pages", "seasons") {
                LibrarySeasonLinks(runtime, entity, source, seasons, metadataLocale, onSeason)
            }
            item("versions-heading", "heading") {
                MediaDetailHeading(stringResource(if (seasons.isEmpty()) R.string.home_versions_sources else R.string.emby_ui_episodes))
            }
            if (fixedSeason == null && seasons.isNotEmpty()) item("seasons", "filter") {
                var expanded by remember { mutableStateOf(false) }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { expanded = true }, Modifier.testTag("home-season")) {
                        Text(if (season < 0) stringResource(R.string.home_all_seasons) else stringResource(R.string.home_season, season))
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_all_seasons)) }, onClick = { season = -1; after = Long.MIN_VALUE; expanded = false })
                        seasons.forEach { n -> DropdownMenuItem(text = { Text(stringResource(R.string.home_season, n)) },
                            onClick = { season = n; after = Long.MIN_VALUE; expanded = false }, modifier = Modifier.testTag("home-season-$n")) }
                    }
                }
            }
            items(versions, key = { "version:${it.id}" }, contentType = { "version" }) { version ->
                LibraryDetailVersion(version, selected == version.id, runtime) { selected = version.id }
            }
            if (versions.isNotEmpty()) item("paging", "actions") {
                LibraryDetailPaging(versions.size == 60,
                    first = { after = Long.MIN_VALUE; scope.launch { list.scrollToItem(0) } },
                    next = { after = versions.last().id; scope.launch { list.scrollToItem(0) } })
            }
            if (publicEpisodes != null) libraryPublicEpisodeItems(publicEpisodes, runtime)
            if (pageRich.people.isNotEmpty()) item("cast", "shelf") {
                MediaDetailHeading(stringResource(R.string.emby_ui_cast))
                LazyRow(contentPadding = PaddingValues(horizontal = layout.inset), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("home-cast")) {
                    items(pageRich.people, key = { it.navigationKey() }, contentType = { "person" }) { person ->
                        MediaDetailCredit(person.name, person.role ?: mediaDetailCreditRole(person.type),
                            Modifier.width(112.dp).clickable { onPerson(person) }.testTag("home-person-${person.id}"), image = person.image?.let { image -> { modifier ->
                                LibraryPoster(header.card.copy(poster = image.uri, posterVersion = image.version), runtime, modifier)
                            } })
                    }
                }
                if (pageRich.peopleTruncated) Text(pluralStringResource(R.plurals.home_credits_limited, pageRich.people.size, pageRich.people.size),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            chosen?.let { version -> item("file-information", "media") {
                MediaDetailHeading(stringResource(R.string.emby_ui_media_info))
                key(version.id) { LibraryDetailFileInformation(version, runtime, contentRevision, pageActive) }
            } }
        } else Box(Modifier.fillMaxSize()) {
            Text(stringResource(if (failed) R.string.scan_source_unavailable else R.string.home_loading), Modifier.align(Alignment.Center))
            toolbar()
        }

        if (failed || selectionFailed || seasonState?.failed == true) Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = {
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.scan_retry)) }
        }) { Text(stringResource(R.string.scan_source_unavailable)) }
    if (options && chosen != null) AlertDialog(onDismissRequest = { options = false }, title = { Text(stringResource(R.string.emby_ui_more)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            MediaDetailChoice(chosen.card.fileName, chosen.source, R.drawable.material_symbol_movie, "home-source") {
                options = false; chooser = true
            }
            if (chosen.resumeEligible) TextButton(onClick = { options = false; onPlay(chosen.card, 0) },
                Modifier.testTag("home-play-start")) { Text(stringResource(R.string.emby_ui_menu_restart)) }
            if (chosen.id > 0) TextButton(onClick = { options = false; onEdit(chosen.id) }, Modifier.testTag("metadata-edit")) { Text(stringResource(R.string.metadata_edit)) }
            TextButton(onClick = {
                options = false
                scope.launch { runtime.recordProgress(chosen.id, chosen.position, chosen.duration, !chosen.watched) }
            }, Modifier.testTag("home-watched")) { Text(stringResource(if (chosen.watched) R.string.home_mark_unwatched else R.string.home_mark_watched)) }
        } }, confirmButton = { TextButton(onClick = { options = false }) { Text(stringResource(R.string.emby_ui_close)) } })
    if (chooser) AlertDialog(onDismissRequest = { chooser = false }, title = { Text(stringResource(R.string.home_versions_sources)) },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp).testTag("home-version-chooser")) {
            items(versions, key = { it.id }) { version ->
                Row(Modifier.fillMaxWidth().selectable(selected == version.id, role = Role.RadioButton, onClick = { selected = version.id; chooser = false })
                    .padding(vertical = 8.dp).testTag("home-choose-${version.id}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == version.id, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(version.card.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(version.source, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { LibraryDetailPaging(versions.size == 60, { after = Long.MIN_VALUE }, { after = versions.last().id }) }
        } }, confirmButton = { TextButton(onClick = { chooser = false }) { Text(stringResource(R.string.emby_ui_close)) } })
    }
    }
    }
}

@Composable private fun LibraryDetailFileInformation(version: HomeVersion, runtime: LocalLibraryRuntime,
    revision: Long?, active: Boolean) {
    val size by produceState<Long?>(null, version.id, revision, active) {
        if (active) try { value = runtime.home { fileSize(version.id) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
    }
    val local = version.card.uri.startsWith("content:") || version.card.uri.startsWith("file:")
    val location = if (local) stringResource(R.string.local_file) else version.source
    val type = version.card.fileName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }.orEmpty()
    MediaDetailFileInformation(location, version.card.fileName, type, size,
        modifier = Modifier.testTag("home-file-information"))
}

@Composable private fun LibraryDetailVersion(version: HomeVersion, selected: Boolean, runtime: LocalLibraryRuntime, onSelect: () -> Unit) {
    MediaDetailEpisodeRow(version.card.title,
        modifier = Modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect).testTag("home-version-${version.id}"),
        image = { LibraryPoster(version.card, runtime, it.background(MaterialTheme.colorScheme.surfaceContainerHigh)) }) {
        if (version.card.season != null && version.card.episode != null) Text(
            if (version.card.episodes.size > 1) stringResource(R.string.home_episodes, version.card.season, version.card.episodes.joinToString(stringResource(R.string.home_episode_separator)))
            else stringResource(R.string.scan_episode, version.card.season, version.card.episode), style = MaterialTheme.typography.labelMedium)
        Text(version.card.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) Icon(painterResource(R.drawable.material_symbol_check_circle), null, Modifier.padding(end = 4.dp).size(18.dp))
            Text(version.source, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (version.duration > 0) Text(stringResource(R.string.emby_minutes, version.duration / 60_000), style = MaterialTheme.typography.labelMedium)
        if (version.watched) Text(stringResource(R.string.home_watched), style = MaterialTheme.typography.labelSmall)
        if (version.resumeEligible) Text(stringResource(R.string.emby_resume_at, mediaDetailTime(version.position)), style = MaterialTheme.typography.labelSmall)
        version.card.plot?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable private fun LibraryDetailPaging(hasNext: Boolean, first: () -> Unit, next: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = first, Modifier.testTag("home-versions-first")) { Text(stringResource(R.string.scan_first)) }
        TextButton(onClick = next, enabled = hasNext, modifier = Modifier.testTag("home-versions-next")) { Text(stringResource(R.string.scan_next)) }
    }
}
