package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*
import com.stellar.player.ui.LocalPageActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable internal fun libraryHomeHero(runtime: LocalLibraryRuntime, source: String?, refresh: Int): LibraryHeroWindow {
    val presentation: LibraryPresentation = viewModel()
    val window = presentation.hero(source)
    var ids by rememberSaveable(source) { mutableStateOf<List<Long>>(emptyList()) }
    var generation by rememberSaveable(source) { mutableIntStateOf(refresh) }
    val updates = remember(runtime, source) { runtime.homeUpdates.map { it.revision(HomeSection.RECENT, source) }.distinctUntilChanged() }
    val initialRevision = remember(updates) { runtime.homeUpdates.value.revision(HomeSection.RECENT, source) }
    val revision by updates.collectAsStateWithLifecycle(initialRevision)
    val active = LocalPageActive.current
    LaunchedEffect(source, revision, refresh, active) {
        if (!active || (window.loaded && window.revision == revision && generation == refresh)) return@LaunchedEffect
        try {
            val replacement = ids.isEmpty() || refresh != generation
            val previous = window.rows.associateBy { it.card.id }
            window.rows = runtime.home {
                val cards = if (replacement) (page(HomeSection.MOVIES, source, limit = 4) + page(HomeSection.SERIES, source, limit = 4))
                    .sortedByDescending { it.order }
                    else byIds(ids, source)
                cards.map { card ->
                    val revision = detailRevisions(card.id)?.first ?: -1
                    LibraryHero(card, previous[card.id]?.takeIf { it.contentRevision == revision }?.details ?: details(card.id, source), revision)
                }
            }
            ids = ArrayList(window.rows.map { it.card.id }); generation = refresh
            window.loaded = true; window.revision = revision
        } catch (c: CancellationException) { throw c } catch (_: Exception) { /* Keep the last useful local snapshot. */ }
    }
    return window
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun LibraryHomeHero(window: LibraryHeroWindow, runtime: LocalLibraryRuntime,
    scroll: LazyListState, verticalScroll: LazyListState, layout: MediaHomeLayout, onCard: (Long) -> Unit) {
    MediaHomeHero(window.rows, scroll, verticalScroll, layout, key = { it.card.id }, tag = "home-hero",
        onSelect = { onCard(it.card.id) }, image = { hero, portrait, modifier ->
            val art = libraryPresentation(hero.card, hero.details, emptyList()).heroArtwork(portrait) as? MediaPresentationImage.Library
            art?.artwork?.let { image -> LibraryPoster(hero.card.card.copy(poster = image.uri, posterVersion = image.version), runtime,
                modifier, alignment = if (portrait) Alignment.TopCenter else Alignment.Center) }
        }, caption = { hero, portrait, gutter, height, modifier ->
            val card = hero.card.card
            MediaHeroCaption(card.title, card.kind, card.plot, portrait, gutter, height, modifier,
                logo = hero.details.logo?.let { logo -> { m ->
                    LibraryPoster(card.copy(poster = logo.uri, posterVersion = logo.version), runtime, m,
                        scale = ContentScale.Fit, alignment = if (portrait) Alignment.BottomCenter else Alignment.CenterStart)
                } }) {
                FlowRow(horizontalArrangement = if (portrait) Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(10.dp)) {
                    card.year?.let { Text(it.toString(), Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium) }
                    if (card.kind == "series") Text(stringResource(R.string.home_episode_count, hero.card.episodes), Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium)
                    hero.details.genres.take(2).forEach { Text(it, Modifier.alignByBaseline(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        })
}
