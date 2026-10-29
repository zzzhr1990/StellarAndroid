package com.stellar.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stellar.player.contract.*
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BoxWithConstraintsScope.EqualizerPanel(s: PlaybackSnapshot, actions: PlayerPanelActions, landscape: Boolean) {
    val dismiss = stringResource(R.string.dismiss_panel)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
        .testTag("panel-scrim").semantics { contentDescription = dismiss }
        .clickable(onClick = actions::dismissPanel))
    Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
            .then(if (landscape) Modifier.width(minOf(440.dp, maxWidth)).fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility))
                else Modifier.fillMaxWidth().heightIn(max = maxHeight * .86f)).testTag("equalizer-panel"),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eq_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = actions::panelBack, modifier = Modifier.testTag("panel-back")) {
                    Text(stringResource(R.string.panel_back))
                }
            }
            if (!s.capabilities.canEqualize) {
                Text(stringResource(R.string.eq_unavailable), Modifier.padding(vertical = 24.dp).testTag("eq-unavailable"))
            } else key(s.sessionId) {
                val eq = s.equalizer
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("eq-scroll")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.eq_enable), Modifier.weight(1f))
                        val label = stringResource(R.string.eq_enable)
                        Switch(eq.enabled, { actions.setEqualizer(eq.copy(enabled = it)) },
                            Modifier.testTag("eq-enable").semantics { contentDescription = label })
                    }
                    Text(stringResource(R.string.eq_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EqualizerPreset.entries.forEach { preset ->
                            val title = stringResource(when(preset) {
                                EqualizerPreset.FLAT -> R.string.eq_flat
                                EqualizerPreset.BASS -> R.string.eq_bass
                                EqualizerPreset.VOICE -> R.string.eq_voice
                                EqualizerPreset.BRIGHT -> R.string.eq_bright
                            })
                            FilterChip(selected = eq.bandsDb == preset.bandsDb,
                                onClick = { actions.setEqualizer(eq.copy(enabled = true, bandsDb = preset.bandsDb)) },
                                label = { Text(title) }, modifier = Modifier.testTag("eq-preset-${preset.name}"))
                        }
                    }
                    EqSlider(stringResource(R.string.eq_preamp), "eq-preamp", eq.preampDb, -24f..0f, eq.enabled) {
                        actions.setEqualizer(eq.copy(preampDb = it))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    EqualizerSettings.frequenciesHz.forEachIndexed { index, frequency ->
                        val label = if (frequency >= 1000) stringResource(R.string.eq_khz, numberLabel((frequency / 1000).roundToInt()))
                            else stringResource(R.string.eq_hz, numberLabel(frequency.roundToInt()))
                        EqSlider(label, "eq-band-$index", eq.bandsDb[index], -12f..12f, eq.enabled) { gain ->
                            actions.setEqualizer(eq.copy(bandsDb = eq.bandsDb.mapIndexed { i, value -> if (i == index) gain else value }))
                        }
                    }
                    Text(stringResource(R.string.eq_headroom), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = { actions.setEqualizer(EqualizerSettings()) }, modifier = Modifier.fillMaxWidth().testTag("eq-reset")) {
                        Text(stringResource(R.string.eq_reset))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun EqSlider(label: String, tag: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, commit: (Float) -> Unit) {
    var preview by remember(value, enabled) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.eq_db, numberLabel(preview.roundToInt())), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = preview, onValueChange = { preview = it.roundToInt().toFloat() },
            onValueChangeFinished = { commit(preview) }, valueRange = range, steps = 23, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label })
    }
}
