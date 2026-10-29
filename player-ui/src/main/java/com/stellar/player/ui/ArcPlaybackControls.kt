package com.stellar.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.*
import com.stellar.player.contract.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Only the controls change mode. Video, timeline, panels and interaction ownership stay in the host. */
@Composable
internal fun ArcPlaybackControls(
    snapshot: PlaybackSnapshot, ui: PlayerInteractionState, actions: PlayerInteractionActions,
    radius: Dp, rightHanded: Boolean, handle: ArcSeekHandle,
    onCommand: (PlaybackCommand) -> Unit, onFeature: (PlayerFeature) -> Unit,
    onPanel: (CorePanel) -> Unit, onClose: () -> Unit, onFullscreen: () -> Unit,
    buffering: State<PlaybackBuffer>?, timeline: State<PlaybackSnapshot>?, liveInteraction: State<PlayerInteractionState>?,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("arc-controls"), contentAlignment = AbsoluteAlignment.TopLeft) {
        val cx = if (rightHanded) maxWidth - 24.dp else 24.dp
        val cy = (maxHeight + 48.dp) / 2 // 56dp above, 8dp below the available control area.
        val sign = if (rightHanded) -1 else 1
        val expansion = ((radius.value - 114f) / 26f).coerceIn(0f, 1f)
        val innerX = (26 + 8 * expansion).dp
        val innerY = (60 + 16 * expansion).dp
        val playSize = (64 + 12 * expansion).dp
        val density = LocalDensity.current
        val dragging = ui.gesture == GestureKind.SEEK
        val position = remember(snapshot, ui, timeline, liveInteraction) { derivedStateOf {
            val current = timeline?.value ?: snapshot
            (liveInteraction?.value ?: ui).previewMs ?: current.pendingSeek?.targetMs ?: current.positionMs
        } }
        ArcSeekTrack(snapshot, ui, actions, handle, rightHanded, cx, cy, radius, position, buffering, timeline)

        @Composable fun button(x: Dp, y: Dp, icon: Int, label: String, tag: String,
            enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) {
            val size = if (primary) playSize else 48.dp
            Box(Modifier.absoluteOffset { with(density) {
                IntOffset((cx + x * sign - size / 2).roundToPx(), (cy + y - size / 2).roundToPx())
            } }.size(size).alpha(if (dragging) .35f else 1f).testTag(tag)
                .semantics { contentDescription = label; role = Role.Button }
                .clip(CircleShape).clickable(enabled = enabled && !dragging, onClick = action),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (primary) playSize else 40.dp).background(HillsControlBackground, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(if (primary) 34.dp else 24.dp),
                        tint = HillsInk.copy(alpha = if (enabled) 1f else .35f))
                }
            }
        }
        val playing = snapshot.status == PlaybackStatus.PLAYING
        button((60 + 12 * expansion).dp, 0.dp, if (playing) R.drawable.hills_pause else R.drawable.hills_play_arrow,
            stringResource(if (playing) R.string.pause else if (snapshot.status == PlaybackStatus.ENDED) R.string.replay else R.string.play),
            "play-pause", snapshot.media != null, primary = true) {
            onCommand(if (playing) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId))
        }
        button(innerX, -innerY, R.drawable.hills_speed, stringResource(R.string.speed), "speed", snapshot.capabilities.canSetSpeed) {
            onFeature(PlayerFeature.SPEED)
        }
        button(0.dp, 0.dp, R.drawable.hills_close, stringResource(R.string.exit_one_handed), "arc-close", action = onClose)
        button(innerX, innerY, R.drawable.hills_fullscreen_exit, stringResource(R.string.exit_fullscreen), "fullscreen", action = onFullscreen)
        val icons = listOf(R.drawable.hills_closed_caption, R.drawable.hills_headphones,
            R.drawable.hills_playlist_play, R.drawable.hills_aspect_ratio, R.drawable.hills_lock_open)
        val labels = listOf(R.string.subtitles, R.string.audio_tracks, R.string.playlist, R.string.screen_fit, R.string.lock)
        val tags = listOf("subtitles", "audio-tracks", "playlist", "screen-fit", "lock")
        val outerRadius = radius + 58.dp
        for (index in icons.indices) {
            val angle = (45 + index * 22.5) * PI / 180
            val enabled = index != 1 || snapshot.capabilities.canSelectAudio
            button(outerRadius * sin(angle).toFloat(), outerRadius * -cos(angle).toFloat(), icons[index],
                stringResource(labels[index]), tags[index], enabled) {
                when (index) {
                    0 -> onFeature(PlayerFeature.SUBTITLES)
                    1 -> onFeature(PlayerFeature.AUDIO)
                    2 -> onFeature(PlayerFeature.PLAYLIST)
                    3 -> onPanel(CorePanel.FIT)
                    4 -> onFeature(PlayerFeature.LOCK)
                }
            }
        }
        ArcTime(position, snapshot.durationMs, Modifier.absoluteOffset {
            with(density) { IntOffset((if (rightHanded) cx - 124.dp else cx + 4.dp).roundToPx(),
                (cy - radius - 16.sp.toDp() - 4.dp).roundToPx()) }
        }.width(120.dp), "position")
        ArcTime(null, snapshot.durationMs, Modifier.absoluteOffset {
            with(density) { IntOffset((if (rightHanded) cx - 124.dp else cx + 4.dp).roundToPx(), (cy + radius + 4.dp).roundToPx()) }
        }.width(120.dp), "duration")
    }
}

