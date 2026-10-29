package com.stellar.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.player.contract.*

enum class PlayerFeature { CAST, SUBTITLES, AUDIO, MORE, REPEAT, PLAYLIST, SPEED, LOCK, SNAPSHOT }

/** Stateless page surface: host owns fullscreen mode, insets and commands; frame has its own aspect. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerSurface(
    snapshot: PlaybackSnapshot,
    fullscreen: Boolean,
    onCommand: (PlaybackCommand) -> Unit,
    onBack: () -> Unit,
    onFullscreen: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onFeature: (PlayerFeature) -> Unit,
    interaction: PlayerInteractionState,
    interactions: PlayerInteractionActions,
    modifier: Modifier = Modifier,
    frameContent: @Composable BoxScope.() -> Unit,
    overlayContent: @Composable BoxScope.() -> Unit = {},
    repeatMode: RepeatMode = RepeatMode.ALL,
    tools: PlayerToolsState = PlayerToolsState(),
    hideControls: Boolean = false,
    buffering: State<PlaybackBuffer>? = null,
    timeline: State<PlaybackSnapshot>? = null,
    liveInteraction: State<PlayerInteractionState>? = null,
    activePanel: CorePanel? = null,
    onPanel: (CorePanel) -> Unit = {},
    onSpeed: (Float) -> Unit = {},
    onPip: () -> Unit = {},
    pipAvailable: Boolean = false,
    fullscreenStatus: @Composable BoxScope.() -> Unit = {},
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    navigationContent: @Composable BoxScope.() -> Unit = {},
    diagnosticsContent: @Composable BoxScope.() -> Unit = {},
    rightHanded: Boolean = true,
) {
    val music = snapshot.media?.kind == MediaKind.AUDIO
    var oneHanded by rememberSaveable(snapshot.sessionId, fullscreen) { mutableStateOf(false) }
    val arcSeek = remember { ArcSeekHandle() }
    Box(modifier.background(Color.Black).clipToBounds().testTag("player-frame")) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ratio = when (tools.fit) { ScreenFit.WIDE -> 16f / 9f; ScreenFit.CLASSIC -> 4f / 3f; else -> snapshot.media?.aspectRatio ?: (16f / 9f) }
            val mediaSize = if (!snapshot.simulated || music || tools.fit == ScreenFit.STRETCH) Modifier.fillMaxSize()
                else if (tools.fit == ScreenFit.FILL) {
                    if (maxWidth / maxHeight > ratio) Modifier.width(maxWidth).height(maxWidth / ratio)
                    else Modifier.height(maxHeight).width(maxHeight * ratio)
                }
                else if (maxWidth / maxHeight > ratio) Modifier.height(maxHeight).width(maxHeight * ratio)
                else Modifier.width(maxWidth).height(maxWidth / ratio)
            // A real SurfaceView stays in its independent layer. MPV owns video transforms.
            Box(mediaSize.then(if (snapshot.simulated) Modifier.graphicsLayer {
                scaleX = (liveInteraction?.value ?: interaction).scale * if (tools.flipHorizontal) -1 else 1
                scaleY = (liveInteraction?.value ?: interaction).scale * if (tools.flipVertical) -1 else 1
            } else Modifier).testTag("media-content")) { frameContent() }
        }
        if (!hideControls) {
        val gestureLabel = stringResource(R.string.player_gestures)
        val toggleLabel = stringResource(R.string.toggle_controls)
        val playbackLabel = stringResource(R.string.toggle_playback)
        val identity = InteractionContext(snapshot.sessionId, interaction.revision)
        // The system can deliver the edge down/up while revealing transient bars.
        // Keep those touches out of playback gestures even while the bars are hidden.
        Box(Modifier.fillMaxSize().then(if (fullscreen) Modifier.windowInsetsPadding(
            WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)) else Modifier)
            .testTag("gesture-area").semantics {
            contentDescription = gestureLabel
            onClick(toggleLabel) { interactions.tap(identity); true }
            if (!interaction.locked) customActions = listOf(CustomAccessibilityAction(playbackLabel) { interactions.tap(identity, true); true })
        }.then(touchModifier(snapshot, interaction, interactions, fullscreen, timeline = timeline, liveInteraction = liveInteraction, arcSeek = arcSeek)))
        if (fullscreen) HillsControlsScrim(interaction, activePanel)
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val stableInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
        val edge = if (fullscreen && !interaction.options.ignoreSafeArea) with(density) {
            maxOf(stableInsets.getLeft(this, direction), stableInsets.getRight(this, direction)).toDp().coerceAtLeast(48.dp)
        } else 0.dp
        Box(Modifier.fillMaxSize().then(if (fullscreen) Modifier
            .then(if (interaction.options.ignoreSafeArea) Modifier else
                Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Vertical)))
            .padding(horizontal = edge) else Modifier)
            .testTag("control-safe-area")) {
            if (fullscreen) {
                HillsFullscreen(snapshot, interaction, interactions, tools, activePanel, onCommand, onBack,
                    onPrevious, onNext, hasPrevious, hasNext, onFeature, onPanel, onSpeed, onPip, pipAvailable,
                    fullscreenStatus, buffering, timeline, liveInteraction, previousEnabled, nextEnabled,
                    rightHanded, oneHanded, { oneHanded = it; interactions.controlsUsed(identity) }, arcSeek, onFullscreen)
            } else {
                if (interaction.controlsVisible && !interaction.locked) {
                    Row(Modifier.fillMaxWidth().height(90.dp)
                        .background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent)))
                        .padding(top = 4.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                        ControlIcon(R.drawable.icon_video_back, stringResource(R.string.back), "player-back", onBack, mirror = true)
                        Spacer(Modifier.weight(1f))
                        if (!music) ControlIcon(R.drawable.icon_video_dlna, stringResource(R.string.cast), "cast", { onFeature(PlayerFeature.CAST) })
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))) {
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlayPause(snapshot, onCommand, false)
                            SeekControl(snapshot, interaction, interactions, false, Modifier.weight(1f).height(48.dp), buffering, timeline, liveInteraction)
                            FullscreenControl(false, onFullscreen)
                        }
                    }
                }
                if (interaction.controlsVisible && interaction.locked) {
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                        ControlIcon(R.drawable.icon_lock_on, stringResource(R.string.unlock), "unlock", { onFeature(PlayerFeature.LOCK) })
                    }
                }
            }
            overlayContent()
        }
        InteractionFeedback(snapshot, interaction, interactions, fullscreen, liveInteraction)
        }
        navigationContent()
        diagnosticsContent()
    }
}

@Composable
private fun FullscreenControl(landscape: Boolean, onClick: () -> Unit) {
    ControlIcon(if (landscape) R.drawable.full_screen_p else R.drawable.full_screen_l,
        stringResource(if (landscape) R.string.exit_fullscreen else R.string.enter_fullscreen), "fullscreen", onClick,
        imageSize = if (landscape) 24.dp else 16.dp)
}

@Composable
private fun PlayPause(snapshot: PlaybackSnapshot, onCommand: (PlaybackCommand) -> Unit, landscape: Boolean) {
    val playing = snapshot.status == PlaybackStatus.PLAYING
    val label = stringResource(if (playing) R.string.pause else if (snapshot.status == PlaybackStatus.ENDED) R.string.replay else R.string.play)
    val icon = if (landscape) { if (playing) R.drawable.icon_pause else R.drawable.icon_play }
        else { if (playing) R.drawable.icon_pause_portrait else R.drawable.icon_play_portrait }
    ControlIcon(icon, label, "play-pause", {
        onCommand(if (playing) PlaybackCommand.Pause(snapshot.sessionId) else PlaybackCommand.Play(snapshot.sessionId))
    }, enabled = snapshot.media != null, imageSize = if (landscape) 48.dp else 16.dp)
}

@Composable
private fun ControlIcon(icon: Int, label: String, tag: String, onClick: () -> Unit,
    enabled: Boolean = true, imageSize: Dp = 24.dp, mirror: Boolean = false, badge: String? = null) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label; role = Role.Button }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(icon), null, Modifier.size(imageSize).graphicsLayer {
            alpha = if (enabled) 1f else 0.35f
            scaleX = if (mirror && rtl) -1f else 1f
        })
        badge?.let { Text(it, color = Color.White.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 10.sp) }
    }
}

@Composable
internal fun SeekControl(snapshot: PlaybackSnapshot, ui: PlayerInteractionState, actions: PlayerInteractionActions,
    landscape: Boolean, modifier: Modifier, buffering: State<PlaybackBuffer>?, timeline: State<PlaybackSnapshot>?,
    liveInteraction: State<PlayerInteractionState>?, hills: Boolean = false) {
    val duration = snapshot.durationMs ?: 0
    val enabled = snapshot.canSeek
    val position = remember(snapshot, ui, timeline, liveInteraction) { derivedStateOf {
        val current = timeline?.value ?: snapshot
        (liveInteraction?.value ?: ui).previewMs ?: current.pendingSeek?.targetMs ?: current.positionMs
    } }
    val identity = InteractionContext(snapshot.sessionId, ui.revision)
    val progressLabel = stringResource(R.string.progress)
    val progressColor = if (hills) HillsInk else PlayerColors.Accent
    // Media time and seek direction stay physically left-to-right in RTL interfaces.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PositionLabel(position, snapshot.durationMs)
        if (hills) Text(stringResource(R.string.hills_duration, formatTime(snapshot.durationMs)), color = HillsInk,
            style = timelineTextStyle(), maxLines = 1, softWrap = false, modifier = Modifier.testTag("duration"))
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp).graphicsLayer().testTag("seek")
            .semantics {
                contentDescription = progressLabel
                progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) position.value.toFloat() / duration else 0f, 0f..1f)
                if (!enabled) disabled()
                if (enabled) setProgress { value ->
                    if (!value.isFinite()) false else actions.begin(identity, GestureKind.SEEK)?.let {
                        actions.change(it, value.toDouble().coerceIn(0.0, 1.0) * duration)
                        actions.finish(it, true)
                        true
                    } ?: false
                }
            }
            .then(touchModifier(snapshot, ui, actions, landscape, seekBar = true, timeline = timeline, liveInteraction = liveInteraction))) {
            val y = size.height / 2
            val ratio = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawLine(PlayerColors.Track, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            // Read only in drawing: a buffer emission invalidates this layer, not composition/layout.
            // Use the actual sampled interval; dragging the thumb must not move the buffered data.
            val buffered = buffering?.value
            if (duration > 0 && snapshot.pendingSeek == null && buffered?.sessionId == snapshot.sessionId) {
                val start = buffered.startMs.coerceIn(0, duration)
                val end = buffered.endMs.coerceIn(start, duration)
                if (end > start) drawLine(Color(0xB3FFFFFF),
                    Offset(size.width * (start.toDouble() / duration).toFloat(), y),
                    Offset(size.width * (end.toDouble() / duration).toFloat(), y), 4.dp.toPx(), StrokeCap.Round)
            }
            drawLine(progressColor, Offset(0f, y), Offset(size.width * ratio, y), 4.dp.toPx(), StrokeCap.Round)
            if (enabled) drawCircle(if (hills) HillsInk else Color.White, (if (hills) 8.dp else 5.dp).toPx(), Offset(size.width * ratio, y))
        }
        if (!hills) Text(formatTime(snapshot.durationMs), color = Color.White, style = timelineTextStyle(),
            maxLines = 1, softWrap = false, modifier = Modifier.testTag("duration"))
    }
    }
}

/** Formatting changes once per displayed second; progress is read in draw/semantics. */
@Composable
private fun PositionLabel(position: State<Long>, durationMs: Long?) {
    val style = timelineTextStyle()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val seconds by remember(position) { derivedStateOf { position.value / 1000 } }
    // Reserve the entire media's time format before playback reaches a minute/hour boundary.
    // Measure all digits as a fallback for user fonts without tabular-number support.
    // Unknown-duration streams reserve two hour digits, expanding only beyond 99 hours.
    val durationTemplate = remember(durationMs) { durationMs?.let(::formatTime)?.map { if (it.isDigit()) '0' else it }?.joinToString("") }
    val template = durationTemplate ?: "${"0".repeat((seconds / 3600).toString().length.coerceAtLeast(2))}:00:00"
    val slotWidth = remember(template, style, measurer) {
        ('0'..'9').maxOf { digit ->
            measurer.measure(template.map { if (it.isDigit()) digit else it }.joinToString(""),
                style = style, maxLines = 1, softWrap = false).size.width
        }
    }
    val density = LocalDensity.current
    Text(formatTime(seconds * 1000), color = Color.White, style = style, textAlign = TextAlign.End,
        maxLines = 1, softWrap = false, modifier = Modifier
            .width(with(density) { slotWidth.toDp() })
            .graphicsLayer().testTag("position"))
}

