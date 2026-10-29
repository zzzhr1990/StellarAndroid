package cn.coldlake.stellar.opensource.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.NavDisplay

/** Entry metadata keeps the same transition on pop, after its business page has been removed. */
@Composable
internal fun rememberHorizontalNavigationMetadata(shortDistance: Boolean = false): Map<String, Any> {
    // NavEntry metadata can outlive a recomposition or a window-density/layout-direction change.
    val direction = rememberUpdatedState(if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1)
    val distance = rememberUpdatedState(if (shortDistance) with(LocalDensity.current) { 30.dp.roundToPx() } else null)
    return remember(shortDistance) {
        NavDisplay.transitionSpec { horizontalTransition(direction.value, distance.value) } +
            NavDisplay.popTransitionSpec { horizontalTransition(-direction.value, distance.value) } +
            NavDisplay.predictivePopTransitionSpec { _ -> horizontalTransition(-direction.value, distance.value) }
    }
}

private fun horizontalTransition(direction: Int, distance: Int?): ContentTransform {
    val enter = slideInHorizontally(tween(300)) { width -> direction * (distance ?: width) }
    val exit = slideOutHorizontally(tween(300)) { width -> -direction * (distance ?: width) }
    return if (distance == null) enter togetherWith exit
    else (enter + fadeIn(tween(300))) togetherWith (exit + fadeOut(tween(300)))
}
