package cn.coldlake.stellar.opensource.settings

import android.content.SharedPreferences
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.cache.AppCaches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlaybackCachePage(onNavigate: (String) -> Unit, caches: AppCaches? = AppCaches.current) {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { PlaybackCacheSettings(context) }
    val cache = remember(context.applicationContext) { PlaybackCacheSettings.mpvDiskCache(context) }
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var days by remember(settings) { mutableIntStateOf(settings.retentionDays) }
    var bytes by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val labels = listOf(R.string.playback_cache_day_1, R.string.playback_cache_day_7, R.string.playback_cache_day_30)
    val scope = rememberCoroutineScope()
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = settings.enabled; days = settings.retentionDays
        }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(cache) {
        busy = true
        try { bytes = withContext(Dispatchers.IO) { PlaybackCacheSettings.usageBytes(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = R.string.playback_cache_read_failed }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)
        .testTag("settings-list-PLAYBACK_CACHE")) {
        SectionTitle(R.string.playback_buffer_title)
        PlaybackBufferRows(onNavigate)
        Spacer(Modifier.height(24.dp))
        SectionTitle(R.string.playback_cache_disk)
        ListItem(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .testTag("playback-cache-enabled").toggleable(enabled, role = Role.Switch) { settings.enabled = it },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            supportingContent = { Text(stringResource(R.string.playback_cache_hint)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) }) {
            Text(stringResource(R.string.playback_cache_enable_disk))
        }
        Box {
            ListItem(onClick = { onNavigate("playback-retention") }, enabled = enabled,
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth()
                    .testTag("playback-cache-retention"),
                supportingContent = { Text(stringResource(labels[PlaybackCacheSettings.RETENTION_DAYS.indexOf(days)])) }) {
                Text(stringResource(R.string.playback_cache_retention))
            }

        }
        Text(stringResource(R.string.playback_cache_expiry_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
        ListItem(supportingContent = { Text(bytes?.let { Formatter.formatShortFileSize(context, it) }
                ?: stringResource(if (busy) R.string.playback_cache_calculating else R.string.playback_cache_read_failed),
                modifier = Modifier.testTag("playback-cache-usage")) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).clip(MaterialTheme.shapes.large)) {
            Text(stringResource(R.string.playback_cache_usage))
        }
        ListItem(onClick = {
            if (!busy) scope.launch {
                busy = true; notice = null
                try {
                    bytes = withContext(Dispatchers.IO) {
                        PlaybackCacheSettings.clear(context); PlaybackCacheSettings.usageBytes(context)
                    }
                    notice = R.string.playback_cache_cleared
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = R.string.playback_cache_clear_failed }
                finally { busy = false }
            }
        }, enabled = !busy,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().testTag("playback-cache-clear"),
            supportingContent = { Text(stringResource(R.string.playback_cache_clear_hint)) }) {
            Text(stringResource(R.string.playback_cache_clear))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp))
        notice?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).testTag("playback-cache-notice"))
        }
        if (caches != null) {
            Spacer(Modifier.height(24.dp))
            SectionTitle(R.string.media_library_cache_title)
            MediaLibraryCacheRows(caches)
        }
    }
}
