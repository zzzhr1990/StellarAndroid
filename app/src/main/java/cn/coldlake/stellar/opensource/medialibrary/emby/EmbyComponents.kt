package cn.coldlake.stellar.opensource.medialibrary.emby

import cn.coldlake.stellar.opensource.medialibrary.*

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.BitmapImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.medialibrary.mediaCaptionMinHeight
import cn.coldlake.stellar.opensource.medialibrary.ScrollPosition
import cn.coldlake.stellar.opensource.medialibrary.artworkIconOutline
import cn.coldlake.stellar.opensource.medialibrary.rememberPageList

internal val LocalEmbyImageSession = staticCompositionLocalOf { "" }

@Composable internal fun EmbyToolbarTitle(text: String, modifier: Modifier = Modifier, outlined: Boolean = false) {
    MediaToolbarTitle(text, modifier, outlined)
}

@Composable internal fun EmbyIcon(icon: Int, label: Int, tag: String, click: () -> Unit,
    enabled: Boolean = true, tint: Color = LocalContentColor.current, outlined: Boolean = false,
    selected: Boolean = false) {
    val painter = painterResource(icon)
    val foreground = if (selected) MaterialTheme.colorScheme.primary else tint
    IconButton(onClick = click, enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(tag).semantics { this.selected = selected }) {
        Icon(painter, stringResource(label), Modifier.size(25.dp)
            .then(if (outlined) Modifier.artworkIconOutline(painter, foreground, enabled) else Modifier),
            tint = foreground.copy(alpha = if (enabled) foreground.alpha else foreground.alpha * .38f))
    }
}

@Composable internal fun EmbyImage(item: EmbyItem, controller: EmbyBrowserController, modifier: Modifier,
    type: String = "Primary", index: Int = 0, tag: String? = null, scale: ContentScale = ContentScale.Crop,
    onColor: ((Int) -> Unit)? = null, alignment: Alignment = Alignment.Center) {
    val changes=controller.imageChanges?.collectAsState()?.value
    val retries=controller.imageRetries?.collectAsState()?.value
    val session = LocalEmbyImageSession.current
    var imageGeneration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(changes, session) {
        if(changes!=null && (changes.namespace==null || changes.namespace==session))
            imageGeneration=changes.sequence
    }
    val imageKey = "$session:$imageGeneration:${item.id}:${item.imageId}:${item.imageTag}:${item.backdropTags}:${item.logoTag}:$type:$index:$tag"
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(imageKey) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(imageKey) { mutableStateOf(false) }
    val callback by rememberUpdatedState(onColor)
    val request = remember(imageKey,size,scale,onColor != null) {
        controller.imageRequest(item,size.width,size.height,type,index,tag,scale == ContentScale.Fit,onColor != null,session)
    }
    var failed by remember(request) { mutableStateOf(false) }
    var retryGeneration by remember(imageKey) { mutableLongStateOf(0L) }
    LaunchedEffect(retries, session) {
        // Only a new refresh event restarts failed artwork. Success/loading states keep
        // their painter, and a failed retry waits for the next explicit refresh.
        if (retries != null && retries.sequence > 0 && retries.namespace == session && failed) {
            failed = false
            retryGeneration++
        }
    }
    LaunchedEffect(bitmap,imageKey) {
        bitmap?.let { source ->
            val color = controller.imageColor(request ?: return@LaunchedEffect,source)
            callback?.invoke(color)
        }
    }
    Box(modifier.onSizeChanged { size=it }, contentAlignment = Alignment.Center) {
        if (!loaded && type != "Logo") Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(painterResource(when { item.type == "Person" -> R.drawable.material_symbol_account_circle
                item.folder -> R.drawable.material_symbol_folder; else -> R.drawable.material_symbol_movie }), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
        val loader=controller.imageLoader
        if(request!=null && loader!=null) key(imageGeneration, retryGeneration) { AsyncImage(model=request,imageLoader=loader,contentDescription=null,contentScale=scale,
            onLoading={ failed=false },
            onSuccess={ loaded=true; failed=false; if(onColor!=null) bitmap=(it.result.image as? BitmapImage)?.bitmap },
            onError={ loaded=false; failed=true },alignment=alignment,modifier=Modifier.fillMaxSize().testTag("emby-art-${item.id}-$type-$index")) }
    }
}

/** Horizontal content margin for shelves and headings. The server home widens it to the
 * Material window-class margin; detail pages keep the default. */
internal val LocalEmbyGutter = staticCompositionLocalOf { 16.dp }

/** Inside one series' own detail pages the series name on episode cards is redundant. */
internal val LocalEmbySeriesId = staticCompositionLocalOf { "" }

@Composable internal fun EmbyHeading(title: String, onClick: (() -> Unit)? = null) {
    cn.coldlake.stellar.opensource.medialibrary.MediaDetailHeading(title, onClick, LocalEmbyGutter.current)
}

internal val LocalEmbyRows = staticCompositionLocalOf<MutableMap<String, ScrollPosition>> { mutableMapOf() }

@Composable internal fun rememberEmbyRow(key: String): LazyListState {
    val positions = LocalEmbyRows.current
    return rememberPageList(positions.getOrPut(key) { ScrollPosition() })
}

@Composable internal fun EmbyShelfRow(items: List<EmbyItem>, controller: EmbyBrowserController, wide: Boolean = false,
    library: Boolean = false, rowKey: String, loading: Boolean = false, error: Boolean = false, fitStaticRow: Boolean = false) {
    if (items.isEmpty()) {
        EmbyShelfPlaceholder(wide, library, loading && !error, error)
        return
    }
    // If every card fits, the outer lazy list can precompose the entire row. A nested
    // LazyRow would defer its children until measure, even after the shelf data arrived.
    if (fitStaticRow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = LocalEmbyGutter.current).testTag("emby-row-$rowKey"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item -> key(item.id) {
                EmbyTile(item, controller, Modifier.width(if (wide) 168.dp else 112.dp), wide, library)
            } }
        }
        return
    }
    LazyRow(state = rememberEmbyRow(rowKey), contentPadding = PaddingValues(horizontal = LocalEmbyGutter.current), horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("emby-row-$rowKey")) {
        items(items, key = { it.id }, contentType = { "tile" }) { EmbyTile(it, controller, Modifier.width(if (wide) 168.dp else 112.dp), wide, library) }
    }
}

