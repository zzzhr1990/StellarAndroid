package com.stellar.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.*
import java.util.Locale

/** Compact controls leave the independent video Surface visible in both orientations. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BoxWithConstraintsScope.FrameStepPanel(s: PlaybackSnapshot, actions: PlayerPanelActions, landscape: Boolean) {
    val dismiss = stringResource(R.string.dismiss_panel)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
        .testTag("panel-scrim").semantics { contentDescription = dismiss }
        .clickable(onClick = actions::dismissPanel))
    Surface(Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility))
        .padding(16.dp).widthIn(max = 400.dp).fillMaxWidth()
        .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(48.dp))
        .pointerInput(Unit) { detectTapGestures {} }.testTag("frame-panel"),
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.frame_step), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = actions::dismissPanel, modifier = Modifier.testTag("panel-back")) {
                    Text(stringResource(R.string.frame_close))
                }
            }
            FrameStepContent(s, actions)
        }
    }
}

@Composable
internal fun FrameStepContent(s: PlaybackSnapshot, actions: PlayerPanelActions) {
    val busy = s.pendingFrame != null || s.pendingSeek != null
    val position = s.framePositionMs ?: s.positionMs
    Text(stringResource(R.string.frame_time, formatTime(position), String.format(Locale.ROOT, "%03d", position % 1000)),
        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr),
        modifier = Modifier.padding(vertical = 8.dp).testTag("frame-position"))
    Text(stringResource(if (s.simulated) R.string.frame_hint else R.string.frame_instructions),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val message = when {
        !s.canStepFrame || s.status in listOf(PlaybackStatus.ERROR, PlaybackStatus.ENDED, PlaybackStatus.IDLE) -> R.string.frame_unavailable
        busy -> R.string.frame_busy
        s.status != PlaybackStatus.PAUSED -> R.string.frame_pausing
        s.frameResult?.failure == FrameStepFailure.BOUNDARY -> R.string.frame_boundary
        s.frameResult?.failure == FrameStepFailure.FAILED -> R.string.frame_failed
        s.frameEndBoundMs != null && position >= s.frameEndBoundMs!! -> R.string.frame_end
        else -> null
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("frame-status")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { actions.tool(ToolEvent.Frame(-1)) }, enabled = s.canStep(-1),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("frame-previous")) { Text(stringResource(R.string.frame_previous)) }
        FilledTonalButton(onClick = { actions.tool(ToolEvent.Frame(1)) }, enabled = s.canStep(1),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("frame-next")) { Text(stringResource(R.string.frame_next)) }
    }
    Button(onClick = { actions.tool(ToolEvent.ResumeFrames) }, enabled = !busy && s.status == PlaybackStatus.PAUSED,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).testTag("frame-resume")) {
        Text(stringResource(R.string.frame_resume))
    }
}
