package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.coldlake.stellar.opensource.R

/** Presentation only. Callers own identities, requests, navigation and playback state. */
internal val MediaDetailRatingColor = Color(0xffff6d62)

/** Keep transparent controls readable over artwork without a blurred/offscreen layer. */
internal fun Modifier.artworkIconOutline(painter: Painter, foreground: Color, enabled: Boolean = true): Modifier = drawBehind {
    val outline = if (foreground.luminance() > .5f) Color.Black else Color.White
    val filter = ColorFilter.tint(outline.copy(alpha = if (enabled) .65f else .25f))
    val radius = 1.dp.toPx()
    for (x in -1..1) for (y in -1..1) if (x != 0 || y != 0) {
        translate(x * radius, y * radius) { with(painter) { draw(size, colorFilter = filter) } }
    }
}

internal fun mediaDetailColor(sample: Int, base: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(sample, hsv)
    hsv[1] = if (base.luminance() < .5f) hsv[1].coerceIn(.40f, .65f) else hsv[1].coerceIn(.08f, .15f)
    hsv[2] = if (base.luminance() < .5f) .42f else .97f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Window-class geometry for detail pages. [inset] is the start/end inset of text blocks
 * and of shelf content; wide windows centre a [contentWidth] column inside the full-bleed
 * background, so shelves still scroll edge to edge but start on the text column.
 * The default keeps the original 16dp phone layout for callers that do not opt in. */
internal data class MediaDetailLayout(val inset: Dp = 16.dp, val portraitHeader: Boolean = false,
    val actionsOnRight: Boolean = false)

internal val LocalMediaDetailLayout = staticCompositionLocalOf { MediaDetailLayout() }

internal fun mediaDetailLayout(width: Dp): MediaDetailLayout {
    val compact = width < 600.dp
    val gutter = if (compact) 16.dp else 24.dp
    // Medium/expanded windows: a readable ~720dp column, centred.
    val inset = if (compact) gutter else maxOf(gutter, (width - 720.dp) / 2)
    return MediaDetailLayout(inset, portraitHeader = compact)
}

internal fun mediaVideoDetailLayout(width: Dp, rightHanded: Boolean) = MediaDetailLayout(
    inset = if (width < 600.dp) 16.dp else maxOf(24.dp, (width - 1200.dp) / 2),
    portraitHeader = width < 700.dp, actionsOnRight = rightHanded)

internal fun mediaDetailActionWidth(width: Dp, layout: MediaDetailLayout): Dp {
    val available = width - layout.inset * 2
    return if (!layout.portraitHeader) (available * .4f).coerceIn(280.dp, 400.dp)
        else minOf(340.dp, available * .9f)
}

/** Change placement without recreating either group; text keeps the app's direction. */
@Composable internal fun MediaDetailSummary(wide: Boolean, actionWidth: Dp,
    controls: @Composable (Modifier) -> Unit, information: @Composable (Modifier) -> Unit) {
    val layout = LocalMediaDetailLayout.current
    val direction = LocalLayoutDirection.current
    val modifier = Modifier.fillMaxWidth().padding(horizontal = layout.inset, vertical = 4.dp)
    if (wide) CompositionLocalProvider(LocalLayoutDirection provides
        if (layout.actionsOnRight) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                controls(Modifier.width(actionWidth))
                information(Modifier.weight(1f))
            }
        }
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        controls(Modifier.width(actionWidth))
        information(Modifier.fillMaxWidth())
    }
}