@Composable
private fun ArcTime(position: State<Long>?, duration: Long?, modifier: Modifier, tag: String) {
    val seconds by remember(position, duration) { derivedStateOf { (position?.value ?: duration)?.div(1000) } }
    Text(formatTime(seconds?.times(1000)), color = HillsInk, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr),
        modifier = modifier.testTag(tag))
}

@Composable
private fun ArcSeekTrack(snapshot: PlaybackSnapshot, ui: PlayerInteractionState, actions: PlayerInteractionActions,
    handle: ArcSeekHandle, rightHanded: Boolean, cx: Dp, cy: Dp, radius: Dp,
    position: State<Long>, buffering: State<PlaybackBuffer>?, timeline: State<PlaybackSnapshot>?) {
    val density = LocalDensity.current
    val r = with(density) { radius.toPx() }
    val halfBand = with(density) { 22.dp.toPx() }
    val duration = snapshot.durationMs ?: 0
    val identity = InteractionContext(snapshot.sessionId, ui.revision)
    val progressLabel = stringResource(R.string.progress)
    val resources = LocalResources.current
    DisposableEffect(handle, r, halfBand, rightHanded, duration, snapshot.canSeek, cx, cy) { onDispose { handle.update(null) } }
    // The tight Canvas bounds also give TalkBack an appropriate focus region. Pointer ownership is
    // decided by the arc hit band in PlayerTouch, leaving the inside/outside available to screen gestures.
    Canvas(Modifier.absoluteOffset { with(density) {
        IntOffset((if (rightHanded) cx - radius - 22.dp else cx - 22.dp).roundToPx(), (cy - radius - 22.dp).roundToPx())
    } }.size(radius + 44.dp, radius * 2 + 44.dp).testTag("arc-seek")
        .onGloballyPositioned {
            val origin = it.positionInWindow()
            handle.update(ArcSeekTarget(origin.x + if (rightHanded) r + halfBand else halfBand,
                origin.y + r + halfBand, r, halfBand, rightHanded, duration, snapshot.canSeek))
        }.semantics {
            contentDescription = progressLabel
            stateDescription = resources.getString(R.string.seek_preview, formatTime(position.value), formatTime(snapshot.durationMs))
            progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f, 0f..1f)
            if (!snapshot.canSeek) disabled()
            else setProgress { value ->
                if (!value.isFinite()) false else actions.begin(identity, GestureKind.SEEK)?.let {
                    actions.change(it, value.toDouble().coerceIn(0.0, 1.0) * duration)
                    actions.finish(it, true)
                    true
                } ?: false
            }
        }) {
        val center = Offset(if (rightHanded) r + halfBand else halfBand, r + halfBand)
        val start = -90f
        val sweep = if (rightHanded) -180f else 180f
        fun arc(from: Float, to: Float, color: androidx.compose.ui.graphics.Color, width: Float) {
            if (to > from) drawArc(color, start + sweep * from, sweep * (to - from), false,
                Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(width, cap = StrokeCap.Round))
        }
        arc(0f, 1f, PlayerColors.Track, 4.dp.toPx())
        val current = timeline?.value ?: snapshot
        val buffer = buffering?.value
        if (duration > 0 && current.pendingSeek == null && buffer?.sessionId == snapshot.sessionId) {
            val from = buffer.startMs.coerceIn(0, duration).toFloat() / duration
            val to = buffer.endMs.coerceIn(0, duration).toFloat() / duration
            arc(from, to, HillsInk.copy(alpha = .65f), 4.dp.toPx())
        }
        val fraction = if (duration > 0) (position.value.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
        arc(0f, fraction.toFloat(), HillsInk, 5.dp.toPx())
        if (snapshot.canSeek) drawCircle(HillsInk, (if (ui.gesture == GestureKind.SEEK) 11.dp else 8.dp).toPx(),
            Offset(center.x + (if (rightHanded) -1 else 1) * r * sin(PI * fraction).toFloat(), center.y - r * cos(PI * fraction).toFloat()))
    }
}
