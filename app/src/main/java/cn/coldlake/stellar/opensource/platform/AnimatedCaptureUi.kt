package cn.coldlake.stellar.opensource.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.PlaybackSnapshot
import cn.coldlake.stellar.opensource.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimatedCaptureDialog(capture: PlayerAnimatedCapture, snapshot: PlaybackSnapshot) {
    val session = capture.dialogSession ?: return
    var format by remember(session) { mutableStateOf(CaptureFormat.GIF) }
    var seconds by remember(session) { mutableIntStateOf(5) }
    val unavailable = capture.unavailable(snapshot)
    AlertDialog(onDismissRequest = capture::dismiss,
        title = { Text(stringResource(R.string.capture_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("capture-dialog")) {
                Text(stringResource(R.string.capture_description))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureFormat.entries.forEach { value -> FilterChip(selected = format == value, onClick = { format = value },
                        label = { Text(stringResource(if (value == CaptureFormat.GIF) R.string.capture_gif else R.string.capture_mp4)) },
                        modifier = Modifier.testTag("capture-format-${value.name}")) }
                }
                Text(stringResource(if (format == CaptureFormat.GIF) R.string.capture_gif_quality else R.string.capture_mp4_quality),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.capture_duration))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3,5,10).forEach { value -> FilterChip(selected = seconds == value, onClick = { seconds = value },
                        label = { Text(pluralStringResource(R.plurals.capture_seconds,value,value)) }, modifier = Modifier.testTag("capture-duration-$value")) }
                }
                Text(stringResource(R.string.capture_destination), style = MaterialTheme.typography.bodySmall)
                unavailable?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        }, confirmButton = {
            TextButton(onClick = { capture.start(format,seconds) }, enabled = unavailable == null,
                modifier = Modifier.testTag("capture-start")) { Text(stringResource(R.string.capture_start)) }
        }, dismissButton = { TextButton(onClick = capture::dismiss) { Text(stringResource(R.string.capture_cancel)) } })
}

@Composable
internal fun AnimatedCaptureProgress(capture: PlayerAnimatedCapture) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp,vertical = 4.dp).testTag("capture-progress"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(when(capture.phase) {
                    CapturePhase.RECORDING -> R.string.capture_recording
                    CapturePhase.SAVING -> R.string.capture_saving
                    else -> R.string.capture_preparing
                }, capture.elapsedSeconds, capture.durationSeconds), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { capture.elapsedSeconds.toFloat() / capture.durationSeconds }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { capture.cancel() }, modifier = Modifier.testTag("capture-cancel")) { Text(stringResource(R.string.capture_cancel)) }
        }
    }
}
