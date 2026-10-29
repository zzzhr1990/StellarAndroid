package com.stellar.player.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.stellar.player.contract.*
import java.util.Locale

internal val HillsInk = Color(0xFFE7E0EC)
internal val HillsPanel = Color(0xD926252B)
internal val HillsControlBackground = Color(0x991C1B20)

private val HillsControlsGradient = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = .5f),
    .3f to Color.Transparent,
    .7f to Color.Transparent,
    1f to Color.Black.copy(alpha = .5f),
)

private val CorePanel?.isHillsSidePanel: Boolean
    get() = this != null && this != CorePanel.MORE && this != CorePanel.SPEED

/** Draw across the video bounds, before applying the controls' safe-area padding. */
@Composable
internal fun HillsControlsScrim(ui: PlayerInteractionState, activePanel: CorePanel?) {
    if (ui.controlsVisible && !activePanel.isHillsSidePanel) {
        Box(Modifier.fillMaxSize().background(HillsControlsGradient))
    }
}

/** Fullscreen chrome only. The video Surface and high-frequency timeline stay outside this node. */
@Composable
internal fun BoxScope.HillsFullscreen(
    snapshot: PlaybackSnapshot, ui: PlayerInteractionState, actions: PlayerInteractionActions,
    tools: PlayerToolsState, activePanel: CorePanel?, onCommand: (PlaybackCommand) -> Unit,
    onBack: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    hasPrevious: Boolean, hasNext: Boolean, onFeature: (PlayerFeature) -> Unit,
    onPanel: (CorePanel) -> Unit, onSpeed: (Float) -> Unit, onPip: () -> Unit, pipAvailable: Boolean,
    status: @Composable BoxScope.() -> Unit, buffering: State<PlaybackBuffer>?,
    timeline: State<PlaybackSnapshot>?, liveInteraction: State<PlayerInteractionState>?,
    previousEnabled: Boolean, nextEnabled: Boolean,
    rightHanded: Boolean, oneHanded: Boolean, onOneHanded: (Boolean) -> Unit,
    arcSeek: ArcSeekHandle, onFullscreen: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val halfHeight = (maxHeight - 64.dp) / 2
    val timeHeight = with(LocalDensity.current) { 16.sp.toDp() }
    val radius = minOf(140.dp, halfHeight - 22.dp, (halfHeight - 24.dp) / .707107f - 58.dp,
        (maxHeight + 48.dp) / 2 - 68.dp - timeHeight)
    val arcFits = radius >= 114.dp && maxWidth >= 320.dp
    val arcMode = oneHanded && arcFits && snapshot.media?.kind == MediaKind.VIDEO
    val handAlignment = if (rightHanded) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft
    val sidePanel = activePanel.isHillsSidePanel
    val chrome = ui.controlsVisible && !ui.locked && !sidePanel
    val options = ui.options
    if (ui.controlsVisible && !sidePanel) status()
    if (chrome) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HillsIcon(R.drawable.hills_arrow_back, stringResource(R.string.back), "player-back", onBack, mirror = true)
            if (arcMode) Text(snapshot.media?.title.orEmpty(), color = HillsInk, fontSize = 18.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("player-title"))
            else Spacer(Modifier.weight(1f))
            val codec = snapshot.details?.videoCodec?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)?.take(12)
                ?: stringResource(R.string.hills_unknown_codec)
            val engine = if (snapshot.simulated) stringResource(R.string.hills_demo_engine) else when (snapshot.details?.videoDynamicRange) {
                VideoDynamicRange.DOLBY_VISION -> stringResource(R.string.hills_codec_dv, codec)
                VideoDynamicRange.HDR -> stringResource(R.string.hills_codec_hdr, codec)
                else -> codec
            }
            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).testTag("media-decoder")
                .semantics { contentDescription = engine; role = Role.Button }
                .clickable { onPanel(CorePanel.INFO) }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(engine, color = HillsInk, fontSize = 16.sp, maxLines = 1)
            }
            if (snapshot.media?.kind == MediaKind.VIDEO) {
                if (!arcMode) HillsIcon(R.drawable.hills_aspect_ratio, stringResource(R.string.screen_fit), "screen-fit", { onPanel(CorePanel.FIT) })
                if (pipAvailable) HillsIcon(R.drawable.hills_picture_in_picture_alt, stringResource(R.string.picture_in_picture), "pip", onPip)
            }
            HillsIcon(R.drawable.hills_more_vert, stringResource(R.string.more), "more", { onFeature(PlayerFeature.MORE) })
        }
        if (!arcMode) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp)) {
            Text(snapshot.media?.title.orEmpty(), color = HillsInk, fontSize = 22.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("player-title"))
            SeekControl(snapshot, ui, actions, true, Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
                buffering, timeline, liveInteraction, hills = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val playing = snapshot.status == PlaybackStatus.PLAYING
                if (!options.showPlayButton) HillsIcon(if (playing) R.drawable.hills_pause else R.drawable.hills_play_arrow,
                    stringResource(if (playing) R.string.pause else if (snapshot.status == PlaybackStatus.ENDED) R.string.replay else R.string.play),
                    "play-pause", { onCommand(if (playing) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId)) },
                    enabled = snapshot.media != null)
                if (snapshot.media?.kind == MediaKind.VIDEO) HillsIcon(R.drawable.hills_photo_camera,
                    stringResource(R.string.screenshot), "snapshot", { onFeature(PlayerFeature.SNAPSHOT) })
                if (hasPrevious) HillsIcon(R.drawable.hills_skip_previous, stringResource(R.string.previous_episode), "previous", onPrevious, enabled = previousEnabled)
                if (hasNext) HillsIcon(R.drawable.hills_skip_next, stringResource(R.string.next_episode), "next", onNext, enabled = nextEnabled)
                if (hasPrevious || hasNext) HillsIcon(R.drawable.hills_playlist_play, stringResource(R.string.playlist), "playlist", { onFeature(PlayerFeature.PLAYLIST) })
                Spacer(Modifier.weight(1f))
                if ((snapshot.media?.videoTracks?.size ?: 0) > 1) HillsIcon(R.drawable.hills_video_library,
                    stringResource(R.string.video_tracks), "video-tracks", { onPanel(CorePanel.VIDEO) })
                if (snapshot.capabilities.canSelectAudio) HillsIcon(R.drawable.hills_headphones, stringResource(R.string.audio_tracks), "audio-tracks", { onFeature(PlayerFeature.AUDIO) })
                // The panel also explains an empty track list; selection capability only gates its actions.
                if (snapshot.media?.kind == MediaKind.VIDEO)
                    HillsIcon(R.drawable.hills_closed_caption, stringResource(R.string.subtitles), "subtitles", { onFeature(PlayerFeature.SUBTITLES) })
            }
        }
        if (!arcMode && (options.showPlayButton || options.showSeekButtons)) {
            // Seek remains physically left-to-right in RTL, as does the timeline.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.align(Alignment.Center).fillMaxWidth(.6f).testTag("center-playback-controls"),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (options.showSeekButtons) HillsIcon(R.drawable.hills_fast_rewind,
                        stringResource(R.string.rewind, numberLabel(options.rewindSeconds)), "rewind", {
                            val current = timeline?.value ?: snapshot
                            onCommand(PlaybackCommand.Seek(current.sessionId,
                                (current.positionMs - options.rewindSeconds * 1000L).coerceAtLeast(0)))
                        }, enabled = snapshot.canSeek) else Spacer(Modifier.size(48.dp))
                    if (options.showPlayButton) {
                        val playing = snapshot.status == PlaybackStatus.PLAYING
                        HillsIcon(if (playing) R.drawable.hills_pause else R.drawable.hills_play_arrow,
                            stringResource(if (playing) R.string.pause else R.string.play), "play-pause", {
                                onCommand(if (playing) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId))
                            }, enabled = snapshot.media != null)
                    } else Spacer(Modifier.size(48.dp))
                    if (options.showSeekButtons) HillsIcon(R.drawable.hills_fast_forward,
                        stringResource(R.string.forward, numberLabel(options.forwardSeconds)), "forward", {
                            val current = timeline?.value ?: snapshot
                            onCommand(PlaybackCommand.Seek(current.sessionId,
                                (current.positionMs + options.forwardSeconds * 1000L).coerceAtMost(current.durationMs ?: 0)))
                        }, enabled = snapshot.canSeek) else Spacer(Modifier.size(48.dp))
                }
            }
        }
        if (!arcMode && snapshot.media?.kind == MediaKind.VIDEO) {
            Column(Modifier.align(handAlignment).padding(horizontal = 8.dp).testTag("hand-controls").clip(RoundedCornerShape(9.dp))
                .background(HillsControlBackground), horizontalAlignment = Alignment.CenterHorizontally) {
                HillsIcon(R.drawable.hills_lock_open, stringResource(R.string.lock), "lock", { onFeature(PlayerFeature.LOCK) })
                val speedDescription = stringResource(R.string.speed)
                Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("speed")
                    .semantics { contentDescription = speedDescription; role = Role.Button }
                    .clickable(enabled = snapshot.capabilities.canSetSpeed) { onFeature(PlayerFeature.SPEED) }, contentAlignment = Alignment.Center) {
                    Text(hillsSpeedLabel(snapshot.speed), color = HillsInk.copy(alpha = if (snapshot.capabilities.canSetSpeed) 1f else .35f),
                        fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
                HillsIcon(R.drawable.hills_radio_button_checked, stringResource(R.string.enter_one_handed), "one-handed",
                    { onOneHanded(true) }, enabled = arcFits)
            }
        }
        if (arcMode && activePanel == null) ArcPlaybackControls(snapshot, ui, actions, radius, rightHanded, arcSeek,
            onCommand, onFeature, onPanel, { onOneHanded(false) }, onFullscreen, buffering, timeline, liveInteraction)
    }
    if (ui.controlsVisible && !sidePanel) {
        Column(Modifier.align(if (ui.locked) handAlignment else if (rightHanded) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!ui.locked && ui.scale != 1f) HillsIcon(R.drawable.hills_zoom_in_map, stringResource(R.string.reset_zoom), "reset-zoom",
                { actions.resetScale(InteractionContext(snapshot.sessionId, ui.revision)) })
            if (ui.locked || snapshot.media?.kind != MediaKind.VIDEO) HillsIcon(if (ui.locked) R.drawable.hills_lock else R.drawable.hills_lock_open,
                stringResource(if (ui.locked) R.string.unlock else R.string.lock), if (ui.locked) "unlock" else "lock", { onFeature(PlayerFeature.LOCK) })
        }
    }
    }
}

@Composable
internal fun HillsIcon(icon: Int, label: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, mirror: Boolean = false) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label; role = Role.Button }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(24.dp).graphicsLayer { scaleX = if (mirror && rtl) -1f else 1f },
            tint = HillsInk.copy(alpha = if (enabled) 1f else .35f))
    }
}
