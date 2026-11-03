package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.settings.SettingsPageToolbar
import com.stellar.player.ui.LocalPageActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat

private enum class ClearFeedback { NONE, CLEARING, CLEARED, FAILED }

/** An independent destination sharing the existing scan service and persistent work. */
@Composable
internal fun LibraryScanScreen(runtime: LocalLibraryRuntime, onBack: () -> Unit) {
    val scan by runtime.status.collectAsStateWithLifecycle()
    val metadata by runtime.metadata.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = metadataLanguage(LocalConfiguration.current.locales[0].toLanguageTag())
    val pageActive = LocalPageActive.current
    val launchTask = rememberLibraryTaskLauncher()
    val scope = rememberCoroutineScope()
    var ready by remember(runtime) { mutableStateOf(false) }
    val clearing by runtime.clearing.collectAsStateWithLifecycle()
    var clearError by remember { mutableIntStateOf(R.string.scan_center_clear_failed) }
    var clearFeedback by remember { mutableStateOf(ClearFeedback.NONE) }
    val busy = clearing || clearFeedback == ClearFeedback.CLEARING
    var showClear by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(runtime, locale, pageActive) {
        if (pageActive) {
            try {
                runtime.awaitReady()
                runtime.metadata.restoreProgress(locale)
                ready = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { runtime.taskFailed() }
        }
    }
    val active = scan.running || metadata.running
    val canStart = ready && !active && !busy
    val title = when {
        busy -> R.string.scan_center_clearing
        clearFeedback == ClearFeedback.CLEARED -> R.string.scan_center_cleared
        clearFeedback == ClearFeedback.FAILED -> clearError
        scan.running -> R.string.scan_center_active
        metadata.running && metadata.waitingUntil > 0 -> R.string.scan_center_waiting
        metadata.running -> R.string.scan_center_identifying
        scan.error || scan.state == "failed" -> R.string.scan_center_failed
        scan.resumable -> R.string.scan_center_paused
        scan.state == "partial" -> R.string.scan_center_partial
        scan.state == "cancelled" -> R.string.scan_center_cancelled
        scan.state == "complete" -> R.string.scan_center_discovered
        else -> R.string.scan_center_ready
    }
    Surface(Modifier.fillMaxSize().testTag("library-scan-page"), color = Color.Transparent) {
        Column(Modifier.fillMaxSize()) {
            SettingsPageToolbar(R.string.scan_center_title, onBack, "library-scan-back")
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("library-scan-list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item("overview") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall,
                                color = if (!busy && (clearFeedback == ClearFeedback.FAILED || scan.error))
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("library-scan-status").semantics { heading() })
                            Text(stringResource(R.string.scan_center_hint), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScanProgressIndicator((active || busy) && pageActive)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (scan.running) Button(onClick = { runtime.stop() }, modifier = Modifier.testTag("library-scan-pause")) {
                                    Text(stringResource(R.string.scan_pause))
                                } else if (!metadata.running) Button(onClick = {
                                    clearFeedback = ClearFeedback.NONE
                                    launchTask { LibraryScanService.start(context, resume = scan.resumable) }
                                }, enabled = canStart && scan.roots > 0, modifier = Modifier.testTag("library-scan-start")) {
                                    Text(stringResource(if (scan.resumable) R.string.scan_resume else R.string.scan_center_update))
                                }
                                if (active || scan.resumable) OutlinedButton(onClick = { runtime.stop(true) },
                                    modifier = Modifier.testTag("library-scan-cancel")) { Text(stringResource(R.string.scan_cancel)) }
                                Box {
                                    TextButton(onClick = { more = true }, enabled = ready && !busy,
                                        modifier = Modifier.testTag("library-scan-more")) {
                                        Text(stringResource(R.string.scan_center_more))
                                        Spacer(Modifier.width(4.dp))
                                        Icon(painterResource(R.drawable.material_symbol_more_vert), null, Modifier.size(18.dp))
                                    }
                                    DropdownMenu(more, onDismissRequest = { more = false }) {
                                        DropdownMenuItem(text = { ScanActionText(R.string.scan_center_fill, R.string.scan_center_fill_hint) }, enabled = canStart,
                                            modifier = Modifier.testTag("library-scan-incremental"),
                                            onClick = { more = false; clearFeedback = ClearFeedback.NONE; launchTask { LibraryScanService.identify(context, locale, incremental = true) } })
                                        DropdownMenuItem(text = { ScanActionText(R.string.scan_center_refresh, R.string.scan_center_refresh_hint) }, enabled = canStart,
                                            modifier = Modifier.testTag("library-scan-refresh"),
                                            onClick = { more = false; clearFeedback = ClearFeedback.NONE; launchTask { LibraryScanService.refresh(context) } })
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text(stringResource(R.string.scan_center_clear), color = MaterialTheme.colorScheme.error) }, enabled = canStart,
                                            modifier = Modifier.testTag("library-scan-clear"),
                                            onClick = { more = false; showClear = true })
                                    }
                                }
                            }
                            if (ready && scan.roots == 0) Text(stringResource(R.string.scan_center_no_sources),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item("discovery") {
                        ScanSection(R.string.scan_center_files, Modifier.testTag("library-scan-discovery")) {
                            val label = when (scan.state) {
                                "running" -> R.string.scan_running
                                "paused" -> R.string.scan_paused
                                "complete" -> R.string.scan_complete
                                "partial" -> R.string.scan_partial
                                "failed" -> R.string.scan_failed
                                "cancelled" -> R.string.scan_cancelled
                                else -> R.string.scan_idle
                            }
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium,
                                color = if (scan.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                ScanMetric(scan.discovered, R.string.scan_center_found, Modifier.weight(1f).widthIn(min = 112.dp))
                                ScanMetric(scan.parsed, R.string.scan_center_nfo, Modifier.weight(1f).widthIn(min = 112.dp))
                            }
                        }
                    }
                    item("metadata") {
                        ScanSection(R.string.scan_center_metadata, Modifier.testTag("library-scan-metadata")) {
                            Text(stringResource(if (metadata.running) R.string.scan_center_identifying else R.string.scan_center_saved),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                ScanMetric(metadata.matched.toLong(), R.string.scan_center_matched, Modifier.weight(1f).widthIn(min = 112.dp))
                                ScanMetric(metadata.review.toLong(), R.string.scan_center_review, Modifier.weight(1f).widthIn(min = 112.dp))
                                ScanMetric(metadata.waiting.toLong(), R.string.scan_center_pending, Modifier.weight(1f).widthIn(min = 112.dp))
                                ScanMetric(metadata.failed.toLong(), R.string.scan_center_errors, Modifier.weight(1f).widthIn(min = 112.dp))
                            }
                            val showWaiting = metadata.waiting > 0 || metadata.waitingUntil > 0
                            // Measure both messages even when hidden, so transient pending work
                            // and running/paused changes do not insert or resize this paragraph.
                            Box(Modifier.fillMaxWidth()) {
                                for (running in listOf(true, false)) {
                                    val visible = showWaiting && metadata.running == running
                                    Text(stringResource(
                                        if (running) R.string.metadata_waiting else R.string.metadata_waiting_paused,
                                        if (showWaiting) metadata.waiting else 1),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (visible) LocalContentColor.current else Color.Transparent,
                                        modifier = if (visible) Modifier else Modifier.clearAndSetSemantics { })
                                }
                            }
                            Text(stringResource(R.string.scan_center_metadata_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (showClear) ClearMediaDialog(onDismiss = { showClear = false }, onConfirm = { manual, playback ->
        showClear = false; clearFeedback = ClearFeedback.CLEARING
        scope.launch {
            try { runtime.clearMedia(manual, playback); clearFeedback = ClearFeedback.CLEARED }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                clearError = R.string.scan_center_clear_failed
                clearFeedback = ClearFeedback.FAILED
            }
        }
    })
}

@Composable
internal fun ScanProgressIndicator(active: Boolean) {
    // Keep the same layout slot across running, paused and stopped states.
    Box(Modifier.fillMaxWidth().height(WavyProgressIndicatorDefaults.LinearContainerHeight)) {
        if (active) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearWavyProgressIndicator(
            progress = { 1f },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            amplitude = { 1f },
            wavelength = WavyProgressIndicatorDefaults.LinearIndeterminateWavelength,
            waveSpeed = 0.dp,
            stopSize = 0.dp,
        )
    }
}

@Composable
private fun ScanSection(title: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun ScanMetric(value: Long, label: Int, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(format.format(value), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScanActionText(title: Int, description: Int) {
    Column(Modifier.widthIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title))
        Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
