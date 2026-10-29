package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by server and catalog details. Image/cache identity remains with the caller. */
@Composable internal fun MediaDetailTheme(seed: Int?, content: @Composable (Color, Color) -> Unit) {
    val base = MaterialTheme.colorScheme
    val colors by produceState(base, seed, base) {
        value = seed?.let { withContext(Dispatchers.Default) {
            mediaArtworkColorScheme(it, base.background.luminance() < .5f, base)
        } } ?: base
    }
    val top = if (seed == null) colors.background else mediaArtworkBackground(colors, .4f)
    val bottom = if (seed == null) colors.background else mediaArtworkBackground(colors, .8f)
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) { content(top, bottom) }
    }
}

/** One page skeleton; callers supply bounded sections and keep navigation/list state. */
@Composable internal fun MediaDetailPage(
    list: LazyListState, color: Color, bottomColor: Color, modifier: Modifier = Modifier,
    statusBarPadding: Boolean = true, logoWidth: Dp? = null,
    image: (@Composable (Modifier) -> Unit)?, logo: (@Composable (Modifier) -> Unit)? = null,
    toolbar: @Composable BoxScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        MediaDetailList(list, color, modifier, bottomColor) {
            if (image != null) item("header", "header") {
                MediaDetailHeader(list, statusBarPadding, logoWidth, image, logo)
            } else item("toolbar-space", "spacing") {
                Column {
                    Spacer(Modifier.then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier).height(64.dp))
                    if (logo != null) {
                        val layout = LocalMediaDetailLayout.current
                        Box(Modifier.fillMaxWidth().padding(horizontal = layout.inset, vertical = 12.dp),
                            contentAlignment = if (layout.portraitHeader) androidx.compose.ui.Alignment.Center else androidx.compose.ui.AbsoluteAlignment.CenterLeft) {
                            logo(Modifier.width(logoWidth ?: 340.dp).height(96.dp))
                        }
                    }
                }
            }
            content()
        }
        toolbar()
    }
}
