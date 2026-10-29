package com.stellar.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.contract.PlaybackDiagnostics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

private enum class DiagnosticField(val label: Int) {
    BACKEND(R.string.diag_backend), VIDEO(R.string.diag_video), FPS(R.string.diag_fps),
    NETWORK(R.string.diag_network), BITRATE(R.string.diag_bitrate), COLOR(R.string.diag_color),
    SCREEN(R.string.diag_screen), FILE(R.string.diag_file),
    DECODER(R.string.diag_decoder), BUFFER(R.string.diag_buffer), CPU(R.string.diag_cpu), GPU(R.string.diag_gpu)
}

/** Fixed cells. Changing values are read only by the value Canvas and semantics, never layout.
 * The parent and the static labels do not subscribe to the diagnostics stream. */
@Composable
fun PlaybackDiagnosticsOverlay(values: StateFlow<PlaybackDiagnostics>, screen: String?, modifier: Modifier = Modifier,
    session: Long? = null) {
    val unknown = stringResource(R.string.diag_unknown)
    val hardware = stringResource(R.string.diag_hardware)
    val software = stringResource(R.string.diag_software)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val density = LocalDensity.current
    val style = TextStyle(color = Color.White, fontSize = 8.sp, lineHeight = 12.sp,
        fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr)
    val rowHeight = with(density) { 14.sp.toDp() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = "${with(density) { maxWidth.roundToPx() }} × ${with(density) { maxHeight.roundToPx() }}"
        Column(Modifier.padding(4.dp).widthIn(max = 390.dp).fillMaxWidth()
            .heightIn(max = (maxHeight - 8.dp).coerceAtLeast(1.dp)).graphicsLayer()
            .background(Color.Black.copy(alpha = 0.76f)).verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp).testTag("diagnostics-overlay")) {
            DiagnosticField.entries.forEach { field ->
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    Text(stringResource(field.label), style = style.copy(color = Color(0xFFD0D0D0),
                        fontFamily = FontFamily.Default, textDirection = TextDirection.Content),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.34f).testTag("diagnostics-label-${field.name}"))
                    DiagnosticValue(values, session, field, unknown, hardware, software, screen, viewport, locale, style,
                        Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DiagnosticValue(values: StateFlow<PlaybackDiagnostics>, session: Long?, field: DiagnosticField,
    unknown: String, hardware: String, software: String, screen: String?, viewport: String,
    locale: Locale, style: TextStyle, modifier: Modifier) {
    val formatted = remember(values, session, field, unknown, hardware, software, screen, viewport, locale) {
        values.map { sample ->
            val data = sample.takeIf { session == null || it.sessionId == session } ?: PlaybackDiagnostics(session ?: 0)
            fun decimal(value: Double?, unit: String, digits: Int = 1) = value?.takeIf { it.isFinite() && it >= 0 }
                ?.let { String.format(locale, "%.${digits}f %s", it, unit) } ?: unknown
            when (field) {
                DiagnosticField.BACKEND -> data.backend ?: unknown
                DiagnosticField.VIDEO -> listOf(data.videoCodec ?: unknown,
                    if (data.width != null && data.height != null) "${data.width} × ${data.height}" else unknown).joinToString(" · ")
                DiagnosticField.FPS -> "${decimal(data.sourceFps, "", 3).trim()} / ${decimal(data.presentedFps, "", 1).trim()}"
                DiagnosticField.NETWORK -> decimal(data.networkBytesPerSecond?.div(1024 * 1024), "MiB/s", 2)
                DiagnosticField.BITRATE -> decimal(data.bitrate?.div(1_000_000.0), "Mbps", 2)
                DiagnosticField.COLOR -> data.color ?: unknown
                DiagnosticField.SCREEN -> "[${screen?.replace(" ", "") ?: unknown}] [${viewport.replace(" ", "")}]"
                DiagnosticField.FILE -> decimal(data.fileBytes?.div(1024.0 * 1024), "MiB", 1)
                DiagnosticField.DECODER -> when (data.hardware) { true -> hardware; false -> software; null -> unknown }
                DiagnosticField.BUFFER -> decimal(data.bufferedMs?.div(1000.0), "s")
                DiagnosticField.CPU -> decimal(data.cpuPercent, "%")
                DiagnosticField.GPU -> decimal(data.gpuPercent, "%")
            }
        }.distinctUntilChanged()
    }
    // Hold State without reading it in composition. Equal formatted values never invalidate drawing.
    val text = formatted.collectAsState(unknown)
    val measurer = rememberTextMeasurer(cacheSize = 1)
    Canvas(modifier.graphicsLayer().testTag("diagnostics-value-${field.name}")
        .semantics { contentDescription = text.value }) {
        drawText(measurer, text.value, style = style, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, size = size)
    }
}