@Composable internal fun EmbyShelfPlaceholder(wide: Boolean = false, library: Boolean = false,
    loading: Boolean = true, error: Boolean = false) {
    val tileWidth = if (wide) 168.dp else 112.dp
    val artworkHeight = tileWidth / if (wide) (16f / 9f) else (2f / 3f)
    Column(Modifier.fillMaxWidth()) {
        // Reserve the tile's artwork and text geometry, but only draw skeletons while loading.
        // Empty/error text is centered in the full-width artwork slot, not the caption area.
        Box(Modifier.fillMaxWidth().height(artworkHeight).testTag("emby-shelf-artwork-slot"), contentAlignment = Alignment.Center) {
            if (loading) Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = false)
                .padding(horizontal = LocalEmbyGutter.current).testTag("emby-shelf-skeleton"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Spacer(Modifier.width(tileWidth).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh))
                }
            } else {
                Text(stringResource(if (error) R.string.emby_ui_partial_error else R.string.emby_empty),
                    Modifier.padding(horizontal = 24.dp).testTag(if (error) "emby-shelf-error" else "emby-empty"),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.padding(top = 5.dp).height(mediaCaptionMinHeight(MaterialTheme.typography.bodyMedium)))
        if (!library) Spacer(Modifier.height(mediaCaptionMinHeight(MaterialTheme.typography.labelMedium)))
    }
}

