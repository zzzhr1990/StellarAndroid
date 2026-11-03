package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R

@Composable
private fun ClearMediaSelection(deleteManual: Boolean, onManual: (Boolean) -> Unit,
    deletePlayback: Boolean, onPlayback: (Boolean) -> Unit, enabled: Boolean = true) {
    Text(stringResource(R.string.scan_center_clear_description))
    ClearMediaOption(R.string.scan_center_delete_manual, deleteManual, "library-clear-manual", enabled, onManual)
    ClearMediaOption(R.string.scan_center_delete_playback, deletePlayback, "library-clear-playback", enabled, onPlayback)
    Text(stringResource(R.string.scan_center_clear_retention), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ClearMediaDialog(onDismiss: () -> Unit, onConfirm: (Boolean, Boolean) -> Unit) {
    var deleteManual by rememberSaveable { mutableStateOf(false) }
    var deletePlayback by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("library-clear-dialog"),
        title = { Text(stringResource(R.string.scan_center_clear)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClearMediaSelection(deleteManual, { deleteManual = it }, deletePlayback, { deletePlayback = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteManual, deletePlayback) }, modifier = Modifier.testTag("library-clear-confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.scan_center_clear_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_center_clear_cancel)) } })
}

@Composable
private fun ClearMediaOption(label: Int, checked: Boolean, tag: String, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).toggleable(value = checked, role = Role.Checkbox, enabled = enabled, onValueChange = onChecked)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}
