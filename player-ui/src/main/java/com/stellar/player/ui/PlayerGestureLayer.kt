package com.stellar.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import com.stellar.player.contract.PlaybackBuffer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.contract.PlaybackSnapshot
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun touchModifier(snapshot: PlaybackSnapshot, ui: PlayerInteractionState, actions: PlayerInteractionActions,
    landscape: Boolean, seekBar: Boolean = false, timeline: State<PlaybackSnapshot>? = null,
    liveInteraction: State<PlayerInteractionState>? = null, arcSeek: ArcSeekHandle? = null): Modifier {
    val context = LocalContext.current
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentUi by rememberUpdatedState(ui)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val origin = remember { arrayOf(Offset.Zero) }
    val identity = InteractionContext(snapshot.sessionId, ui.revision)
    val touch = remember(identity, landscape, actions, seekBar) {
        PlayerTouch(context, identity, actions, { timeline?.value ?: currentSnapshot },
            { liveInteraction?.value ?: currentUi }, { size.width }, { size.height }, seekBar,
            arcSeek, { origin[0].x }, { origin[0].y })
    }
    DisposableEffect(touch) {
        arcSeek?.cancelDrag = touch::cancelArc
        onDispose { arcSeek?.cancelDrag = null; touch.dispose() }
    }
    return Modifier.onSizeChanged { size = it }.onGloballyPositioned { origin[0] = it.positionInWindow() }
        .pointerInteropFilter { touch.event(it) }
}

@Composable
internal fun BoxScope.InteractionFeedback(snapshot: PlaybackSnapshot, ui: PlayerInteractionState,
    actions: PlayerInteractionActions, landscape: Boolean, liveInteraction: State<PlayerInteractionState>? = null) {
    val current = liveInteraction?.value ?: ui
    LevelFeedback(current, snapshot.simulated)
    val text = when (current.gesture) {
        GestureKind.SEEK -> stringResource(R.string.seek_preview, formatTime(current.previewMs), formatTime(snapshot.durationMs))
        GestureKind.BRIGHTNESS, GestureKind.VOLUME -> null
        GestureKind.SPEED -> stringResource(R.string.temporary_speed, speedLabel(current.temporarySpeed ?: snapshot.speed))
        GestureKind.ZOOM -> stringResource(R.string.zoom_value, numberLabel((current.scale * 100).roundToInt()))
        null -> null
    }
    if (text != null) {
        Column(Modifier.align(if (landscape && current.gesture == GestureKind.SPEED) Alignment.TopCenter else Alignment.Center)
            .then(if (landscape && current.gesture == GestureKind.SPEED) Modifier.padding(top = 32.dp) else Modifier)
            .graphicsLayer().widthIn(max = 280.dp).background(PlayerColors.Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("gesture-feedback").semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.gesture == GestureKind.SEEK && landscape && snapshot.simulated) {
                Box(Modifier.size(96.dp, 54.dp).background(Color(0xFF111722)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.icon_cover_placeholder), null, Modifier.size(28.dp))
                }
            }
            Text(text, color = Color.White, fontSize = 14.sp)
        }
    }
    if (!landscape && ui.scale != 1f && ui.controlsVisible && !ui.locked && ui.gesture == null) {
        val label = stringResource(R.string.reset_zoom)
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).heightIn(min = 48.dp)
            .background(PlayerColors.Panel, RoundedCornerShape(8.dp)).testTag("reset-zoom")
            .semantics { contentDescription = label; role = Role.Button }
            .clickable { actions.resetScale(InteractionContext(snapshot.sessionId, ui.revision)) }.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BoxScope.LevelFeedback(ui: PlayerInteractionState, simulated: Boolean) {
    // Retain the displayed kind while the visibility animation fades out.
    var displayedKind by remember { mutableStateOf(GestureKind.VOLUME) }
    ui.levelFeedback?.let { if (it != displayedKind) displayedKind = it }
    val brightness = displayedKind == GestureKind.BRIGHTNESS
    val value = if (brightness) ui.brightness else ui.volume
    val percent = numberLabel((value * 100 + .0001f).toInt())
    val label = stringResource(if (simulated) {
        if (brightness) R.string.simulated_brightness else R.string.simulated_volume
    } else if (brightness) R.string.device_brightness else R.string.device_volume, percent)
    val colors = SliderDefaults.colors()
    val boostColor = MaterialTheme.colorScheme.onPrimary
    val icon = if (brightness) when {
        value < .3f -> R.drawable.hills_brightness_low
        value < .7f -> R.drawable.hills_brightness_medium
        else -> R.drawable.hills_brightness_high
    } else when {
        value == 0f -> R.drawable.hills_volume_off
        value < .7f -> R.drawable.hills_volume_down
        else -> R.drawable.hills_volume_up
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        AnimatedVisibility(ui.levelFeedback != null, Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.graphicsLayer().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .5f))
            .testTag("gesture-feedback")
            .semantics(mergeDescendants = true) {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..if (brightness) 1f else 2f)
                liveRegion = LiveRegionMode.Polite
            }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null,
                Modifier.size(24.dp).testTag(if (brightness) "brightness-icon" else if (value == 0f) "mute-icon" else "volume-icon"),
                tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(112.dp, 32.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.small).testTag("level-track")) {
                    drawRect(colors.inactiveTrackColor)
                    clipRect(right = size.width * value.coerceIn(0f, 1f)) { drawRect(colors.activeTrackColor) }
                    if (!brightness && value > 1f) {
                        clipRect(right = size.width * (value - 1f).coerceIn(0f, 1f)) { drawRect(boostColor) }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.player_level_percent, percent), Modifier.widthIn(min = 34.dp).testTag("level-percent"),
                color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, maxLines = 1)
        }
        }
    }
}
