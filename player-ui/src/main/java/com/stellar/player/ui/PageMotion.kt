package com.stellar.player.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection

/** Local tab motion only; application page transitions are owned by Navigation 3. */
enum class PageMotion { TopLevel, LateralForward, LateralBackward }

/** Outgoing pages remain drawn briefly, but must not handle back or start page work. */
val LocalPageActive = staticCompositionLocalOf { true }

private val PageEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

@Composable
fun <T> PageMotionContent(
    targetState: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    motion: (T, T) -> PageMotion = { _, _ -> PageMotion.TopLevel },
    content: @Composable (T) -> Unit,
) {
    val parentActive = LocalPageActive.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(targetState = targetState, modifier = modifier.clipToBounds(), contentAlignment = Alignment.TopStart,
        contentKey = contentKey, transitionSpec = {
            val pattern = motion(initialState, targetState)
            val forward = pattern == PageMotion.LateralForward
            val sign = if (forward) 1 else -1
            val enter = fadeIn(tween(210, delayMillis = 90, easing = PageEasing))
            val exit = fadeOut(tween(90, easing = PageEasing))
            when (pattern) {
                PageMotion.TopLevel -> enter togetherWith exit
                PageMotion.LateralForward, PageMotion.LateralBackward ->
                    slideInHorizontally(tween(300, easing = PageEasing)) { it * direction * sign } togetherWith
                        slideOutHorizontally(tween(300, easing = PageEasing)) { -it * direction * sign }
            }.using(null) // Screen bounds stay fixed; no AnimatedContent default size/scale animation.
        }) { page ->
        val active = parentActive && contentKey(page) == contentKey(targetState)
        CompositionLocalProvider(LocalPageActive provides active) {
            Box(Modifier.fillMaxSize().then(if (active) Modifier else Modifier
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                })) { content(page) }
        }
    }
}
