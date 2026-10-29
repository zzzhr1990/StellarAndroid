package cn.coldlake.stellar.opensource.medialibrary.emby

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.*
import cn.coldlake.stellar.opensource.settings.rememberRightHanded

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EmbyDetails(state: EmbyBrowserState, controller: EmbyBrowserController, onPlay: (EmbyPlayback) -> Unit,
    list: LazyListState, toolbar: @Composable (Color) -> Unit = {}) {
    val item = state.detail ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Re-evaluate the logical width when density changes without new pixel constraints.
    val width = with(LocalDensity.current) { constraints.maxWidth.toDp() }
    val person = item.type == "Person"
    val wide = width >= 700.dp
    val rightHanded = rememberRightHanded()
    val layout = if (person) mediaDetailLayout(width) else mediaVideoDetailLayout(width, rightHanded)
    val actionWidth = mediaDetailActionWidth(width, layout)
    val artworkType = if (item.type == "Person") "Primary" else embyHeroArtwork(item, layout.portraitHeader)
    val context = LocalContext.current
    var gallery by rememberSaveable { mutableIntStateOf(-1) }
    var options by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf("") }
    var linkFailure by remember { mutableStateOf(false) }
    val artwork = remember(item.id, item.imageId, item.imageTag, item.backdropId, item.backdropTags, artworkType) {
        "${item.id}:${item.imageId}:${item.imageTag}:${item.backdropId}:${item.backdropTags}:$artworkType"
    }
    val sampledColor = state.detailColor?.takeIf { it.first == artwork }?.second
    val cast = remember(item.people) { item.people.distinctBy { Triple(it.id, it.type, it.role) } }
    val groups = remember(state.children) { state.children.groupBy { it.type } }
    val childrenPending = state.childrenLoading || (!state.childrenLoaded && state.childrenError != null)
    val relatedPending = state.relatedLoading || (!state.relatedLoaded && state.relatedError != null)
    val source = item.sources.firstOrNull { it.id == state.selectedVersion } ?: item.sources.firstOrNull()
    MediaDetailTheme(sampledColor) { color, bottomColor ->
    val inset = layout.inset
    val seriesId = when (item.type) { "Series" -> item.id; "Season", "Episode" -> item.seriesId; else -> "" }
    CompositionLocalProvider(LocalMediaDetailLayout provides layout, LocalEmbyGutter provides inset, LocalEmbySeriesId provides seriesId) {
    MediaDetailPage(list, color, bottomColor, Modifier.testTag("emby-detail"),
        logoWidth = if (person) null else actionWidth,
        image = { modifier ->
            EmbyImage(item, controller, modifier, artworkType,
                onColor = { controller.detailColor(state.pageKey, artwork, it) },
                alignment = if (layout.portraitHeader) Alignment.TopCenter else Alignment.Center)
        }, logo = if (!person && item.logoTag.isNotEmpty()) { modifier ->
            EmbyImage(item, controller, modifier.testTag("emby-detail-logo"), "Logo", scale = ContentScale.Fit,
                alignment = if (layout.portraitHeader) Alignment.Center else androidx.compose.ui.AbsoluteAlignment.CenterLeft)
        } else if (!person) { modifier ->
            MediaDetailHeroTitle(if (item.type in setOf("Season", "Episode")) item.seriesName.ifBlank { item.name } else item.name,
                modifier.testTag("emby-detail-title"))
        } else null, toolbar = { toolbar(color) }) {
        if (person) {
        item(key = "metadata", contentType = "metadata") {
            MediaDetailMetadata(item.name, person = true, modifier = Modifier.testTag("emby-detail-title")) {
                if (item.premiere.isNotBlank() && item.birthPlaces.isNotEmpty()) Text(stringResource(R.string.emby_ui_born_at, embyDate(item.premiere), item.birthPlaces.joinToString()), style = MaterialTheme.typography.bodyMedium)
                else if (item.premiere.isNotBlank()) Text(stringResource(R.string.emby_ui_born, embyDate(item.premiere)))
                else if (item.birthPlaces.isNotEmpty()) Text(stringResource(R.string.emby_ui_birth_place, item.birthPlaces.joinToString()))
            }
        }
        if (item.overview.isNotBlank() || item.tagline.isNotBlank()) item(key = "overview", contentType = "text") {
            MediaDetailOverview(item.overview, "emby-overview", "emby-full-overview", item.tagline, 5)
        }
        } else item(key = "summary", contentType = "summary") {
            EmbyDetailSummary(state, controller, onPlay, wide, actionWidth, onMore = { options = true })
        }
        if (item.type == "Series" && (state.related.isNotEmpty() || relatedPending)) item(key = "continue", contentType = "shelf") {
            EmbyHeading(stringResource(R.string.emby_continue)); EmbyShelfRow(state.related, controller, wide = true, rowKey = "related",
                loading = state.relatedLoading, error = state.relatedError != null)
        }
        if (item.type == "Season") {
            items(state.children, key = { "episode:${it.id}" }, contentType = { "episode" }) { EmbyEpisodeRow(it, controller) }
        } else if (state.children.isNotEmpty() || childrenPending) {
            if (item.type == "Person") {
                for (type in listOf("Movie", "Series", "Episode")) {
                    val children = groups[type].orEmpty()
                    if(children.isNotEmpty() || childrenPending) item(key = "works:$type", contentType = "shelf") {
                    EmbyHeading(stringResource(if (type == "Movie") R.string.emby_ui_movies else if (type == "Series") R.string.emby_ui_shows else R.string.emby_ui_episodes)) {
                        controller.navigate(EmbyLocation(EmbySection.RELATED, title = item.name, person = item.id, itemTypes = type))
                    }
                    EmbyShelfRow(children, controller, wide = type == "Episode", rowKey = "works:$type",
                        loading = state.childrenLoading, error = state.childrenError != null)
                }
                }
            } else item(key = "children", contentType = "shelf") {
                EmbyHeading(stringResource(if (item.type == "Series") R.string.emby_ui_seasons else R.string.emby_ui_all))
                EmbyShelfRow(state.children, controller, rowKey = "children",
                    loading = state.childrenLoading, error = state.childrenError != null)
            }
        }
        if (state.childTotal > state.children.size) item(key = "children-more", contentType = "button") {
            TextButton(onClick = { controller.navigate(if (item.type == "Person") EmbyLocation(EmbySection.RELATED, title = item.name, person = item.id) else EmbyLocation(EmbySection.FOLDER, item)) }) { Text(stringResource(R.string.emby_ui_view_all)) }
        }
        if (item.type == "Episode" && (state.related.isNotEmpty() || relatedPending)) item(key = "from-season", contentType = "shelf") {
            EmbyHeading(stringResource(R.string.emby_ui_from_season)); EmbyShelfRow(state.related, controller, wide = true, rowKey = "related",
                loading = state.relatedLoading, error = state.relatedError != null)
        }
        if (cast.isNotEmpty()) item(key = "cast", contentType = "shelf") {
            EmbyHeading(stringResource(R.string.emby_ui_cast))
            LazyRow(state = rememberEmbyRow("cast"), contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("emby-cast")) {
                items(cast, key = { "${it.id}:${it.type}:${it.role}" }, contentType = { "person" }) { person ->
                    val credit = remember(person) { person.asItem() }
                    MediaDetailCredit(person.name, person.role.ifEmpty { mediaDetailCreditRole(person.type) },
                        Modifier.width(112.dp).clickable { controller.select(credit) }.testTag("emby-item-${person.id}"),
                        image = { EmbyImage(credit, controller, it) })
                }
            }
        }
        if (item.backdropTags.isNotEmpty()) item(key = "gallery", contentType = "gallery") {
            EmbyHeading(stringResource(R.string.emby_ui_gallery))
            LazyRow(state = rememberEmbyRow("gallery"), contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(item.backdropTags.size) { n -> EmbyImage(item, controller, Modifier.width(270.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                    .clickable { gallery = n }.testTag("emby-gallery-$n"), "Backdrop", n) }
            }
        }
        if (item.studios.isNotEmpty()) item(key = "studios", contentType = "text") {
            EmbyHeading(stringResource(R.string.emby_ui_studios))
            FlowRow(Modifier.padding(horizontal = inset).testTag("emby-studios"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.studios.forEach { studio -> Text(studio.name, textDecoration = TextDecoration.Underline,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.alignByBaseline().clickable { controller.navigate(EmbyLocation(EmbySection.RELATED, title = studio.name, studio = studio.name)) }.padding(vertical = 5.dp)) }
            }
        }
        if (item.tags.isNotEmpty()) item(key = "tags", contentType = "text") {
            EmbyHeading(stringResource(R.string.emby_ui_tags))
            FlowRow(Modifier.padding(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(8.dp)) { item.tags.forEach { tag ->
                AssistChip(onClick = { controller.navigate(EmbyLocation(EmbySection.RELATED, title = tag, tag = tag)) }, label = { Text(tag) })
            } }
        }
        if (item.type == "Movie" && (state.related.isNotEmpty() || relatedPending)) item(key = "similar", contentType = "shelf") {
            EmbyHeading(stringResource(R.string.emby_ui_similar)); EmbyShelfRow(state.related, controller, rowKey = "related",
                loading = state.relatedLoading, error = state.relatedError != null)
        }
        if (item.links.isNotEmpty()) item(key = "links", contentType = "links") {
            EmbyHeading(stringResource(R.string.emby_ui_links))
            LazyRow(contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(item.links) { link ->
                MediaDetailExternalLink(link.name, link.url) {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } catch (_: Exception) { linkFailure = true }
                }
            } }
        }
        if (item.sources.isNotEmpty()) {
            item(key = "media-heading", contentType = "heading") { EmbyHeading(stringResource(R.string.emby_ui_media_info)) }
            items(item.sources, key = { "media:${it.id}" }, contentType = { "media" }) { media ->
                val server = if (state.profile?.kind == cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind.JELLYFIN) "Jellyfin" else "Emby"
                val serverLabel = stringResource(R.string.detail_file_server, server)
                val name = state.profile?.name.orEmpty()
                val location = if (name.isBlank() || name.equals(server, true)) serverLabel
                    else stringResource(R.string.detail_file_named_source, serverLabel, name)
                MediaDetailFileInformation(location, media.fileName.ifBlank { media.name }, media.container,
                    media.size.takeIf { it > 0 }, Modifier.testTag("emby-file-information-${media.id}"), added = item.dateCreated)
            }
        }
    }
    }
    if (linkFailure) AlertDialog(onDismissRequest = { linkFailure = false }, text = { Text(stringResource(R.string.emby_ui_no_links)) }, confirmButton = {
        TextButton(onClick = { linkFailure = false }) { Text(stringResource(R.string.emby_ui_close)) }
    })
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(stringResource(R.string.emby_ui_more)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (item.playable || state.nextEpisode != null) TextButton(onClick = { options = false; controller.play(false, onPlay) }, modifier = Modifier.testTag("emby-restart")) { Text(stringResource(R.string.emby_ui_menu_restart)) }
            if (item.playable && source != null) {
            Column {
                MediaDetailChoice(source.name, source.tracks.firstOrNull { it.type == "Video" }?.title.orEmpty() + " / " + Formatter.formatFileSize(context, source.size),
                    R.drawable.material_symbol_movie, "emby-source") { controller.loadVersions(); options = false; chooser = "version" }
                source.tracks.filter { it.type == "Audio" }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    val selected = tracks.firstOrNull { it.index == state.selectedAudio } ?: tracks.first()
                    MediaDetailChoice(selected.title, selected.language, R.drawable.material_symbol_play_circle, "emby-audio") { options = false; chooser = "Audio" }
                }
                source.tracks.filter { it.type == "Subtitle" }.takeIf { it.isNotEmpty() }?.let { tracks ->
                    val selected = tracks.firstOrNull { it.index == state.selectedSubtitle }
                    MediaDetailChoice(selected?.title ?: stringResource(R.string.emby_ui_none), selected?.language.orEmpty(), R.drawable.material_symbol_subtitles, "emby-subtitle") { options = false; chooser = "Subtitle" }
                }
            }
            }
            TextButton(onClick = { options = false; controller.flag(true) }) { Text(stringResource(if (item.favorite) R.string.emby_ui_favorite_remove else R.string.emby_ui_favorite_add)) }
            TextButton(onClick = { options = false; controller.refresh() }) { Text(stringResource(R.string.emby_refresh)) }
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text(stringResource(R.string.emby_ui_close)) } })
    if (chooser.isNotEmpty()) AlertDialog(onDismissRequest = { chooser = "" }, title = {
        Text(stringResource(when (chooser) { "version" -> R.string.emby_version; "Audio" -> R.string.emby_ui_audio; else -> R.string.emby_ui_subtitle }))
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (state.versionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (chooser == "version") state.versions.forEach { version ->
                TextButton(onClick = { controller.selectVersion(version.id); chooser = "" }, enabled = version.streamable && !state.versionsLoading, modifier = Modifier.testTag("emby-version-${version.id}")) { Text(version.name) }
            } else {
                if (chooser == "Subtitle") TextButton(onClick = { controller.selectTrack(false, -1); chooser = "" }) { Text(stringResource(R.string.emby_ui_none)) }
                source?.tracks?.filter { it.type == chooser }?.forEach { track ->
                    TextButton(onClick = { controller.selectTrack(chooser == "Audio", track.index); chooser = "" }, enabled = !track.external,
                        modifier = Modifier.testTag("emby-track-${track.index}")) { Text(track.title + if (track.external) " · " + stringResource(R.string.emby_ui_external_subtitle) else "") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooser = "" }) { Text(stringResource(R.string.emby_ui_close)) } })
    if (gallery >= 0) Dialog(onDismissRequest = { gallery = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("emby-gallery-viewer")) {
            EmbyImage(item, controller, Modifier.fillMaxSize(), "Backdrop", gallery, scale = ContentScale.Fit)
            Box(Modifier.statusBarsPadding()) { EmbyIcon(R.drawable.material_symbol_close, R.string.emby_ui_close, "emby-gallery-close", { gallery = -1 }, tint = Color.White, outlined = true) }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                EmbyIcon(R.drawable.material_symbol_arrow_back, R.string.emby_ui_previous, "emby-gallery-prev", { gallery-- }, gallery > 0, Color.White, outlined = true)
                Text(stringResource(R.string.emby_ui_image_count, gallery + 1, item.backdropTags.size), color = Color.White)
                EmbyIcon(R.drawable.material_symbol_chevron_right, R.string.emby_ui_next, "emby-gallery-next", { gallery++ }, gallery < item.backdropTags.lastIndex, Color.White, outlined = true)
            }
        }
    }
    }
    }
}