@Composable internal fun MediaDetailList(list: LazyListState, color: Color, modifier: Modifier = Modifier,
    bottomColor: Color? = null,
    content: LazyListScope.() -> Unit) {
    // Fold-size vertical stretch allocates a full-page GPU layer at the boundary.
    // Keep horizontal shelves' own overscroll; only this measured vertical path opts out.
    LazyColumn(state = list, overscrollEffect = null,
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = modifier.fillMaxSize().background(Brush.verticalGradient(
            if (bottomColor != null) listOf(color, bottomColor) else listOf(color, color, MaterialTheme.colorScheme.background))),
        content = {
            content()
            // Emby is edge-to-edge; local details already consume the system inset
            // in their parent. The size modifier adds only the remaining inset.
            item(key = "media-detail-bottom-inset", contentType = "system-inset") {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        })
}

@Composable internal fun MediaDetailHeader(list: LazyListState? = null,
    statusBarPadding: Boolean = true,
    logoWidth: Dp? = null,
    image: @Composable (Modifier) -> Unit, logo: (@Composable (Modifier) -> Unit)? = null) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val inset = if (statusBarPadding) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 0.dp
    val layout = LocalMediaDetailLayout.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = with(LocalDensity.current) { constraints.maxWidth.toDp() }
        val portrait = layout.portraitHeader
        // Portrait: poster hero like the server home, leaving the title/actions reachable.
        // Wide: 16:9 backdrop, capped so the play action stays near the first screen.
        val expanded = if (portrait) minOf(width * 1.08f, windowHeight * .52f)
            else if (width < 600.dp) windowHeight * .4f
            else (width * 9 / 16).coerceIn(windowHeight * .4f, windowHeight * .6f)
        Box(Modifier.fillMaxWidth().height(expanded + inset).clipToBounds().testTag("detail-expanded-header")) {
            // Fade the image into the list's actual background, not a separate solid
            // colour. Only the bitmap moves with parallax; the mask ends at the header
            // boundary at every scroll offset. The offscreen target is header-sized.
            Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val mask = Brush.verticalGradient(0f to Color.Black, .6f to Color.Black,
                        (if (logo != null) .9f else 1f) to Color.Transparent, 1f to Color.Transparent)
                    onDrawWithContent {
                        drawContent()
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                // Same bitmap/constraints throughout the gesture; only its drawing moves.
                translationY = if (list?.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset * .5f else 0f
            }) {
                image(Modifier.fillMaxSize())
            }
            }
            val logoHeight = (expanded * .24f).coerceIn(64.dp, 120.dp)
            logo?.invoke(if (logoWidth != null) Modifier.align(if (portrait) Alignment.BottomCenter
                    else AbsoluteAlignment.BottomLeft)
                    .padding(horizontal = if (portrait) 0.dp else layout.inset).padding(bottom = 12.dp)
                    .width(logoWidth).height(logoHeight)
                else if (portrait) Modifier.align(Alignment.BottomCenter).padding(horizontal = layout.inset, vertical = 12.dp)
                    .fillMaxWidth(.7f).height(logoHeight)
                else Modifier.align(Alignment.BottomStart).padding(horizontal = layout.inset, vertical = 16.dp)
                    .widthIn(max = 560.dp).fillMaxWidth(.85f).height(logoHeight))
        }
    }
}

@Composable internal fun MediaDetailMetadata(title: String, modifier: Modifier = Modifier, person: Boolean = false,
    hasLogo: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val layout = LocalMediaDetailLayout.current
    val centred = layout.portraitHeader && hasLogo
    Column(Modifier.fillMaxWidth().padding(horizontal = layout.inset), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
        // With a Logo the name is already shown as artwork: keep it, smaller, for reading and a11y.
        Text(title, style = when { person -> MaterialTheme.typography.headlineLarge
                hasLogo -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.headlineSmall },
            fontWeight = if (hasLogo && !person) FontWeight.Medium else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (hasLogo && !person) LocalContentColor.current.copy(alpha = .8f) else Color.Unspecified, modifier = modifier)
        content()
    }
}

@Composable internal fun MediaDetailSources(color: Color, containerColor: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val accent = remember(color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = .30f; hsv[2] = 1f
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    Column(Modifier.padding(horizontal = LocalMediaDetailLayout.current.inset, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
        .background(containerColor ?: accent.copy(alpha = .10f)).padding(horizontal = 12.dp), content = content)
}

@Composable internal fun MediaDetailChoice(title: String, subtitle: String, icon: Int, tag: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.material_symbol_chevron_right), null)
    }
}

