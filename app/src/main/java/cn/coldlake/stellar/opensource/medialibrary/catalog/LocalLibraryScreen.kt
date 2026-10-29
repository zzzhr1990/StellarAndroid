package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.cache.AppCaches
import kotlinx.coroutines.launch

@Composable
internal fun ScanControls(runtime: LocalLibraryRuntime, modifier: Modifier = Modifier) {
    val status by runtime.status.collectAsStateWithLifecycle()
    val metadata by runtime.metadata.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launchTask = rememberLibraryTaskLauncher()
    Column(modifier.testTag("scan-controls")) {
        val label = when (status.state) {
            "running" -> R.string.scan_running; "paused" -> R.string.scan_paused
            "complete" -> R.string.scan_complete; "partial" -> R.string.scan_partial
            "failed" -> R.string.scan_failed; "cancelled" -> R.string.scan_cancelled; else -> R.string.scan_idle
        }
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            modifier = Modifier.testTag("scan-status"))
        Text(stringResource(R.string.scan_counts, status.discovered, status.parsed), style = MaterialTheme.typography.bodySmall)
        if (metadata.running) {
            Text(stringResource(R.string.metadata_progress, metadata.processed, metadata.matched, metadata.review, metadata.failed),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("scan-metadata-progress"))
            if (metadata.waitingUntil > 0) Text(stringResource(R.string.metadata_waiting, metadata.waiting), style = MaterialTheme.typography.bodySmall)
        }
        if (status.error) Text(stringResource(R.string.scan_failed), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.running || metadata.running) TextButton(onClick = { runtime.stop() }, Modifier.testTag("scan-pause")) { Text(stringResource(R.string.scan_pause)) }
            else TextButton(onClick = { launchTask { LibraryScanService.start(context, status.resumable) } }, enabled = status.roots > 0 && !metadata.running,
                modifier = Modifier.testTag("scan-start")) {
                Text(stringResource(if (status.resumable) R.string.scan_resume else R.string.scan_start))
            }
            if (status.running || status.resumable || metadata.running) TextButton(onClick = { runtime.stop(true) }, Modifier.testTag("scan-cancel")) {
                Text(stringResource(R.string.scan_cancel))
            } else TextButton(onClick = { launchTask { LibraryScanService.start(context, force = true) } }, enabled = status.roots > 0 && !metadata.running) {
                Text(stringResource(R.string.scan_reread))
            }
        }
    }
}

internal data class NasArtwork(val uri: String, val version: String?)
