package cn.coldlake.stellar.opensource.playback.reporting

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.stringResource
import cn.coldlake.stellar.opensource.R

@Composable
internal fun PlaybackReportingDialogs(runtime: PlaybackReportingRuntime) {
    val conflict by runtime.conflict.collectAsState()
    val failure by runtime.storageFailed.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(failure) {
        if (failure) android.widget.Toast.makeText(context, R.string.playback_save_failed, android.widget.Toast.LENGTH_LONG).show()
    }
    conflict?.let { value ->
        AlertDialog(onDismissRequest = { runtime.resolveConflict(null) },
            title = { Text(stringResource(R.string.playback_resume_conflict)) },
            text = { Text(stringResource(R.string.playback_resume_conflict_detail)) },
            confirmButton = {
                Column {
                    TextButton(onClick = { runtime.resolveConflict(value.localMs) }) {
                        Text(stringResource(R.string.playback_resume_local, time(value.localMs)))
                    }
                    TextButton(onClick = { runtime.resolveConflict(value.serverMs) }) {
                        Text(stringResource(R.string.playback_resume_server, time(value.serverMs)))
                    }
                    TextButton(onClick = { runtime.resolveConflict(0) }) { Text(stringResource(R.string.playback_resume_start)) }
                }
            })
    }
}
private fun time(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