@Composable internal fun MediaDetailActions(positionMs: Long, durationMs: Long?, enabled: Boolean,
    playTag: String, onPlay: () -> Unit, onMore: () -> Unit, moreTag: String,
    restartTag: String, onRestart: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.padding(horizontal = LocalMediaDetailLayout.current.inset, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaybackProgressButton(onClick = onPlay, enabled = enabled, positionMs = positionMs, durationMs = durationMs,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(playTag)) {
                Icon(painterResource(R.drawable.material_symbol_play_arrow), null)
                Spacer(Modifier.width(6.dp))
                Text(if (positionMs > 0) stringResource(R.string.emby_resume_at, mediaDetailTime(positionMs))
                    else stringResource(R.string.emby_ui_play))
            }
            FilledTonalIconButton(onClick = onMore,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = .18f), contentColor = LocalContentColor.current),
                modifier = Modifier.size(48.dp).testTag(moreTag)) {
                Icon(painterResource(R.drawable.material_symbol_more_vert), stringResource(R.string.emby_ui_more))
            }
        }
        if (onRestart != null) TextButton(onClick = onRestart, enabled = enabled, modifier = Modifier.testTag(restartTag)) { Text(stringResource(R.string.emby_restart)) }
        content()
    }
}

internal fun mediaDetailTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(java.util.Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
}

internal fun mediaDetailDate(value: String): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd", java.util.Locale.getDefault()))
}.getOrElse { value.take(10).replace('-', '/') }

@Composable internal fun MediaDetailCertification(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier.border(1.dp, LocalContentColor.current, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable internal fun MediaDetailCredit(name: String, role: String, modifier: Modifier = Modifier,
    image: (@Composable (Modifier) -> Unit)? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        image?.invoke(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
        Text(name, Modifier.padding(top = 5.dp).heightIn(min = mediaCaptionMinHeight(MaterialTheme.typography.bodyMedium)),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(role, Modifier.heightIn(min = mediaCaptionMinHeight(MaterialTheme.typography.labelMedium)),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun mediaDetailCreditRole(type: String): String = stringResource(when (type) {
    "Actor" -> R.string.home_credit_actor
    "Director" -> R.string.emby_ui_director
    "Writer" -> R.string.home_credit_writer
    else -> R.string.home_credit_crew
})

@Composable internal fun MediaDetailOverview(text: String, tag: String, fullTag: String, tagline: String = "", maxLines: Int = 3) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = LocalMediaDetailLayout.current.inset, vertical = 8.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tagline.isNotBlank()) Text(tagline, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(stringResource(R.string.emby_ui_overview)) },
        text = { Text(text.ifBlank { tagline }, Modifier.verticalScroll(rememberScrollState()).testTag(fullTag)) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.emby_ui_close)) } })
}

@Composable internal fun MediaDetailHeading(title: String, onClick: (() -> Unit)? = null, gutter: Dp = LocalMediaDetailLayout.current.inset) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(start = gutter, end = gutter - 4.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(painterResource(R.drawable.material_symbol_chevron_right), stringResource(R.string.emby_ui_view_all))
    }
}

@Composable internal fun MediaDetailEpisodeRow(title: String, modifier: Modifier = Modifier,
    image: @Composable (Modifier) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = LocalMediaDetailLayout.current.inset, vertical = 16.dp)) {
        val imageWidth = minOf(144.dp, maxWidth * .42f)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            image(Modifier.width(imageWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                content()
            }
        }
    }
}

@Composable internal fun MediaDetailToolbarBackground(list: LazyListState?, modifier: Modifier,
    statusBarPadding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val transparent by remember(list) { derivedStateOf { list != null && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 200 } }
    Column(modifier.fillMaxWidth().background(if (transparent) Color.Transparent else MaterialTheme.colorScheme.background)
        .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier), content = content)
}
