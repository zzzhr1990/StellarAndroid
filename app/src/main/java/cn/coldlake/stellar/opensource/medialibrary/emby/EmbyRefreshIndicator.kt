package cn.coldlake.stellar.opensource.medialibrary.emby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Material's default IndicatorBox clips the circle at its top edge during a short pull.
 * Fade in a whole circle instead; keep the shared gesture state and refresh anchor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EmbyRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val visible by remember(state, isRefreshing) {
        derivedStateOf { isRefreshing || state.distanceFraction > 0f }
    }
    if (!visible) return
    // Insets belong to the outer box, outside the moving 40dp circle.
    Box(modifier) {
        Surface(
            modifier = Modifier.size(40.dp).graphicsLayer {
                val fraction = state.distanceFraction
                alpha = if (isRefreshing) 1f else (fraction * 2).coerceIn(0f, 1f)
                translationY = (fraction * PullToRefreshDefaults.IndicatorMaxDistance.toPx() - size.height).coerceAtLeast(0f)
            },
            shape = CircleShape,
            color = PullToRefreshDefaults.indicatorContainerColor,
            shadowElevation = PullToRefreshDefaults.Elevation,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isRefreshing) CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.5.dp,
                    color = PullToRefreshDefaults.indicatorColor, trackColor = Color.Transparent,
                ) else CircularProgressIndicator(
                    progress = { state.distanceFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.size(16.dp), strokeWidth = 2.5.dp,
                    color = PullToRefreshDefaults.indicatorColor, trackColor = Color.Transparent,
                )
            }
        }
    }
}
