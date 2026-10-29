@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cn.coldlake.stellar.opensource.casting

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stellar.player.contract.*
import cn.coldlake.stellar.opensource.R
import com.stellar.player.ui.formatTime
import com.stellar.player.ui.rememberPlaybackChrome
import com.stellar.player.ui.R as PlayerR
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@Composable
internal fun CastRemoteScreen(controller: CastingController, onCollapse: () -> Unit, onLocalSubtitle: () -> Unit,
    onUrlSubtitle: () -> Unit, subtitleImporting: Boolean = false, subtitleMessage: Int? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val timeline = controller.snapshots.collectAsStateWithLifecycle()
    val snapshot by rememberPlaybackChrome(timeline)
    var subtitles by rememberSaveable(snapshot.sessionId) { mutableStateOf(false) }
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(entered, enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
        slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 12 }) {
        Column(Modifier.fillMaxSize().testTag("cast-remote-screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse, modifier = Modifier.testTag("cast-collapse")) {
                    Icon(painterResource(R.drawable.material_symbol_arrow_downward), stringResource(R.string.cast_collapse))
                }
                Text(stringResource(R.string.cast_remote_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = controller::openPanel) {
                    Icon(painterResource(R.drawable.material_symbol_more_vert), stringResource(R.string.casting_title))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.material_symbol_movie), null, Modifier.size(64.dp))
                            Text(state.device?.let { stringResource(R.string.casting_on_device, it.name) }.orEmpty(),
                                style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text(stringResource(castStatusLabel(snapshot, state)), style = MaterialTheme.typography.labelLarge)
                            if (state.connecting || state.subtitleBusy || snapshot.status == PlaybackStatus.BUFFERING)
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    Text(snapshot.media?.title.orEmpty(), style = MaterialTheme.typography.headlineSmallEmphasized,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.testTag("cast-title"))
                    CastTimeline(controller)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = { controller.seekBy(-15000) }, enabled = snapshot.canSeek && !state.connecting && !state.subtitleBusy,
                            modifier = Modifier.size(56.dp).testTag("cast-rewind")) {
                            Icon(painterResource(PlayerR.drawable.hills_fast_rewind), stringResource(R.string.cast_rewind))
                        }
                        CastPlayButton(controller, snapshot, state, Modifier.size(88.dp), "cast-play")
                        FilledTonalIconButton(onClick = { controller.seekBy(15000) }, enabled = snapshot.canSeek && !state.connecting && !state.subtitleBusy,
                            modifier = Modifier.size(56.dp).testTag("cast-forward")) {
                            Icon(painterResource(PlayerR.drawable.hills_fast_forward), stringResource(R.string.cast_forward))
                        }
                    }
                    state.volume?.let { CastVolume(controller, it, !state.connecting && !state.subtitleBusy) }
                    FilledTonalButton(onClick = { subtitles = true }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().testTag("cast-subtitles")) {
                        Icon(painterResource(PlayerR.drawable.hills_closed_caption), null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cast_subtitles))
                    }
                    if (subtitleImporting) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    subtitleMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    if (state.subtitleFailed) Text(stringResource(R.string.casting_subtitle_error),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cast-subtitle-error"))
                    state.failure?.let { Text(stringResource(castFailureLabel(it)), color = MaterialTheme.colorScheme.error) }
                    if (state.relaying) Text(stringResource(R.string.cast_phone_relay), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = controller::returnToPhone, enabled = !state.connecting && !state.subtitleBusy,
                        modifier = Modifier.fillMaxWidth().testTag("cast-return")) {
                        Icon(painterResource(R.drawable.material_symbol_smartphone), null)
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.casting_return))
                    }
                    TextButton(onClick = controller::cancelSession, modifier = Modifier.testTag("cast-stop")) {
                        Text(stringResource(R.string.casting_stop), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (subtitles) ModalBottomSheet(onDismissRequest = { subtitles = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 24.dp).testTag("cast-subtitle-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cast_subtitles), style = MaterialTheme.typography.titleLargeEmphasized)
            Text(stringResource(if (state.subtitleBusy) PlayerR.string.remote_subtitles_loading
                else if (snapshot.capabilities.canImportSubtitle) PlayerR.string.remote_subtitles_hint
                else PlayerR.string.remote_subtitles_unavailable), style = MaterialTheme.typography.bodyMedium)
            if (state.subtitleBusy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            if (state.subtitleFailed) Text(stringResource(R.string.casting_subtitle_error), color = MaterialTheme.colorScheme.error)
            val enabled = snapshot.capabilities.canSelectSubtitle && !state.subtitleBusy
            SubtitleChoice(stringResource(PlayerR.string.remote_subtitles_off), snapshot.selectedSubtitleId == null, enabled, "cast-subtitle-off") {
                controller.dispatch(PlaybackCommand.SelectTrack(snapshot.sessionId, TrackKind.SUBTITLE, null))
            }
            snapshot.externalSubtitle?.let { subtitle ->
                SubtitleChoice(subtitle.label, snapshot.selectedSubtitleId == subtitle.id, enabled, "cast-subtitle-selected") {
                    controller.dispatch(PlaybackCommand.SelectTrack(snapshot.sessionId, TrackKind.SUBTITLE, subtitle.id))
                }
            }
            if (snapshot.capabilities.canImportSubtitle || state.subtitleBusy) {
                FilledTonalButton(onClick = { subtitles = false; onLocalSubtitle() }, enabled = !state.subtitleBusy && !subtitleImporting,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().testTag("cast-subtitle-file")) {
                    Text(stringResource(R.string.cast_subtitle_file))
                }
                TextButton(onClick = { subtitles = false; onUrlSubtitle() }, enabled = !state.subtitleBusy && !subtitleImporting,
                    modifier = Modifier.fillMaxWidth().testTag("cast-subtitle-url")) { Text(stringResource(R.string.cast_subtitle_url)) }
            }
        }
    }
}

@Composable private fun SubtitleChoice(title: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .testTag(tag).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun CastTimeline(controller: CastingController) {
    val snapshot by controller.snapshots.collectAsStateWithLifecycle()
    var dragging by remember(snapshot.sessionId) { mutableStateOf<Float?>(null) }
    var pending by remember(snapshot.sessionId) { mutableStateOf<Long?>(null) }
    val duration = snapshot.durationMs?.coerceAtLeast(0) ?: 0
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        withTimeoutOrNull(6000) { controller.snapshots.first { abs(it.positionMs - target) < 2500 } }
        pending = null
    }
    val position = dragging?.toLong() ?: pending ?: snapshot.positionMs
    Column(Modifier.fillMaxWidth()) {
        Slider(value = position.coerceIn(0, duration).toFloat(),
            onValueChange = { dragging = it }, onValueChangeFinished = {
                dragging?.toLong()?.let { pending = it; controller.seekTo(it) }; dragging = null
            }, enabled = snapshot.canSeek && duration > 0, valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth().testTag("cast-seek"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.labelLarge)
            Text(formatTime(snapshot.durationMs), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun CastVolume(controller: CastingController, current: Float, enabled: Boolean) {
    var dragging by remember(controller) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.casting_volume), style = MaterialTheme.typography.titleSmall)
        Slider(value = dragging ?: current.coerceIn(0f, 1f), onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(controller::volume); dragging = null },
            enabled = enabled, modifier = Modifier.testTag("cast-volume"))
    }
}

@Composable private fun CastPlayButton(controller: CastingController, snapshot: PlaybackSnapshot, state: CastState,
    modifier: Modifier, tag: String) {
    val playing = snapshot.status == PlaybackStatus.PLAYING
    Button(onClick = controller::togglePlayback, shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(0.dp),
        enabled = !state.connecting && !state.subtitleBusy && (!playing || state.canPause), modifier = modifier.testTag(tag)) {
        Crossfade(playing, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "cast-play-state") {
            Icon(painterResource(if (it) PlayerR.drawable.hills_pause else PlayerR.drawable.hills_play_arrow),
                stringResource(if (it) R.string.cast_pause else R.string.cast_play), Modifier.size(32.dp))
        }
    }
}

@Composable
internal fun CastMiniControls(controller: CastingController, onExpand: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val timeline = controller.snapshots.collectAsStateWithLifecycle()
    val snapshot by rememberPlaybackChrome(timeline)
    Surface(onClick = onExpand, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("cast-mini")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.material_symbol_movie), null, Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(snapshot.media?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(state.device?.let { stringResource(R.string.casting_on_device, it.name) }.orEmpty(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            CastPlayButton(controller, snapshot, state, Modifier.size(48.dp), "cast-mini-play")
        }
    }
}

internal fun castStatusLabel(snapshot: PlaybackSnapshot, state: CastState) = when {
    state.connecting -> R.string.cast_connecting
    state.subtitleBusy -> R.string.cast_updating_subtitle
    snapshot.status == PlaybackStatus.BUFFERING -> R.string.cast_buffering
    snapshot.status == PlaybackStatus.PLAYING -> R.string.cast_playing
    snapshot.status == PlaybackStatus.ENDED -> R.string.cast_ended
    else -> R.string.cast_paused
}
internal fun castFailureLabel(failure: CastFailure) = when (failure) {
    CastFailure.PERMISSION -> R.string.casting_permission
    CastFailure.NO_NETWORK -> R.string.casting_network
    CastFailure.VPN_BLOCKED -> R.string.casting_vpn_blocked
    CastFailure.UNAVAILABLE -> R.string.casting_unavailable
    CastFailure.SOURCE -> R.string.casting_source
    CastFailure.LOAD -> R.string.casting_load_error
    CastFailure.CONTROL -> R.string.casting_control_error
    CastFailure.DISCONNECTED -> R.string.casting_disconnected
}