@Composable internal fun EmbyTile(item: EmbyItem, controller: EmbyBrowserController, modifier: Modifier = Modifier,
    wide: Boolean = false, library: Boolean = false, subtitle: String? = null) {
    Column(modifier.clickable { controller.select(item) }.testTag("emby-item-${item.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (item.type in setOf("Genre", "Tag")) 1f else if (wide) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(12.dp))) {
            EmbyImage(item, controller, Modifier.fillMaxSize())
            if (item.unplayed > 0 && !library) Surface(Modifier.align(Alignment.TopEnd).padding(5.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(item.unplayed.toString(), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (item.played && !library) Icon(painterResource(R.drawable.material_symbol_check_circle), stringResource(R.string.emby_ui_watched),
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp))
            if (item.resumeMs > 0 && item.durationMs != null) LinearProgressIndicator(
                progress = { (item.resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
        val sameSeries = item.type == "Episode" && item.seriesId.isNotBlank() && item.seriesId == LocalEmbySeriesId.current
        val seriesEpisode = wide && item.type == "Episode" && item.seriesName.isNotBlank()
        Text(when {
            wide && sameSeries -> stringResource(R.string.emby_ui_episode_name, item.seasonIndex ?: 0, item.index ?: 0, item.name)
            seriesEpisode -> item.seriesName
            item.type == "Episode" && item.index != null -> stringResource(R.string.emby_ui_episode_number, item.index, item.name)
            else -> item.name
        },
            // Reserve one line without clipping taller fallback glyphs.
            Modifier.padding(top = 5.dp).heightIn(min = mediaCaptionMinHeight(MaterialTheme.typography.bodyMedium)),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val caption = subtitle ?: when {
            library -> ""
            wide && sameSeries -> embyYears(item)
            seriesEpisode -> if (item.index != null) stringResource(R.string.emby_ui_episode_name, item.seasonIndex ?: 0, item.index, item.name) else item.name
            else -> embyYears(item)
        }
        if (!library) Text(caption, Modifier.heightIn(min = mediaCaptionMinHeight(MaterialTheme.typography.labelMedium)),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun embyYears(item: EmbyItem): String = when {
    item.year == null -> ""
    item.type == "Series" && item.status == "Continuing" -> stringResource(R.string.emby_ui_present, item.year)
    item.endYear != null && item.endYear != item.year -> "${item.year}–${item.endYear}"
    else -> item.year.toString()
}

@Composable internal fun EmbyEpisodeRow(item: EmbyItem, controller: EmbyBrowserController) {
    cn.coldlake.stellar.opensource.medialibrary.MediaDetailEpisodeRow(
        title = if (item.index != null) stringResource(R.string.emby_ui_episode_number, item.index, item.name) else item.name,
        modifier = Modifier.clickable { controller.select(item) }.testTag("emby-item-${item.id}"),
        image = { EmbyImage(item, controller, it) }) {
            item.durationMs?.let { Text(stringResource(R.string.emby_minutes, it / 60000), style = MaterialTheme.typography.labelMedium) }
            Text(item.overview, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun EmbyEmpty() { Text(stringResource(R.string.emby_empty), Modifier.padding(24.dp).testTag("emby-empty"), color = MaterialTheme.colorScheme.onSurfaceVariant) }

internal typealias EmbyHomeWidth = MediaHomeWidth
internal typealias EmbyHomeLayout = MediaHomeLayout
internal fun embyHomePeek(height: Dp) = mediaHomePeek(height)
@Composable internal fun embyHomeLayout(width: Dp, height: Dp) = mediaHomeLayout(width, height)

/** The window shape picks the hero artwork type; the other type is only a fallback. */
internal fun embyHeroArtwork(item: EmbyItem, portrait: Boolean): String =
    if (portrait) { if (item.imageTag.isNotEmpty() || item.backdropTags.isEmpty()) "Primary" else "Backdrop" }
    else { if (item.backdropTags.isNotEmpty()) "Backdrop" else "Primary" }

@Composable internal fun EmbyHomeHero(items: List<EmbyItem>, controller: EmbyBrowserController,
    position: ScrollPosition, verticalScroll: LazyListState, layout: EmbyHomeLayout) {
    MediaHomeHero(items, rememberPageList(position), verticalScroll, layout, key = { it.id }, tag = "emby-hero",
        onSelect = controller::select, image = { item, portrait, modifier ->
            EmbyImage(item, controller, modifier, type = embyHeroArtwork(item, portrait),
                alignment = if (portrait) Alignment.TopCenter else Alignment.Center)
        }, caption = { item, portrait, gutter, height, modifier ->
            MediaHeroCaption(item.name, item.type.lowercase(), item.overview, portrait, gutter, height, modifier,
                logo = if (item.logoTag.isNotEmpty()) { m -> EmbyImage(item, controller, m, "Logo", scale = ContentScale.Fit,
                    alignment = if (portrait) Alignment.BottomCenter else Alignment.CenterStart) } else null) {
                EmbyMetadata(item, hero = true, centered = portrait)
            }
        })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EmbyMetadata(item: EmbyItem, hero: Boolean = false, centered: Boolean = false) {
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Each wrapped line shares the first text baseline, including the smaller rating badge.
        val metadataAlignment = Modifier.alignByBaseline()
        item.rating?.let { Text("★" + String.format(locale, "%.1f", it), metadataAlignment, color = cn.coldlake.stellar.opensource.medialibrary.MediaDetailRatingColor, style = MaterialTheme.typography.bodyMedium) }
        if (!hero && item.type != "Series" && item.premiere.isNotBlank()) Text(embyDate(item.premiere), metadataAlignment, style = MaterialTheme.typography.bodyMedium)
        else if (item.year != null) Text(embyYears(item), metadataAlignment, style = MaterialTheme.typography.bodyMedium)
        if (!hero && item.type != "Series") item.durationMs?.let { Text(stringResource(R.string.emby_minutes, it / 60000), metadataAlignment, style = MaterialTheme.typography.bodyMedium) }
        if (hero && item.genres.isNotEmpty()) Text(item.genres.take(2).joinToString(" · "), metadataAlignment, style = MaterialTheme.typography.bodyMedium)
        if (item.officialRating.isNotEmpty()) cn.coldlake.stellar.opensource.medialibrary.MediaDetailCertification(item.officialRating, metadataAlignment)
        if (!hero && item.type == "Series" && item.seasonCount > 0) Text(pluralStringResource(R.plurals.emby_ui_season_count, item.seasonCount, item.seasonCount), metadataAlignment, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun embyDate(value: String): String = cn.coldlake.stellar.opensource.medialibrary.mediaDetailDate(value)

internal fun embyTime(ms: Long): String = cn.coldlake.stellar.opensource.medialibrary.mediaDetailTime(ms)