@Composable
private fun timelineTextStyle() = LocalTextStyle.current.copy(
    fontSize = 12.sp, fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr,
)

/** Continuous progress stays in timeline consumers, outside the page boundary. */
@Composable
fun rememberPlaybackChrome(timeline: State<PlaybackSnapshot>): State<PlaybackSnapshot> =
    remember(timeline) { derivedStateOf { timeline.value.copy(positionMs = 0, audioGainDb = 0f) } }

@Composable
fun rememberInteractionChrome(live: State<PlayerInteractionState>): State<PlayerInteractionState> =
    remember(live) { derivedStateOf { live.value.copy(previewMs = null, brightness = .5f, volume = .5f, temporarySpeed = null,
        scale = if (live.value.scale == 1f) 1f else 2f) } }

@Composable
fun StaticDemoFrame(snapshot: PlaybackSnapshot, landscape: Boolean = false, modifier: Modifier = Modifier) {
    val music = snapshot.media?.kind == MediaKind.AUDIO
    val statusCard = snapshot.status in listOf(PlaybackStatus.BUFFERING, PlaybackStatus.ERROR, PlaybackStatus.ENDED)
    Box(modifier.fillMaxSize().background(if (music) Color.Black else Color(0xFF111722)), contentAlignment = Alignment.Center) {
        if (music) Image(painterResource(R.drawable.icon_music_bg), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (!statusCard) Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 52.dp).offset(y = if (!music && !landscape) (-24).dp else 0.dp)) {
            if (!music) Image(painterResource(R.drawable.icon_cover_placeholder), null, Modifier.size(if (landscape) 46.dp else 32.dp))
            if (music) Text(snapshot.media?.title.orEmpty(), color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (music) Text(stringResource(R.string.music_metadata, stringResource(R.string.sample_album), stringResource(R.string.sample_artist)), color = PlayerColors.Secondary,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The simulation label is UI chrome: picture transforms must not mirror or scale its text. */
@Composable
fun BoxScope.DemoFrameLabel(landscape: Boolean) {
    if (landscape) Text(stringResource(R.string.static_frame), color = PlayerColors.Secondary, fontSize = 12.sp,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp, start = 64.dp, end = 64.dp).testTag("simulation-label"))
}

@Composable
fun DemoSubtitle(snapshot: PlaybackSnapshot, landscape: Boolean, style: SubtitleStyle = SubtitleStyle(), delayMs: Int = 0) {
    val subtitle = subtitleCue(snapshot, delayMs)
    if (snapshot.media?.kind == MediaKind.VIDEO && subtitle != null &&
        snapshot.status !in listOf(PlaybackStatus.BUFFERING, PlaybackStatus.ERROR, PlaybackStatus.ENDED)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.padding(start = 40.dp, end = 40.dp, bottom = (if (landscape) 118 else 58).dp + (style.bottom / 255f * if (landscape) 60 else 30).dp)) {
                StyledSubtitle(subtitle, style, "sample-subtitle")
            }
        }
    }
}
