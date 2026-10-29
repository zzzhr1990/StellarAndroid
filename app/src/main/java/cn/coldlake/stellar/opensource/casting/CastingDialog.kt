package cn.coldlake.stellar.opensource.casting

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.coldlake.stellar.opensource.R

@Composable
internal fun CastingDialog(controller: CastingController, onDismiss: () -> Unit) {
    val visible by controller.panel.collectAsStateWithLifecycle()
    if (!visible) return
    val state by controller.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), controller::permissionResult)
    val dismiss = { controller.dismissPanel(); onDismiss() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.casting_title)) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("casting-dialog")) {
                Text(stringResource(R.string.casting_hint), style = MaterialTheme.typography.bodySmall)
                if (state.subtitleFailed) Text(stringResource(R.string.casting_subtitle_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp).testTag("casting-subtitle-error"))
                state.failure?.let {
                    val resource = castFailureLabel(it)
                    Text(stringResource(resource), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).testTag("casting-error"))
                }
                if (!controller.permitted()) {
                    Button(onClick = { permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }, modifier = Modifier.testTag("casting-permission")) { Text(stringResource(R.string.casting_allow)) }
                } else if (state.device != null) {
                    Text(state.device!!.name, modifier = Modifier.padding(vertical = 12.dp))
                    if (state.connecting || state.subtitleBusy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    if (state.device?.protocol == CastProtocol.DLNA) Text(stringResource(R.string.casting_subtitle_hint), style = MaterialTheme.typography.bodySmall)
                    state.volume?.let { current ->
                        var volume by remember(state.device?.id, current) { mutableFloatStateOf(current.coerceIn(0f, 1f)) }
                        Text(stringResource(R.string.casting_volume))
                        Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { controller.volume(volume) }, modifier = Modifier.testTag("casting-volume"))
                    }
                    Button(onClick = controller::returnToPhone, enabled = !state.connecting && !state.subtitleBusy, modifier = Modifier.testTag("casting-return")) { Text(stringResource(R.string.casting_return)) }
                    TextButton(onClick = controller::cancelSession, modifier = Modifier.testTag("casting-stop")) { Text(stringResource(R.string.casting_stop)) }
                } else {

                    if (state.discovering) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(state.devices, key = { it.id }, contentType = { "device" }) { device ->
                            TextButton(onClick = { controller.connect(device) }, modifier = Modifier.fillMaxWidth().testTag("casting-device-${device.id}")) {
                                Text(stringResource(R.string.casting_dlna_device, device.name), Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (!state.discovering && state.devices.isEmpty() && state.failure == null) Text(stringResource(R.string.casting_empty), modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = controller::discover, enabled = !state.discovering, modifier = Modifier.testTag("casting-refresh")) { Text(stringResource(R.string.casting_refresh)) }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.casting_close)) } })
}
