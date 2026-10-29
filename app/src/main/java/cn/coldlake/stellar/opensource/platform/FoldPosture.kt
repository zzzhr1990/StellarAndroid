package cn.coldlake.stellar.opensource.platform

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** Where content may sit without crossing a physical fold. */
internal sealed interface FoldPosture {
    data object Flat : FoldPosture
    /** Vertical fold, half opened or separating: physical [left]/[right] pane widths. */
    data class Book(val left: Dp, val gap: Dp, val right: Dp) : FoldPosture
}

/** The window's vertical fold while it is half opened or separating, otherwise null. */
@Composable internal fun rememberBookFold(): State<FoldingFeature?> {
    val activity = LocalActivity.current
    val features = remember(activity) {
        if (activity == null) emptyFlow() else WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
            .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { it.isBook() } }
            .distinctUntilChanged()
    }
    return features.collectAsStateWithLifecycle(null)
}

private fun FoldingFeature.isBook() = orientation == FoldingFeature.Orientation.VERTICAL &&
    (state == FoldingFeature.State.HALF_OPENED || isSeparating)

/** [fold] comes from [rememberBookFold]; [container] is the caller's own bounds in window pixels. */
internal fun foldPosture(fold: FoldingFeature?, container: Rect?, density: Density): FoldPosture {
    if (fold == null || container == null) return FoldPosture.Flat
    with(density) {
        val left = (fold.bounds.left - container.left).toDp()
        val right = (container.right - fold.bounds.right).toDp()
        // Each pane must still hold usable content; otherwise the fold is near an edge.
        return if (left < 280.dp || right < 280.dp) FoldPosture.Flat
            else FoldPosture.Book(left, fold.bounds.width().toFloat().toDp(), right)
    }
}
