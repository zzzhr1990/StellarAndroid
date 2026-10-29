package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R

/** Material window width classes for the server home, derived from the current window,
 * so folding, split-screen and rotation re-layout without a device check. */
internal enum class MediaHomeWidth { COMPACT, MEDIUM, EXPANDED }

internal data class MediaHomeLayout(val width: MediaHomeWidth, val heroHeight: Dp, val gutter: Dp, val portraitHero: Boolean)

/** Below the hero, the first shelf heading and the top of its cards stay in view as a
 * cue that the page scrolls; short windows keep at least the heading. */
internal fun mediaHomePeek(height: Dp): Dp = if (height < 480.dp) 72.dp else 96.dp

/** [width] and [height] are the home viewport, so folding, split-screen and rotation
 * re-layout without a device check. */
@Composable internal fun mediaHomeLayout(width: Dp, height: Dp): MediaHomeLayout {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val widthClass = when { width < 600.dp -> MediaHomeWidth.COMPACT; width < 840.dp -> MediaHomeWidth.MEDIUM; else -> MediaHomeWidth.EXPANDED }
    // The artwork follows the window's shape, not its width: tall windows (phones, the
    // Fold cover, Flip main screens) take a full-bleed poster; squarish and wide ones
    // (Fold inner, wide folds, tablets, landscape, Flip cover) a backdrop.
    val portrait = width < height * .75f
    val heroHeight = (height - bottomInset - mediaHomePeek(height)).coerceAtLeast(240.dp)
    return MediaHomeLayout(widthClass, heroHeight, if (widthClass == MediaHomeWidth.COMPACT) 16.dp else 24.dp, portrait)
}

@Composable internal fun <T> MediaHomeHero(items: List<T>, scroll: LazyListState, verticalScroll: LazyListState, layout: MediaHomeLayout,
    key: (T) -> Any, tag: String, onSelect: (T) -> Unit,
    image: @Composable (T, Boolean, Modifier) -> Unit,
    caption: @Composable (T, Boolean, Dp, Dp, Modifier) -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val portrait = layout.portraitHero
    Box(Modifier.testTag(if (portrait) "$tag-portrait" else "$tag-landscape")) {
        val height = layout.heroHeight
        // Constrain the lazy viewport outside its pages. A full-width item plus snapping
        // gives page behavior without the previous nested, dynamically sized Pager tree.
        LazyRow(state = scroll, flingBehavior = rememberSnapFlingBehavior(scroll),
            modifier = Modifier.fillMaxWidth().height(height).testTag("$tag-pager")) {
            items(items, key = key, contentType = { "hero" }) { item ->
                // Size pages from the list's own viewport in the same pass: a width captured
                // from outside can lag one frame when rotating, which clamps a later page back
                // to the first when the window widens (e.g. cover screen turned sideways).
                Box(Modifier.fillParentMaxWidth().height(height).clipToBounds().clickable { onSelect(item) }.testTag("$tag-${key(item)}")) {
                    image(item, portrait, Modifier.fillMaxSize().graphicsLayer {
                        // Consume scroll in drawing only; image constraints/cache identity stay fixed.
                        translationY = if (verticalScroll.firstVisibleItemIndex == 0)
                            verticalScroll.firstVisibleItemScrollOffset * .5f else 0f
                    })
                    // A short top scrim keeps the floating toolbar legible on bright artwork;
                    // the long lower scrim gives the captions an opaque, readable base and
                    // dissolves the artwork into the peeking shelf below.
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .45f), 1f to Color.Transparent)))
                    // Posters often carry their own title art low down; in portrait the scrim
                    // is opaque under the captions so the two never read as a double title.
                    Box(Modifier.fillMaxSize().background(if (portrait) Brush.verticalGradient(
                        0f to Color.Transparent, .4f to Color.Transparent, .56f to background.copy(alpha = .85f),
                        .67f to background)
                    else Brush.verticalGradient(0f to Color.Transparent, .4f to Color.Transparent,
                        .8f to background.copy(alpha = .82f), 1f to background)))
                    // Wide backdrops keep their subject on the right; the left side darkens
                    // under the start-aligned captions.
                    if (!portrait) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        0f to background.copy(alpha = .78f), .45f to background.copy(alpha = .35f), .7f to Color.Transparent)))
                    caption(item, portrait, layout.gutter, height,
                        Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.BottomStart))
                }
            }
        }
        MediaHeroIndicator(scroll, items.size, Modifier.align(Alignment.BottomCenter), tag)
    }
}

@Composable internal fun MediaHeroCaption(title: String, kind: String, overview: String?, portrait: Boolean,
    gutter: Dp, heroHeight: Dp, modifier: Modifier,
    logo: (@Composable (Modifier) -> Unit)? = null, metadata: @Composable () -> Unit) {
    // Short heroes (landscape phones, the folded cover screen turned sideways) shrink the
    // Logo and overview so the captions never climb under the floating toolbar.
    val short = heroHeight < 340.dp
    val logoHeight = (heroHeight * .24f).coerceIn(56.dp, if (portrait) 88.dp else 105.dp)
    val align = if (portrait) Alignment.CenterHorizontally else Alignment.Start
    // Wide windows cap the caption measure so the overview stays a readable line length.
    Column(modifier.then(if (portrait) Modifier.fillMaxWidth() else Modifier.widthIn(max = 560.dp + gutter * 2))
        .padding(horizontal = gutter).padding(bottom = 30.dp), horizontalAlignment = align) {
        // Movies and series alternate in the hero; the kind sits above the title.
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .9f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(stringResource(if (kind == "series") R.string.emby_ui_hero_series else R.string.emby_ui_hero_movie),
                Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (logo != null) logo(
            Modifier.fillMaxWidth(if (portrait) .7f else .85f).height(logoHeight))
        else Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
            textAlign = if (portrait) TextAlign.Center else TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        metadata()
        if (!overview.isNullOrBlank()) Text(overview, maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (portrait) TextAlign.Center else TextAlign.Start, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable private fun MediaHeroIndicator(scroll: LazyListState, count: Int, modifier: Modifier, tag: String) {
    if (count < 2) return
    // Fractional page position, read only while drawing: swiping never recomposes or
    // re-measures the indicator, it just redraws the stretched active pill.
    fun position(): Float {
        val first = scroll.layoutInfo.visibleItemsInfo.firstOrNull()
        return if (first == null || first.size == 0) 0f else first.index - first.offset.toFloat() / first.size
    }
    val active = MaterialTheme.colorScheme.onSurface
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .3f)
    Spacer(modifier.padding(bottom = 10.dp).size(width = (6.dp * count) + (6.dp * (count - 1)) + 12.dp, height = 6.dp)
        .testTag("$tag-indicator").drawBehind {
            val dot = size.height; val gap = 6.dp.toPx(); val extra = 12.dp.toPx()
            val p = position().coerceIn(0f, (count - 1).toFloat())
            var x = 0f
            repeat(count) { i ->
                // The active page widens by up to 12dp, interpolated between neighbours.
                val w = dot + extra * (1f - kotlin.math.abs(p - i)).coerceIn(0f, 1f)
                drawRoundRect(if (kotlin.math.abs(p - i) < .5f) active else inactive,
                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, dot),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(dot / 2))
                x += w + gap
            }
        })
}
