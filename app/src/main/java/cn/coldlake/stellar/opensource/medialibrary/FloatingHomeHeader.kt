package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/** Keep Material3's offset handling, but snap in the last drag direction like a floating
 * SliverAppBar. A short downward drag must reveal the controls even deep in the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun rememberFloatingHomeScrollConnection(
    state: TopAppBarState,
    canScroll: () -> Boolean,
): NestedScrollConnection {
    val enabled by rememberUpdatedState(canScroll)
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(state,
        canScroll = { enabled() }, snapAnimationSpec = null, flingAnimationSpec = null)
    return remember(behavior) {
        object : NestedScrollConnection {
            var direction = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) direction = available.y
                behavior.nestedScrollConnection.onPreScroll(available, source)
                // This header overlays a fixed viewport: the list must receive the same
                // delta immediately, even while the header is still partially visible.
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                behavior.nestedScrollConnection.onPostScroll(consumed, available, source)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val result = behavior.nestedScrollConnection.onPostFling(consumed, available)
                if (enabled() && direction != 0f && state.collapsedFraction > 0f && state.collapsedFraction < 1f) {
                    val target = if (direction > 0f) 0f else state.heightOffsetLimit
                    AnimationState(state.heightOffset).animateTo(target, tween(180)) { state.heightOffset = value }
                }
                direction = 0f
                return result
            }
        }
    }
}

/** Overlay only: collapsing the header never resizes the list or changes its anchor.
 * Move the whole header, including its insets, and clip hidden controls from hit testing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FloatingHomeHeader(
    state: TopAppBarState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hidden by remember(state) {
        derivedStateOf { state.heightOffsetLimit != -Float.MAX_VALUE && state.collapsedFraction >= .999f }
    }
    Column(Modifier.fillMaxWidth().clipToBounds()
        .layout { measurable, constraints ->
            val header = measurable.measure(constraints.copy(minHeight = 0))
            // The overlay keeps its size. A drag only updates its layer placement,
            // without invalidating measurement of the header or its parent viewport.
            layout(header.width, header.height) {
                header.placeRelativeWithLayer(0, state.heightOffset.roundToInt())
            }
        }
        // Keep caller semantics inside the translated, clipped area (including hit tests).
        .then(modifier)
        .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier)
        .onSizeChanged { size ->
            // This callback can run inside measurement's read observer. Its one-time
            // checkpoint must not subscribe measurement to the next scroll offset.
            Snapshot.withoutReadObservation {
                val limit = -size.height.toFloat()
                if (state.heightOffsetLimit != limit) {
                    // Preserve the visible fraction through font, inset and orientation changes.
                    val fraction = state.collapsedFraction
                    state.heightOffsetLimit = limit
                    state.heightOffset = limit * fraction
                }
            }
        }, content = content)
}
