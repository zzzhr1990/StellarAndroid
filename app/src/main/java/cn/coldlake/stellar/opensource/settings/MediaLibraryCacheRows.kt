package cn.coldlake.stellar.opensource.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.cache.AppCaches
import cn.coldlake.stellar.opensource.cache.CacheWork

@Composable
internal fun MediaLibraryCacheRows(caches: AppCaches) {
    val state by caches.maintenance.collectAsState()
    LaunchedEffect(caches) { caches.refreshUsage() }
    val data = state.data
    val images = state.images
    val bytes = data.bytes?.let { dataBytes -> images.bytes?.let { dataBytes + it } }
    val usage = when {
        data.work == CacheWork.CLEARING || images.work == CacheWork.CLEARING -> stringResource(R.string.cache_clearing)
        data.work == CacheWork.CALCULATING || images.work == CacheWork.CALCULATING -> stringResource(R.string.cache_calculating)
        data.work == CacheWork.FAILED || images.work == CacheWork.FAILED -> stringResource(R.string.cache_failed)
        bytes == null -> stringResource(R.string.cache_calculating)
        else -> stringResource(
            if (data.work == CacheWork.DONE && images.work == CacheWork.DONE) R.string.cache_cleared_size else R.string.cache_size,
            Formatter.formatFileSize(LocalContext.current, bytes))
    }
    ListItem(
        supportingContent = { Text(usage, modifier = Modifier.testTag("media-library-cache-usage")) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)) {
        Text(stringResource(R.string.playback_cache_usage))
    }
    ListItem(onClick = { caches.clearAll() }, enabled = !state.busy,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().testTag("media-library-cache-clear"),
        supportingContent = { Text(stringResource(R.string.media_library_cache_clear_hint)) }) {
        Text(stringResource(R.string.playback_cache_clear))
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp))
    CacheUsageFooter(caches)
}
