package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import cn.coldlake.stellar.opensource.medialibrary.FloatingHomeHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MediaHomeHeader(
    state: TopAppBarState,
    scroll: LazyListState?,
    hasHero: Boolean,
    pageKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val statusBarHeight = WindowInsets.statusBars.getTop(LocalDensity.current)
    val overArtwork by remember(scroll, hasHero, statusBarHeight) {
        derivedStateOf {
            if (!hasHero || scroll == null || scroll.firstVisibleItemIndex != 0) false
            else {
                val info = scroll.layoutInfo
                val hero = info.visibleItemsInfo.firstOrNull { it.key == "hero" }
                // Match our hero's fade into the themed caption background. This is
                // a region change, not per-pixel brightness sampling or a new scrim.
                val fade = if (info.viewportSize.width < info.viewportSize.height * .75f) .56f else .8f
                hero == null || hero.offset + hero.size * fade > statusBarHeight
            }
        }
    }
    MediaStatusBar(pageKey, overArtwork, MaterialTheme.colorScheme.background)
    FloatingHomeHeader(state, modifier) {
        CompositionLocalProvider(LocalContentColor provides
            if (overArtwork) Color.White else MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.statusBarsPadding(), content = content)
        }
    }
}

/** Reuse the measured text for a crisp outline over arbitrary header artwork. */
@Composable internal fun MediaToolbarTitle(text: String, modifier: Modifier = Modifier, outlined: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleLarge) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val foreground = LocalContentColor.current
    val outline = (if (foreground.luminance() > .5f) Color.Black else Color.White).copy(alpha = .65f)
    Text(text, modifier = modifier.then(if (outlined) Modifier.drawBehind {
        layout?.let { drawText(it, color = outline, drawStyle = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)) }
    } else Modifier), color = foreground, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (outlined) layout = it })
}
