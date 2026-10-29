package com.stellar.player.mpv

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.stellar.player.contract.*
import com.stellar.player.source.SmbSource
import com.stellar.player.source.NfsSource
import com.stellar.player.source.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.KeyStore
import android.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.coroutines.resume

/** Session-only input. Addresses and authentication must never be copied into diagnostics. */
class MpvSource(val address: String, private val http: HttpSource? = null,
    val verifyTls: Boolean = http?.verifyTlsCertificates ?: true) {
    constructor(http: HttpSource) : this(http.address, http)
    val headers: Map<String, String> get() = http?.headers.orEmpty()
    val userAgent: String get() = http?.userAgent ?: "StellarPlayer/Android"
    override fun toString() = "MpvSource(redacted)"
}

/** All client API calls and destruction run on the host's serialized native worker. */
class MpvPlaybackPort(private val resolve: (String) -> MpvSource?,
    private val contentResolver: ContentResolver? = null,
    private val accurateSeek: () -> Boolean = { true },
    private val bufferSeconds: () -> Pair<Int, Int> = { 3 to 15 },
    sharedWorker: Handler? = null, private val cacheDirectory: File? = null,
    private val resolveSmb: (String) -> SmbSource? = { null },
    private val resolveNfs: (String) -> NfsSource? = { null },
    private val forceSdr: () -> Boolean = { false },
    private val prepareDiskCache: (MpvSource) -> File? = { null },
    private val subtitleAppearance: () -> Map<SubtitleAppearanceOption, String> = { emptyMap() },
    context: android.content.Context? = null,
    private val resolveStream: (String) -> com.stellar.player.source.StreamSource? = { null }) : PlaybackPort, AutoCloseable {
    private val applicationContext = context?.applicationContext
    @Volatile var audioPassthroughActive = false; private set
    private val thread = if (sharedWorker == null) HandlerThread("stellar-mpv").apply { start() } else null
    private val worker = sharedWorker ?: Handler(checkNotNull(thread).looper)
    private var pollTask: Runnable? = null
    /** IO samples are only needed by explicit diagnostics consumers. */
    @Volatile var diagnosticsEnabled = false
    private val mutable = MutableStateFlow(PlaybackSnapshot(simulated = false))
    override val snapshots = mutable.asStateFlow()
    private val buffer = MutableStateFlow(PlaybackBuffer())
    val buffering = buffer.asStateFlow()
    @Volatile var diagnosticSamples = 0L; private set
    /** Deliberately separate from the 100 ms control poll and expensive renderer evidence. */
    suspend fun sampleDiagnostics(session: Long): PlaybackDiagnostics? = suspendCancellableCoroutine { continuation ->
        val task = Runnable {
            if (!continuation.isActive) return@Runnable
            val result = if (!disposed && current(session) && handle != 0L && ready) try {
                diagnosticSamples++
                val params = MpvNative.read(handle, "video-params")?.takeUnless { it == "null" }?.let(::JSONObject) ?: JSONObject()
                val io = JSONObject(MpvNative.remoteDiagnostics(handle))
                fun number(name: String) = readString(name).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                val videoRate = number("video-bitrate")
                val audioRate = number("audio-bitrate")
                // Selected audio/video only. Do not report a partial sum as the total bitrate.
                val rate = if ((snapshots.value.media?.kind != MediaKind.VIDEO || videoRate != null) &&
                    (snapshots.value.selectedAudioId == null || audioRate != null))
                    ((videoRate ?: 0.0) + (audioRate ?: 0.0)).toLong().takeIf { it > 0 } else null
                val bytes = when {
                    io.has("networkReadBytes") -> io.optLong("networkReadBytes").takeIf { it >= 0 }
                    io.has("bytes") -> io.optLong("bytes").takeIf { it >= 0 }
                    else -> null
                }
                val hw = readString("hwdec-current")
                val color = listOf("primaries", "gamma", "colormatrix").map { params.optString(it) }
                    .takeIf { parts -> parts.all { it.isNotBlank() && it != "unknown" && it != "auto" } }?.joinToString(" / ")
                PlaybackDiagnostics(session, backend = "MPV", videoCodec = readString("video-format").takeIf { it.isNotBlank() },
                    width = params.optInt("w").takeIf { it > 0 }, height = params.optInt("h").takeIf { it > 0 },
                    sourceFps = number("container-fps")?.takeIf { it > 0 }, bitrate = rate, color = color,
                    fileBytes = io.optLong("size", -1).takeIf { it >= 0 } ?: number("file-size")?.toLong(),
                    hardware = hw.takeIf { it.isNotBlank() && it != "null" }?.let { it != "no" },
                    bufferedMs = number("demuxer-cache-duration")?.let { (it * 1000).toLong() }, networkBytes = bytes,
                    // Without our byte counter, only a directly network-backed, uncached input is usable.
                    networkBytesPerSecond = if (bytes == null && !appliedDiskCache && readString("demuxer-via-network") == "true")
                        number("cache-speed") else null)
            } catch (_: Exception) { null } else null
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation { worker.removeCallbacks(task) }
        if (!worker.post(task) && continuation.isActive) continuation.resume(null)
    }
    @Volatile private var disposed = false
    @Volatile var activeEngines = 0; private set
    @Volatile var appliedDiskCache = false; private set
    @Volatile var runtimeVersion = ""; private set
    @Volatile var hardwareDecoder = ""; private set
    @Volatile var videoOutput = ""; private set
    @Volatile var gpuContext = ""; private set
    @Volatile var videoPixelFormat = ""; private set
    @Volatile var videoColorParameters = "{}"; private set
    @Volatile var videoTargetParameters = "{}"; private set
    @Volatile var appliedAudioGain = 1f; private set
    // Remaining fields are native-worker confined, except command IDs guarded by dispatch.
    private var handle = 0L
    @Volatile private var remoteToken: String? = null
    @Volatile var remoteDiagnostics = "{}"; private set
    private fun cancelRemote() { remoteToken?.let { MpvNative.cancelRemote(it) } }
    private var descriptor: ParcelFileDescriptor? = null
    private class SurfaceBinding(val owner: Any, val surface: Surface, @Volatile var size: String) {
        // Only this short state transition is locked; native work never holds the monitor.
        var released = false
        var detached = CountDownLatch(0)
    }
    @Volatile private var surfaceBinding: SurfaceBinding? = null
    private var renderingSurface: SurfaceBinding? = null
    @Volatile private var discDurationMs: Long? = null
    private var ready = false
    private var wantsPlay = false
    private var trustBundle: File? = null
    private var sessionRestore: PlaybackRestore? = null
    private var sessionLanguage: String? = null
    private var nativeTrackIds: Map<String, String> = emptyMap()
    private var publicTrackIds: Map<Pair<String, Long>, String> = emptyMap()
    private var externalSubtitle: ExternalSubtitle? = null
    private var externalTrackId: Long? = null
    private class SubtitleLoad(val id: Long, val session: Long, val subtitle: ExternalSubtitle,
        val select: Boolean, val active: () -> Boolean, val complete: (Boolean) -> Unit)
    private var subtitleRequestId = 0L
    private var pendingSubtitle: SubtitleLoad? = null
    private var commandId = 0L
    private var seekIssuedAt = 0L
    private var issuedSeekId: Long? = null
    private var seekStarted = false
    private var issuedFrameId: Long? = null
    private var frameIssuedAt = 0L
    private var frameStartPts = 0.0
    private var frameSeekStarted = false
    private var frameUsesSeek = false
    private var videoFrameRate = 0.0
    private var playbackStarted = false
    private var fit = 0
    private var scaleX = 1f
    private var scaleY = 1f
    private var lastBufferAt = 0L
    @Volatile var appliedSubtitleAppearance: Map<String, String> = emptyMap(); private set
    @Volatile var appliedEqualizerGraph: String = ""; private set
    private var appliedEqualizer: EqualizerSettings? = null
    private var equalizerRate = 0
    private var equalizerFailed = false
    private var audioSwitchPending = false
    private var selectedClosedCaption = false

    fun updateSubtitleAppearance() {
        val session = snapshots.value.sessionId
        execute(session) { applySubtitleAppearance() }
    }

    private fun applySubtitleAppearance() {
        val desired = mpvSubtitleAppearance(subtitleAppearance(), selectedClosedCaption)
        val applied = appliedSubtitleAppearance.toMutableMap()
        for ((name, value) in desired) if (applied[name] != value && set(name, value)) applied[name] = value
        appliedSubtitleAppearance = applied
    }

    private fun applyEqualizer(session: Long, rate: Int) {
        val settings = snapshots.value.equalizer
        if (audioPassthroughActive || equalizerFailed || rate <= 0 || (settings == appliedEqualizer && rate == equalizerRate)) return
        val graph = MpvNative.equalizer(handle, settings.enabled, settings.preampDb, settings.bandsDb.toFloatArray(), rate)
        if (graph == null) {
            equalizerFailed = true
            update(session) { it.copy(media = it.media?.copy(capabilities = it.capabilities.copy(canEqualize = false))) }
        } else {
            appliedEqualizer = settings; equalizerRate = rate; appliedEqualizerGraph = graph
        }
    }



    /** On-demand evidence only; never read native properties from the UI thread. */
    suspend fun subtitleAudioDiagnostics(): String = suspendCancellableCoroutine { result ->
        worker.post {
            val values = JSONObject()
            if (handle != 0L) for (name in appliedSubtitleAppearance.keys + listOf("sid", "secondary-sid", "sub-text", "secondary-sub-text", "track-list", "audio-params", "audio-out-params", "current-ao", "current-tracks/audio/decoder", "audio-spdif", "ad", "af", "time-pos", "video-params", "avsync"))
                values.put(name, org.json.JSONTokener(MpvNative.read(handle, name) ?: "null").nextValue())
            if (result.isActive) result.resume(values.toString())
        }
    }

    private fun capabilities(ready: Boolean = false) = Capabilities(
        maxSpeed = 8f,
        canSeek = ready && mutable.value.media?.discNavigation != true,
        canSetSpeed = ready && !audioPassthroughActive && mutable.value.media?.discNavigation != true, canSelectSubtitle = ready, canSelectAudio = ready,
        canStyleSubtitle = false, canDelaySubtitle = ready, canImportSubtitle = ready,
        canAdjustDevice = ready, canSetAudioGain = ready && !audioPassthroughActive, canEqualize = false,
        canStepFrame = ready && videoFrameRate > 0 && mutable.value.media?.discNavigation != true, canSelectVideo = ready)

    @Synchronized override fun dispatch(command: PlaybackCommand): CommandResult {
        if (disposed) return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        if (command is PlaybackCommand.Open) {
            cancelRemote()
            val session = PlaybackSessions.nextReal()
            mutable.value = PlaybackSnapshot(sessionId = session, simulated = false, status = PlaybackStatus.BUFFERING,
                equalizer = command.equalizer,
                media = command.media.copy(durationMs = null, subtitleTracks = emptyList(), audioTracks = emptyList(),
                    videoTracks = emptyList(), capabilities = capabilities()),
                speed = command.restore?.speed?.coerceIn(0.5f, 8f) ?: 1f,
                subtitleDelayMs = command.restore?.subtitleDelayMs ?: 0,
                externalSubtitle = command.restore?.externalSubtitle)
            buffer.value = PlaybackBuffer(session)
            val smb = resolveSmb(command.media.id)
            val nfs = resolveNfs(command.media.id)
            val stream = resolveStream(command.media.id)
            val source = resolve(command.media.id) ?: if (smb != null || nfs != null || stream != null) MpvSource("") else null
            val cache = bufferSeconds()
            worker.post {
                releaseEngine()
                if (!current(session)) return@post
                try {
                    if (source == null) { fail(session, PlaybackError.SOURCE_UNAVAILABLE); return@post }
                    ready = false; wantsPlay = false; seekStarted = false
                    appliedSubtitleAppearance = emptyMap(); selectedClosedCaption = false
                    appliedEqualizer = null; equalizerRate = 0; equalizerFailed = false; appliedEqualizerGraph = ""
                    audioSwitchPending = false
                    videoColorParameters = "{}"
                    videoTargetParameters = "{}"
                    discDurationMs = command.media.durationMs.takeIf { command.media.id.startsWith("dvd-session:") || command.media.id.startsWith("bluray-session:") }
                    sessionRestore = command.restore; sessionLanguage = command.subtitleLanguage
                    nativeTrackIds = emptyMap(); publicTrackIds = emptyMap()
                    var address = source.address
                    if (address.startsWith("content:")) {
                        val input = checkNotNull(contentResolver).openFileDescriptor(Uri.parse(address), "r")
                        if (input == null) { fail(session, PlaybackError.SOURCE_UNAVAILABLE); return@post }
                        descriptor = input
                        address = "fd://${input.fd}"
                    }
                    val options = mutableListOf("config", "no", "terminal", "no", "input-default-bindings", "no",
                        "input-vo-keyboard", "no", "osd-level", "0", "idle", "yes", "keep-open", "yes",
                        "vo", "null", "gpu-api", "opengl",
                        "gpu-context", "android",
                        "hwdec", "mediacodec-copy",
                        // Prefer OpenSL ES for stable pause/seek timing; retain AAudio as fallback.
                        "ao", "opensles,aaudio", "pause", "yes", "volume", "100", "cache", "yes", "cache-on-disk", "no",
                        "cache-pause-wait", cache.first.toString(), "demuxer-readahead-secs", cache.second.toString(),
                        "cache-secs", cache.second.toString(),
                        // Retain keyframes needed when a Surface rebind restarts video decoding.
                        "demuxer-max-bytes", "67108864", "demuxer-max-back-bytes", "33554432",
                        "tls-verify", if (source.verifyTls) "yes" else "no",
                        "user-agent", source.userAgent,
                        "sub-auto", "no", "secondary-sid", "no", "sub-fonts-dir", "/system/fonts", "sub-font", "sans-serif",
                        "speed", snapshots.value.speed.toString(), "sub-delay", (snapshots.value.subtitleDelayMs / 1000.0).toString())
                    if (forceSdr()) options += listOf("target-trc", "srgb", "target-prim", "bt.709")
                    if (source.verifyTls && source.address.startsWith("http")) {
                        options += listOf("tls-ca-file", systemTrustBundle().absolutePath)
                    }
                    if (command.media.discNavigation) options += listOf("cache", "no", "cache-pause", "no",
                        "demuxer-readahead-secs", "0.5", "demuxer-lavf-probesize", "65536", "demuxer-lavf-analyzeduration", "0.5")
                    if (address.startsWith("stellardvd:")) {
                        val palette = MpvNative.discPalette(address)
                        if (palette.isNotEmpty()) options += listOf("sub-lavc-o", "palette=%${palette.length}%$palette")
                    }
                    handle = MpvNative.create(null, options.toTypedArray())
                    check(handle != 0L)
                    applySubtitleAppearance()
                    surfaceBinding?.let(::attachRendering)
                    activeEngines = 1
                    remoteDiagnostics = "{}"
                    if (address.startsWith("http://") || address.startsWith("https://")) {
                        val directory = try { prepareDiskCache(source) } catch (error: Exception) {
                            Log.w("StellarMPV", "Disk cache unavailable: ${error.javaClass.simpleName}")
                            null
                        }
                        if (directory != null) {
                            val token = "http-cache:${java.util.UUID.randomUUID()}"
                            check(MpvNative.configureHttpCache(handle, token, address, directory.absolutePath,
                                source.headers.entries.joinToString("") { (key, value) -> "$key: $value\r\n" },
                                source.userAgent, source.verifyTls,
                                if (source.verifyTls) systemTrustBundle().absolutePath else ""))
                            remoteToken = token
                            appliedDiskCache = true
                        }
                    }
                    if (smb != null) {
                        val token = "smb://${java.util.UUID.randomUUID()}"
                        val password = smb.password.toByteArray(Charsets.UTF_8)
                        try {
                            MpvNative.configureSmb(handle, token, smb.server.toByteArray(Charsets.UTF_8),
                                smb.share.toByteArray(Charsets.UTF_8), smb.path.toByteArray(Charsets.UTF_8),
                                smb.user.toByteArray(Charsets.UTF_8), password, smb.domain.toByteArray(Charsets.UTF_8), smb.requireEncryption)
                        } finally { password.fill(0) }
                        remoteToken = token; address = token
                    } else if (nfs != null) {
                        val token = "nfs://${java.util.UUID.randomUUID()}"
                        MpvNative.configureNfs(handle, token, nfs.server.toByteArray(Charsets.UTF_8),
                            nfs.export.toByteArray(Charsets.UTF_8), nfs.path.toByteArray(Charsets.UTF_8),
                            nfs.version.number, nfs.uid ?: -1, nfs.gid ?: -1, nfs.port)
                        remoteToken = token; address = token
                    }
                    if (stream != null) {
                        val token = "stellarcloud://${java.util.UUID.randomUUID()}"
                        val directory = try { prepareDiskCache(MpvSource("stellarcloud:${command.media.id}")) }
                        catch (error: Exception) {
                            Log.w("StellarMPV", "Disk cache unavailable: ${error.javaClass.simpleName}"); null
                        }
                        check(MpvNative.configureStream(handle, token, stream, directory?.absolutePath.orEmpty()))
                        appliedDiskCache = directory != null
                        remoteToken = token; address = token
                    }
                    // Close can race registration. Do not start the newly cancelled open.
                    if (!current(session)) { cancelRemote(); releaseEngine(); return@post }
                    if (source.headers.isNotEmpty()) {
                        check(MpvNative.headers(handle, source.headers.entries.map { (key, value) -> "$key: $value" }.toTypedArray()) >= 0)
                    }
                    set("sid", "no") // Apply the shared language/restore policy after real tracks arrive.
                    val start = command.restore?.positionMs?.coerceAtLeast(0) ?: 0
                    set("start", (start / 1000.0).toString())
                    applyTransform()
                    runtimeVersion = readString("mpv-version")
                    val result = MpvNative.command(handle, arrayOf("loadfile", address, "replace"))
                    if (result < 0) {
                        fail(session, mpvPlaybackError(result)); releaseEngine(); return@post
                    }
                    Log.i("StellarMPV", "open session=$session version=$runtimeVersion")
                    poll(session)
                } catch (error: Exception) {
                    Log.e("StellarMPV", "Open failed: ${error.javaClass.simpleName}")
                    releaseEngine(); fail(session, mpvOpenError(error))
                } catch (error: LinkageError) {
                    Log.e("StellarMPV", "Native load failed: ${error.javaClass.simpleName}")
                    releaseEngine(); fail(session, mpvOpenError(error))
                }
            }
            return CommandResult.Accepted
        }
        val s = snapshots.value
        if (command !is PlaybackCommand.InSession || command.sessionId != s.sessionId)
            return CommandResult.Rejected(RejectionReason.STALE_SESSION)
        if (command is PlaybackCommand.Close) {
            cancelRemote()
            mutable.value = PlaybackSnapshot(sessionId = PlaybackSessions.nextReal(), simulated = false)
            buffer.value = PlaybackBuffer(mutable.value.sessionId)
            worker.post { releaseEngine() }
            return CommandResult.Accepted
        }
        val media = s.media ?: return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        if (s.status == PlaybackStatus.ERROR) return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        if (s.pendingFrame != null && command !is PlaybackCommand.Pause && command !is PlaybackCommand.SetAudioGain && command !is PlaybackCommand.SetEqualizer)
            return CommandResult.Rejected(RejectionReason.OPERATION_BUSY)
        when (command) {
            is PlaybackCommand.SetHardwareDecoding -> {
                if (!s.capabilities.canSetHardwareDecoding) return CommandResult.Rejected(RejectionReason.INVALID_STATE)
                if (s.pendingSeek != null) return CommandResult.Rejected(RejectionReason.OPERATION_BUSY)
                execute(s.sessionId) { set("hwdec", if (command.enabled) "mediacodec-copy" else "no") }
            }
            is PlaybackCommand.Play -> execute(s.sessionId) {
                wantsPlay = true
                if (s.status == PlaybackStatus.ENDED) MpvNative.command(handle, arrayOf("seek", "0", "absolute+exact"))
                set("pause", "no")
            }
            is PlaybackCommand.Pause -> execute(s.sessionId) { wantsPlay = false; set("pause", "yes") }
            is PlaybackCommand.Seek -> {
                if (!s.canSeek) return CommandResult.Rejected(RejectionReason.SEEK_UNAVAILABLE)
                if (s.pendingSeek != null) return CommandResult.Rejected(RejectionReason.OPERATION_BUSY)
                val pending = PendingSeek(++commandId, command.positionMs.coerceIn(0, s.durationMs!!))
                mutable.value = s.copy(pendingSeek = pending, seekResult = null, frameResult = null, framePositionMs = null)
                buffer.value = PlaybackBuffer(s.sessionId)
                execute(s.sessionId) {
                    // Drain preceding events before attributing SEEK/PLAYBACK_RESTART to this request.
                    finishSubtitles(JSONObject(MpvNative.poll(handle)))
                    issuedSeekId = pending.id
                    seekIssuedAt = SystemClock.elapsedRealtime(); seekStarted = false
                    if (MpvNative.command(handle, arrayOf("seek", (pending.targetMs / 1000.0).toString(),
                        if (accurateSeek()) "absolute+exact" else "absolute+keyframes")) < 0) finishSeek(s.sessionId, false)
                }
            }
            is PlaybackCommand.SetSpeed -> {
                if (!s.capabilities.canSetSpeed || !command.speed.isFinite() || command.speed !in 0.5f..8f)
                    return CommandResult.Rejected(RejectionReason.SPEED_UNAVAILABLE)
                execute(s.sessionId) { set("speed", command.speed.toString()) }
            }
            is PlaybackCommand.SetAudioGain -> {
                if (!s.capabilities.canSetAudioGain || !command.decibels.isFinite() || command.decibels !in 0f..9f)
                    return CommandResult.Rejected(RejectionReason.INVALID_STATE)
                // This is the requested session gain, consumed synchronously by the gesture HUD.
                mutable.value = s.copy(audioGainDb = command.decibels)
                execute(s.sessionId) {
                    // mpv volume uses a cubic perceptual curve; volume-gain accepts actual decibels.
                    if (set("volume-gain", command.decibels.toString())) {
                        appliedAudioGain = 10.0.pow(readString("volume-gain").toDouble() / 20).toFloat()
                    }
                }
            }
            is PlaybackCommand.SetEqualizer -> {
                if (!s.capabilities.canEqualize || !command.settings.isValid())
                    return CommandResult.Rejected(RejectionReason.INVALID_STATE)
                mutable.value = s.copy(equalizer = command.settings.copy(bandsDb = command.settings.bandsDb.toList()))
                execute(s.sessionId) { if (!audioSwitchPending) applyEqualizer(s.sessionId, equalizerRate) }
            }
            is PlaybackCommand.SetSubtitleDelay -> {
                if (!s.capabilities.canDelaySubtitle || command.delayMs !in -60_000..60_000)
                    return CommandResult.Rejected(RejectionReason.INVALID_STATE)
                execute(s.sessionId) { if (set("sub-delay", (command.delayMs / 1000.0).toString()))
                    update(s.sessionId) { it.copy(subtitleDelayMs = command.delayMs) } }
            }
            is PlaybackCommand.SelectTrack -> {
                val tracks = when (command.kind) { TrackKind.AUDIO -> media.audioTracks
                    TrackKind.SUBTITLE -> media.subtitleTracks; TrackKind.VIDEO -> media.videoTracks }
                if (!ready || (command.trackId == null && command.kind != TrackKind.SUBTITLE) ||
                    (command.kind == TrackKind.SUBTITLE && command.trackId != null && command.trackId == s.selectedSecondarySubtitleId) ||
                    (command.trackId != null && tracks.none { it.id == command.trackId }))
                    return CommandResult.Rejected(RejectionReason.TRACK_UNAVAILABLE)
                val property = when (command.kind) { TrackKind.AUDIO -> "aid"; TrackKind.SUBTITLE -> "sid"; TrackKind.VIDEO -> "vid" }
                execute(s.sessionId) {
                    val id = nativeTrackIds[command.trackId] ?: "no"
                    if (command.kind == TrackKind.AUDIO) selectAudio(id)
                    else if (command.kind == TrackKind.SUBTITLE) selectSubtitle(id, secondary = false)
                    else set(property, id)
                }
            }
            is PlaybackCommand.SelectSecondarySubtitle -> {
                if (!s.capabilities.canSelectSecondarySubtitle ||
                    (command.trackId != null && (command.trackId == s.selectedSubtitleId ||
                        media.subtitleTracks.none { it.id == command.trackId })))
                    return CommandResult.Rejected(RejectionReason.TRACK_UNAVAILABLE)
                execute(s.sessionId) { selectSubtitle(nativeTrackIds[command.trackId] ?: "no", secondary = true) }
            }
            is PlaybackCommand.StepFrame -> {
                if (!s.canStep(command.direction)) return CommandResult.Rejected(RejectionReason.FRAME_UNAVAILABLE)
                val pending = PendingFrameStep(++commandId, command.direction)
                mutable.value = s.copy(pendingFrame = pending, frameResult = null, seekResult = null)
                buffer.value = PlaybackBuffer(s.sessionId)
                execute(s.sessionId) {
                    // Retire old seek/restart events before issuing this single frame operation.
                    finishSubtitles(JSONObject(MpvNative.poll(handle)))
                    issuedFrameId = pending.id
                    frameStartPts = readString("time-pos").toDoubleOrNull() ?: Double.NaN
                    frameIssuedAt = SystemClock.elapsedRealtime(); frameSeekStarted = false
                    // At the known last frame, audio can still advance time-pos without a new video frame.
                    val lastFrame = s.durationMs?.let { it / 1000.0 - 1.0 / videoFrameRate }
                    if (pending.direction > 0 && lastFrame != null && frameStartPts >= lastFrame - 0.001) {
                        finishFrame(s.sessionId, null, FrameStepFailure.BOUNDARY)
                        return@execute
                    }
                    // Playing into EOF switches mpv's clock to audio. Seek the final video frame
                    // with its native frame command so a subsequent backward step retains the PTS.
                    frameUsesSeek = pending.direction < 0 ||
                        (lastFrame != null && frameStartPts >= lastFrame - 1.0 / videoFrameRate - 0.001)
                    val args = if (pending.direction < 0) arrayOf("frame-back-step")
                        else arrayOf("frame-step", "1", if (frameUsesSeek) "seek" else "mute")
                    if (!ready || wantsPlay || !frameStartPts.isFinite() || MpvNative.command(handle, args) < 0)
                        finishFrame(s.sessionId, null, FrameStepFailure.FAILED)
                }
            }
            is PlaybackCommand.LoadSubtitle -> {
                if (!s.capabilities.canImportSubtitle) return CommandResult.Rejected(RejectionReason.TRACK_UNAVAILABLE)
                execute(s.sessionId) { startSubtitle(s.sessionId, command.subtitle) }
            }
            else -> return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        }
        return CommandResult.Accepted
    }

    private fun current(session: Long) = !disposed && snapshots.value.sessionId == session
    private fun systemTrustBundle(): File {
        trustBundle?.takeIf { it.isFile }?.let { return it }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val roots = factory.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
        val file = File(checkNotNull(cacheDirectory), "mpv-system-ca.pem")
        file.bufferedWriter().use { writer ->
            for (certificate in roots) {
                writer.appendLine("-----BEGIN CERTIFICATE-----")
                writer.appendLine(Base64.encodeToString(certificate.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                writer.appendLine("-----END CERTIFICATE-----")
            }
        }
        trustBundle = file
        return file
    }
    private fun execute(session: Long, action: () -> Unit) { worker.post {
        if (current(session) && handle != 0L) { action(); schedulePoll(session, 0) }
    } }
    private fun schedulePoll(session: Long, delayMs: Long) {
        pollTask?.let(worker::removeCallbacks)
        pollTask = Runnable { poll(session) }.also { worker.postDelayed(it, delayMs) }
    }
    @Synchronized private fun update(session: Long, action: (PlaybackSnapshot) -> PlaybackSnapshot) {
        if (current(session)) mutable.value = action(snapshots.value)
    }
    private fun fail(session: Long, error: PlaybackError) = update(session) { it.copy(status = PlaybackStatus.ERROR,
        error = error, hardwareDecoding = false, pendingSeek = null, pendingFrame = null, media = it.media?.copy(capabilities = capabilities())) }
    private fun set(name: String, value: String) = MpvNative.set(handle, name, value) >= 0
    private fun readString(name: String): String = MpvNative.read(handle, name)?.let { raw ->
        if (raw == "null") "" else JSONArray("[$raw]").optString(0) }.orEmpty()
    private fun trackId(type: String, id: Long) = publicTrackIds[type to id]

    /** The host waits for native validation before reporting import success or saving the selection. */
    suspend fun loadExternalSubtitle(session: Long, subtitle: ExternalSubtitle): Boolean = suspendCancellableCoroutine { result ->
        worker.post {
            startSubtitle(session, subtitle, active = { result.isActive }) { result.resume(it) }
            if (current(session) && handle != 0L) schedulePoll(session, 0)
        }
        result.invokeOnCancellation { worker.post {
            pendingSubtitle?.takeIf { !it.active() }?.let { MpvNative.abortCommand(handle, it.id) }
        } }
    }

    private fun readTracks(): List<JSONObject> {
        val tracks = JSONArray(MpvNative.read(handle, "track-list")?.takeIf { it.startsWith("[") } ?: "[]")
        return (0 until tracks.length()).map { tracks.getJSONObject(it) }
    }

    private fun startSubtitle(session: Long, subtitle: ExternalSubtitle, select: Boolean = true,
        active: () -> Boolean = { true }, complete: (Boolean) -> Unit = {}) {
        if (!current(session) || handle == 0L || !ready || !active() || pendingSubtitle != null || !File(subtitle.path).isFile) {
            complete(false); return
        }
        val previous = externalTrackId
        if (externalSubtitle?.path == subtitle.path && previous != null) {
            val loaded = !select || readString("secondary-sid") == previous.toString() || selectSubtitle(previous.toString(), secondary = false)
            if (loaded) { externalSubtitle = subtitle; metadata(session) }
            complete(loaded); return
        }
        val request = SubtitleLoad(++subtitleRequestId, session, subtitle, select, active, complete)
        if (MpvNative.addSubtitle(handle, request.id, subtitle.path, subtitle.label) < 0) {
            complete(false); return
        }
        pendingSubtitle = request
    }

    private fun finishSubtitle(id: Long, error: Int) {
        val request = pendingSubtitle?.takeIf { it.id == id } ?: return
        pendingSubtitle = null
        val added = readTracks().filter { it.optString("type") == "sub" && it.optBoolean("external") &&
            it.optString("external-filename") == request.subtitle.path }.map { it.getLong("id") }
        val first = added.firstOrNull()
        val loaded = error >= 0 && first != null && current(request.session) && request.active() &&
            (!request.select || selectSubtitle(first.toString(), secondary = false))
        if (loaded) {
            // Keep one external slot, committing only after the asynchronous parse succeeds.
            added.drop(1).forEach { MpvNative.command(handle, arrayOf("sub-remove", it.toString())) }
            externalTrackId?.let { MpvNative.command(handle, arrayOf("sub-remove", it.toString())) }
            externalTrackId = first
            externalSubtitle = request.subtitle
        } else {
            added.forEach { MpvNative.command(handle, arrayOf("sub-remove", it.toString())) }
        }
        metadata(request.session)
        request.complete(loaded)
    }

    private fun selectSubtitle(id: String, secondary: Boolean): Boolean {
        // Recheck on the serialized worker: a second selection may have been queued
        // before the first one was published. mpv cannot use one track in both slots.
        if (id != "no" && readString(if (secondary) "sid" else "secondary-sid") == id) return false
        return set(if (secondary) "secondary-sid" else "sid", id)
    }

    private fun selectAudio(id: String) {
        if (readString("aid") == id) return
        if (appliedEqualizerGraph.isNotEmpty()) {
            MpvNative.equalizer(handle, false, 0f, FloatArray(10), equalizerRate.coerceAtLeast(1))
            appliedEqualizerGraph = ""
        }
        appliedEqualizer = null
        audioSwitchPending = set("aid", id)
    }

    private fun restoreTracks(session: Long, saved: PlaybackRestore?) {
        if (!current(session) || !ready) return
        val subtitle = if (saved?.restoreSubtitleSelection == true) saved.subtitleId
            else SubtitleLanguageSelector.select(snapshots.value.media?.subtitleTracks.orEmpty()
                .filterNot { it.id == externalSubtitle?.id }, sessionLanguage)
        set("sid", nativeTrackIds[subtitle] ?: "no")
        set("secondary-sid", nativeTrackIds[saved?.secondarySubtitleId?.takeUnless { it == subtitle }] ?: "no")
        nativeTrackIds[saved?.audioId]?.let(::selectAudio)
        nativeTrackIds[saved?.videoId]?.let { set("vid", it) }
        sessionRestore = null
        metadata(session)
    }

    private fun finishSubtitles(data: JSONObject): Boolean {
        val replies = data.getJSONArray("replies")
        for (i in 0 until replies.length()) {
            val reply = replies.getJSONObject(i)
            finishSubtitle(reply.getLong("id"), reply.getInt("error"))
        }
        return replies.length() > 0
    }

    private fun poll(session: Long) {
        if (!current(session) || handle == 0L) return
        val data = JSONObject(MpvNative.poll(handle))
        if (data.optBoolean("audioReconfigured")) audioSwitchPending = false

        if (diagnosticsEnabled && remoteToken != null) remoteDiagnostics = MpvNative.remoteDiagnostics(handle)
        if (data.optInt("error") != 0) {
            val error = mpvPlaybackError(data.getInt("error"), MpvNative.remoteError(handle))
            fail(session, error); releaseEngine(); return
        }
        if (data.optInt("loaded") != 0) {
            ready = true
            set("pause", if (wantsPlay) "no" else "yes")
        }
        val replied = ready && finishSubtitles(data)
        if (ready) {
            if (data.optInt("restart") != 0) playbackStarted = true
            if (data.optInt("loaded") != 0 || (data.optInt("tracksChanged") != 0 && pendingSubtitle == null)) metadata(session)
            if (data.optInt("loaded") != 0) {
                val saved = sessionRestore
                val external = saved?.externalSubtitle
                if (external != null) {
                    startSubtitle(session, external, select = false) { restoreTracks(session, saved) }
                } else restoreTracks(session, saved)
            }
        }
        if (ready) {
            val outputFormat = data.optString("audio-output-format").takeUnless { it == "null" }.orEmpty()
            val passthrough = outputFormat.startsWith("spdif-")
            if (passthrough != audioPassthroughActive) {
                audioPassthroughActive = passthrough
                appliedEqualizer = null
                if (passthrough) {
                    set("af", ""); appliedEqualizerGraph = ""
                    set("speed", "1"); set("volume-gain", "0"); appliedAudioGain = 1f
                } else set("volume-gain", snapshots.value.audioGainDb.toString())
                update(session) { it.copy(media = it.media?.copy(capabilities = it.capabilities.copy(
                    canSetSpeed = !passthrough && it.media?.discNavigation != true, canSetAudioGain = !passthrough,
                    canEqualize = false))) }
            }
            val rate = data.optInt("audio-rate")
            if (rate > 0 && outputFormat.isNotEmpty() && !audioSwitchPending && !audioPassthroughActive) {
                applyEqualizer(session, rate)
                if (!equalizerFailed && !snapshots.value.capabilities.canEqualize)
                    update(session) { it.copy(media = it.media?.copy(capabilities = it.capabilities.copy(canEqualize = true))) }
            } else if (snapshots.value.capabilities.canEqualize) {
                update(session) { it.copy(media = it.media?.copy(capabilities = it.capabilities.copy(canEqualize = false))) }
            }
            val position = (data.optDouble("time-pos", 0.0) * 1000).toLong().coerceAtLeast(0)
            val duration = discDurationMs ?: (data.optDouble("duration", 0.0) * 1000).toLong().takeIf { it > 0 }
            // mpv's time-pos follows the displayed video PTS while paused (get_current_time).
            val pts = data.optDouble("time-pos", Double.NaN)
            val paused = data.optBoolean("pause", !wantsPlay)
            val eof = data.optInt("ended") != 0 || readString("eof-reached") == "true"
            hardwareDecoder = data.optString("hwdec-current", "")
            update(session) { s -> s.copy(positionMs = position, speed = data.optDouble("speed", s.speed.toDouble()).toFloat(),
                hardwareDecoding = hardwareDecoder.isNotEmpty() && hardwareDecoder != "no" && hardwareDecoder != "null",
                // Forward stepping briefly unpauses mpv internally; the user remains in paused frame mode.
                status = when { sessionRestore?.externalSubtitle != null -> PlaybackStatus.BUFFERING
                    s.pendingFrame != null -> PlaybackStatus.PAUSED
                    !playbackStarted -> PlaybackStatus.BUFFERING
                    eof && wantsPlay -> PlaybackStatus.ENDED
                    data.optBoolean("paused-for-cache") -> PlaybackStatus.BUFFERING
                    paused -> PlaybackStatus.PAUSED; else -> PlaybackStatus.PLAYING },
                framePositionMs = if (paused && pts.isFinite() && s.pendingSeek == null && s.pendingFrame == null)
                    (pts * 1000).roundToLong().coerceAtLeast(0) else if (wantsPlay) null else s.framePositionMs,
                media = s.media?.copy(durationMs = duration.takeUnless { s.media?.discNavigation == true },
                    capabilities = s.capabilities.copy(canSeek = duration != null && s.media?.discNavigation != true)),
                selectedAudioId = data.optLong("aid", -1).takeIf { it >= 0 }?.let { trackId("audio", it) },
                selectedSubtitleId = (if (data.optInt("loaded") != 0 || replied) readString("sid").toLongOrNull()
                    else data.optLong("sid", -1).takeIf { it >= 0 })?.let { trackId("sub", it) },
                selectedSecondarySubtitleId = (if (data.optInt("loaded") != 0 || replied) readString("secondary-sid").toLongOrNull()
                    else data.optLong("secondary-sid", -1).takeIf { it >= 0 })?.let { trackId("sub", it) },
                selectedVideoId = data.optLong("vid", -1).takeIf { it >= 0 }?.let { trackId("video", it) }) }
            val pendingSeek = snapshots.value.pendingSeek
            // The UI publishes pending before the worker dispatches the native command.
            // An already queued poll must not use the previous seek's timer or events.
            if (pendingSeek != null && pendingSeek.id == issuedSeekId) {
                seekStarted = seekStarted || data.optInt("seek") != 0
                if (seekStarted && data.optInt("restart") != 0 && !data.optBoolean("seeking")) finishSeek(session, true)
                else if (SystemClock.elapsedRealtime() - seekIssuedAt > 10_000) finishSeek(session, false)
            }
            val pendingFrame = snapshots.value.pendingFrame
            if (pendingFrame != null && pendingFrame.id == issuedFrameId) {
                frameSeekStarted = frameSeekStarted || data.optInt("seek") != 0
                val moved = pts.isFinite() && (pts - frameStartPts) * pendingFrame.direction > 0.000001
                val settled = paused && !data.optBoolean("seeking") &&
                    (!frameUsesSeek || (frameSeekStarted && data.optInt("restart") != 0))
                if (moved && settled) finishFrame(session, (pts * 1000).roundToLong())
                else if (pendingFrame.direction > 0 && !frameUsesSeek && eof && paused) finishFrame(session, null, FrameStepFailure.BOUNDARY)
                else if (SystemClock.elapsedRealtime() - frameIssuedAt > 10_000) {
                    finishFrame(session, null, FrameStepFailure.FAILED)
                    fail(session, PlaybackError.SOURCE_UNAVAILABLE); releaseEngine(); return
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBufferAt >= 300 && current(session) && snapshots.value.pendingSeek == null && snapshots.value.pendingFrame == null) {
                lastBufferAt = now
                val end = position + (data.optDouble("demuxer-cache-duration", 0.0) * 1000).toLong().coerceAtLeast(0)
                buffer.value = PlaybackBuffer(session, position, end.coerceAtMost(duration ?: end))
            }
        }
        val s = snapshots.value
        schedulePoll(session, if (ready && pendingSubtitle == null && !audioSwitchPending &&
            s.pendingSeek == null && s.pendingFrame == null &&
            ((!wantsPlay && s.status == PlaybackStatus.PAUSED) || s.status == PlaybackStatus.ENDED)) 1_000 else 100)
    }

    private fun finishSeek(session: Long, succeeded: Boolean) {
        val id = issuedSeekId
        issuedSeekId = null
        update(session) { s ->
            s.pendingSeek?.takeIf { it.id == id }?.let {
                s.copy(pendingSeek = null, seekResult = SeekResult(it.id, s.positionMs, succeeded))
            } ?: s
        }
    }
    private fun finishFrame(session: Long, position: Long?, failure: FrameStepFailure? = null) {
        val id = issuedFrameId
        issuedFrameId = null
        update(session) { s -> s.pendingFrame?.takeIf { it.id == id }?.let {
            s.copy(pendingFrame = null, framePositionMs = position ?: s.framePositionMs,
                frameResult = FrameStepResult(it.id, it.direction, position, failure))
        } ?: s }
    }
    private fun metadata(session: Long) {
        val all = readTracks().filter {
            it.optString("type") in setOf("audio", "video", "sub") &&
                !(it.optString("type") == "video" && it.optBoolean("dependent"))
        }
        val nativeIds = mutableMapOf<String, String>()
        val publicIds = mutableMapOf<Pair<String, Long>, String>()
        fun list(type: String, kind: TrackKind) = all.filter { it.optString("type") == type }.mapIndexed { ordinal, track ->
            val nativeId = track.getLong("id")
            // Stable demux stream identity preserves track selections.
            val external = externalSubtitle?.takeIf { type == "sub" && nativeId == externalTrackId }
            val id = external?.id ?: if (track.has("ff-index")) "${kind.name}:$ordinal:${track.getInt("ff-index")}" else "mpv:$type:$nativeId"
            nativeIds[id] = nativeId.toString(); publicIds[type to nativeId] = id
            TrackDescriptor(id, kind,
                external?.label ?: listOf(track.optString("title"), track.optString("lang"), track.optString("codec")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { track.getLong("id").toString() },
                language = track.optString("lang").takeIf { it.isNotBlank() }, title = track.optString("title").takeIf { it.isNotBlank() }) }
        val params = MpvNative.read(handle, "video-params")?.takeIf { it != "null" }?.let(::JSONObject) ?: JSONObject()
        videoColorParameters = params.toString()
        videoTargetParameters = MpvNative.read(handle, "video-target-params")?.takeIf { it != "null" } ?: "{}"
        val width = params.optInt("w"); val height = params.optInt("h")
        videoFrameRate = readString("container-fps").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && width > 0 } ?: 0.0
        val aspect = params.optDouble("aspect", if (height > 0) width.toDouble() / height else 16.0 / 9.0).toFloat()
        hardwareDecoder = readString("hwdec-current"); videoOutput = readString("current-vo")
        gpuContext = readString("current-gpu-context")
        videoPixelFormat = params.optString("pixelformat")
        val details = MediaDetails(readString("file-format"), readString("video-format"), width, height,
            when {
                params.optString("gamma") in setOf("pq", "hlg") -> VideoDynamicRange.HDR
                params.optString("gamma").isEmpty() -> VideoDynamicRange.UNKNOWN
                else -> VideoDynamicRange.SDR
            })
        update(session) { s -> s.copy(media = s.media?.copy(subtitleTracks = list("sub", TrackKind.SUBTITLE),
            audioTracks = list("audio", TrackKind.AUDIO), videoTracks = list("video", TrackKind.VIDEO),
            aspectRatio = aspect.takeIf { it.isFinite() && it > 0 } ?: 16f / 9f,
            capabilities = capabilities(true).copy(canImportSubtitle = width > 0, canSelectSecondarySubtitle = width > 0, canSetHardwareDecoding = width > 0,
                canEqualize = s.capabilities.canEqualize && !equalizerFailed)),
            externalSubtitle = externalSubtitle,
            selectedSubtitleId = readString("sid").toLongOrNull()?.let { publicIds["sub" to it] },
            selectedSecondarySubtitleId = readString("secondary-sid").toLongOrNull()?.let { publicIds["sub" to it] },
            details = details, mediaInfo = "$runtimeVersion\n$videoOutput · $hardwareDecoder") }
        nativeTrackIds = nativeIds; publicTrackIds = publicIds
        val primarySubtitle = readString("sid").toLongOrNull()
        selectedClosedCaption = all.any { it.optString("type") == "sub" && it.optLong("id") == primarySubtitle && it.optString("codec") == "eia_608" }
        applySubtitleAppearance()
    }

    fun attachSurface(view: Any, target: Surface, width: Int, height: Int) {
        if (disposed) return
        val size = "${width}x$height"
        val old = surfaceBinding
        if (old != null && old.owner === view && old.surface === target && !old.released) {
            if (old.size != size) {
                old.size = size
                worker.post { if (handle != 0L && renderingSurface === old) set("android-surface-size", size) }
            }
            return
        }
        old?.let { detachSurface(it.owner) }
        val binding = SurfaceBinding(view, target, size)
        surfaceBinding = binding
        worker.post { if (!disposed && handle != 0L) attachRendering(binding) }
    }
    private fun attachRendering(binding: SurfaceBinding) {
        if (renderingSurface === binding) return
        synchronized(binding) {
            if (binding.released || !binding.surface.isValid) return
            binding.detached = CountDownLatch(1)
        }
        renderingSurface = binding
        set("android-surface-size", binding.size)
        MpvNative.surface(handle, binding.surface)
        applyTransform()
    }
    private fun detachRendering() {
        val binding = renderingSurface ?: return
        MpvNative.surface(handle, null)
        renderingSurface = null
        // A slow native destroy may follow. The UI only waits for rendering to stop.
        binding.detached.countDown()
    }
    fun detachSurface(view: Any) {
        val binding = surfaceBinding?.takeIf { it.owner === view } ?: return
        val completed = synchronized(binding) { binding.released = true; binding.detached }
        surfaceBinding = null
        worker.post { if (renderingSurface === binding) detachRendering() }
        if (!completed.await(1, TimeUnit.SECONDS)) Log.w("StellarMPV", "Surface detach timed out")
    }
    fun transform(mode: Int, x: Float, y: Float) { worker.post { fit = mode; scaleX = x; scaleY = y; if (handle != 0L) applyTransform() } }
    private fun applyTransform() {
        set("keepaspect", if (fit == 1) "no" else "yes")
        set("panscan", if (fit == 2) "1" else "0")
        set("video-aspect-override", when (fit) { 3 -> "16:9"; 4 -> "4:3"; else -> "no" })
        set("video-zoom", log2(abs(scaleX).coerceAtLeast(0.1f)).toString())
        set("vf", listOfNotNull("hflip".takeIf { scaleX < 0 }, "vflip".takeIf { scaleY < 0 }).joinToString(","))
    }
    private fun releaseEngine() {
        pollTask?.let(worker::removeCallbacks); pollTask = null
        audioPassthroughActive = false
        cancelRemote()
        ready = false
        pendingSubtitle?.let {
            pendingSubtitle = null
            MpvNative.abortCommand(handle, it.id)
            it.complete(false)
        }
        detachRendering()
        issuedSeekId = null
        issuedFrameId = null; videoFrameRate = 0.0; appliedAudioGain = 1f; playbackStarted = false
        if (handle != 0L) { MpvNative.destroy(handle); handle = 0; Log.i("StellarMPV", "released") }
        remoteToken = null
        appliedDiskCache = false
        externalSubtitle = null; externalTrackId = null
        descriptor?.close(); descriptor = null; activeEngines = 0; ready = false
    }
    @Synchronized override fun close() {
        if (disposed) return
        disposed = true; cancelRemote(); buffer.value = PlaybackBuffer()
        worker.post { releaseEngine(); surfaceBinding = null; thread?.quitSafely() }
    }
}
