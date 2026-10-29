package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A pinned detail toolbar, independent of the floating home toolbar and business state. */
@Composable internal fun MediaDetailTopBar(
    list: LazyListState?, color: Color, title: String, modifier: Modifier = Modifier,
    statusBarPadding: Boolean = true,
    statusBarAppearance: (@Composable (collapsed: Boolean) -> Unit)? = null,
    actionsWhenCollapsed: Boolean = false,
    logo: (@Composable (Modifier) -> Unit)? = null,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    notices: @Composable ColumnScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val inset = if (statusBarPadding) WindowInsets.statusBars.getTop(density) else 0
    val toolbarBottom = with(density) { 56.dp.toPx() } + inset
    // Use the real header geometry, including the host's insets. No physical-pixel threshold.
    val collapsed by remember(list, toolbarBottom) { derivedStateOf {
        if (list == null) true else {
            val header = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header" }
            if (header == null) list.layoutInfo.totalItemsCount > 0
            else header.offset + header.size < toolbarBottom
        }
    } }
    statusBarAppearance?.invoke(collapsed)
    val opacity = animateFloatAsState(if (collapsed) 1f else 0f, tween(300), label = "detail-title")
    Column(modifier.fillMaxWidth().testTag("detail-top-bar")
        .drawBehind { drawRect(color.copy(alpha = opacity.value)) }
        .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)) {
        CompositionLocalProvider(LocalContentColor provides if (collapsed) MaterialTheme.colorScheme.onSurface else Color.White) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                navigation()
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)
                    .graphicsLayer { alpha = opacity.value }
                    .then(if (!collapsed) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("detail-collapsed-title"), contentAlignment = Alignment.CenterStart) {
                    if (logo != null) logo(Modifier.fillMaxWidth().height(32.dp).testTag("detail-toolbar-logo"))
                    else Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!actionsWhenCollapsed || collapsed) actions()
            }
        }
        notices()
    }
}
