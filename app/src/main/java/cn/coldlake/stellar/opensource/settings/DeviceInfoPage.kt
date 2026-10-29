package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.coldlake.stellar.opensource.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DeviceInfoPage(model: DeviceInfoViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val displayId = LocalView.current.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    LaunchedEffect(model, displayId) { model.ensureLoaded(displayId) }
    DeviceInfoContent(state, onRefresh = { model.refresh(displayId) })
}

@Composable
internal fun DeviceInfoContent(state: DeviceInfoState, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    val listState = rememberLazyListState()
    var expanded by rememberSaveable { mutableStateOf(listOf("device")) }
    fun toggle(key: String) { expanded = if (key in expanded) expanded - key else expanded + key }
    val snapshot = state.snapshot
    val video = remember(snapshot) { snapshot?.codecs.orEmpty().filter { it.mime.startsWith("video/") } }
    val audio = remember(snapshot) { snapshot?.codecs.orEmpty().filter { it.mime.startsWith("audio/") } }
    Column(Modifier.fillMaxSize().testTag("device-info-content")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.di_snapshot), Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
            TextButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.testTag("device-info-refresh")) {
                Icon(painterResource(R.drawable.material_symbol_refresh), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.di_refresh))
            }
        }
        if (snapshot != null) {
            val timestamp = remember(snapshot.sampledAtMillis, locale) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale).format(Date(snapshot.sampledAtMillis))
            }
            Text(stringResource(R.string.di_sample_time, timestamp, NumberFormat.getIntegerInstance(locale).format(snapshot.durationMillis)),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("device-info-sample-time"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("device-info-loading")) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("device-info-progress"),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text(stringResource(R.string.di_loading), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.failed) Text(stringResource(if (snapshot == null) R.string.di_sample_failed else R.string.di_refresh_failed),
            Modifier.padding(16.dp).testTag("device-info-error"), color = MaterialTheme.colorScheme.error)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("device-info-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item("explanation", contentType = "note") {
                Text(stringResource(R.string.di_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            snapshot?.sections?.forEach { section ->
                item("section-${section.key}", contentType = "section") {
                    DeviceInfoHeading(stringResource(section.title), section.key in expanded,
                        "device-info-section-${section.key}", onClick = { toggle(section.key) })
                }
                if (section.key in expanded) {
                    if (section.failed) item("error-${section.key}", contentType = "note") {
                        Text(stringResource(R.string.di_read_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                    items(section.fields, key = { "${section.key}/${it.key}" }, contentType = { "field" }) { field ->
                        DeviceInfoFieldRow(field, "${section.key}/${field.key}", context, locale)
                    }
                }
            }
            if (snapshot != null) {
                if (snapshot.codecsFailed) item("codecs-error", contentType = "note") {
                    Text(stringResource(R.string.di_codecs_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                listOf(Triple("video", R.string.di_video_decoders, video), Triple("audio", R.string.di_audio_decoders, audio)).forEach { (kind, title, codecs) ->
                    item("group-$kind", contentType = "section") {
                        DeviceInfoHeading(stringResource(title, codecs.size), kind in expanded,
                            "device-info-section-$kind", onClick = { toggle(kind) })
                    }
                    if (kind in expanded) {
                        if (codecs.isEmpty() && !snapshot.codecsFailed) item("empty-$kind", contentType = "note") {
                            Text(stringResource(R.string.di_none), Modifier.padding(16.dp))
                        }
                        codecs.forEach { codec ->
                            val key = "codec-${codec.key}"
                            item(key, contentType = "codec") {
                                DeviceInfoHeading(codec.name, key in expanded, key, subtitle = codec.mime,
                                    onClick = { toggle(key) })
                            }
                            if (key in expanded) items(codec.fields, key = { "$key/${it.key}" }, contentType = { "field" }) { field ->
                                DeviceInfoFieldRow(field, "$key/${field.key}", context, locale)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoHeading(title: String, expanded: Boolean, tag: String, subtitle: String? = null, onClick: () -> Unit) {
    val state = stringResource(if (expanded) R.string.di_expanded else R.string.di_collapsed)
    ListItem(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag).semantics { heading(); stateDescription = state },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        trailingContent = { Icon(painterResource(R.drawable.material_symbol_chevron_right), null,
            Modifier.rotate(if (expanded) 90f else 0f)) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        content = { Text(title, style = MaterialTheme.typography.titleSmallEmphasized) })
}

@Composable
private fun DeviceInfoFieldRow(field: DeviceInfoField, key: String, context: Context, locale: Locale) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("device-info-field-$key")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(field.title, *field.titleArguments.toTypedArray()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(formatDeviceInfoValue(field.value, context, locale), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

internal fun formatDeviceInfoValue(value: DeviceInfoValue, context: Context, locale: Locale): String {
    fun numeric(number: Double, decimals: Int = 4) = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = decimals }.format(number)
    return when (value) {
        is DeviceInfoValue.Text -> value.text
        is DeviceInfoValue.Message -> context.getString(value.resource)
        is DeviceInfoValue.Timestamp -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale).format(Date(value.millis))
        is DeviceInfoValue.Number -> context.getString(value.format, numeric(value.number))
        is DeviceInfoValue.Bytes -> {
            val (divisor, unit) = when {
                value.bytes >= 1L.shl(30) -> 1L.shl(30) to R.string.di_gib
                value.bytes >= 1L.shl(20) -> 1L.shl(20) to R.string.di_mib
                value.bytes >= 1L.shl(10) -> 1L.shl(10) to R.string.di_kib
                else -> 1L to R.string.di_bytes_exact
            }
            context.getString(unit, numeric(value.bytes.toDouble() / divisor, 2))
        }
        is DeviceInfoValue.Lines -> value.values.joinToString("\n") { formatDeviceInfoValue(it, context, locale) }
    }
}
