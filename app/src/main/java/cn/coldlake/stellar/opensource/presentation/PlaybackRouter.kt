package cn.coldlake.stellar.opensource.presentation

import com.stellar.player.contract.*
import com.stellar.player.source.HttpSource
import com.stellar.player.source.SmbSource
import com.stellar.player.source.NfsSource
import cn.coldlake.stellar.opensource.medialibrary.isRealMedia
import cn.coldlake.stellar.opensource.medialibrary.resolveRealMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.HandlerThread
import android.content.Context
import com.stellar.player.mpv.MpvPlaybackPort
import com.stellar.player.mpv.MpvSource

/** Host composition selects adapters; the UI and interaction controller see only PlaybackPort. */
class PlaybackRouter(scope: CoroutineScope,
                     resolveSmb: (String) -> SmbSource? = { null },
                     resolveNfs: (String) -> NfsSource? = { null },
                     resolveWebDav: (String) -> String? = { null },
                     ignoreCertificateErrors: () -> Boolean = { false },
                     resolveHttp: (String) -> HttpSource? = { null },
                     accurateSeek: () -> Boolean = { true },
                     mpvDiskCache: com.stellar.player.nativeio.PlaybackDiskCache? = null,
                     subtitleAppearance: () -> Map<com.stellar.player.contract.SubtitleAppearanceOption, String> = { emptyMap() },
                     bufferOptions: () -> com.stellar.player.nativeio.PlaybackBufferOptions = { com.stellar.player.nativeio.PlaybackBufferOptions() },
                     context: Context? = null,
                     forceSdr: () -> Boolean = { false },
                     resolveStream: (String) -> com.stellar.player.source.StreamSource? = { null }) : PlaybackPort, AutoCloseable {
    private val nativeThread = HandlerThread("stellar-playback").apply { start() }
    private val nativeWorker = Handler(nativeThread.looper)
    val mpv = MpvPlaybackPort({ id ->
        resolveHttp(id)?.let { MpvSource(it) }
            ?: (resolveWebDav(id) ?: resolveRealMedia(id))?.let { MpvSource(it, verifyTls = !ignoreCertificateErrors()) }
    }, context?.contentResolver, accurateSeek, { bufferOptions().let { (it.minMs / 1000).toInt() to (it.maxMs / 1000).toInt() } },
        nativeWorker, context?.cacheDir, resolveSmb, resolveNfs, forceSdr,
        subtitleAppearance = subtitleAppearance,
        context = context, resolveStream = resolveStream,
        prepareDiskCache = { source ->
            mpvDiskCache?.prepare(source.address, if (source.address.startsWith("stellarcloud:")) null else
                HttpSource(source.address, source.headers, source.userAgent, source.verifyTls))?.path?.toFile()
        })
    private val mutableBuffer = MutableStateFlow(PlaybackBuffer())
    val buffering = mutableBuffer.asStateFlow()
    private var active: PlaybackPort = mpv
    private var remote: PlaybackPort? = null
    private var remoteCollection: kotlinx.coroutines.Job? = null
    internal var onLocalOpen: (() -> Unit)? = null
    internal var onBeforeLocalClose: (() -> Unit)? = null
    internal var onReportingOwnerChanged: ((PlaybackSnapshot, Long) -> Unit)? = null
    internal var ownerGeneration = 0L; private set
    private var waitingLocalSeek: Long? = null
    private var waitingForLocal = false
    private fun grantReporting(value: PlaybackSnapshot) { onReportingOwnerChanged?.invoke(value, ownerGeneration) }
    internal val casting get() = remote != null
    internal val localSnapshot get() = active.snapshots.value
    internal fun attachRemote(port: PlaybackPort, scope: CoroutineScope) {
        active.dispatch(PlaybackCommand.Pause(active.snapshots.value.sessionId))
        remote = port
        remoteCollection?.cancel()
        ownerGeneration++
        var granted = false
        remoteCollection = scope.launch { port.snapshots.collect { value ->
            if (remote === port) {
                if (!granted && value.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.ENDED) && value.mediaInfo != null) {
                    granted = true; grantReporting(value)
                }
                if (granted) mutable.value = value
            }
        } }
    }
    internal fun detachRemote(positionMs: Long? = null, play: Boolean = false) {
        remoteCollection?.cancel(); remoteCollection = null; remote = null
        val local = active.snapshots.value
        ownerGeneration++
        waitingForLocal = positionMs != null && local.canSeek && positionMs != (local.framePositionMs ?: local.positionMs)
        waitingLocalSeek = local.seekResult?.id
        if (!waitingForLocal) grantReporting(local)
        if (positionMs != null && local.canSeek) active.dispatch(PlaybackCommand.Seek(local.sessionId, positionMs))
        if (play) active.dispatch(PlaybackCommand.Play(local.sessionId))
        mutable.value = active.snapshots.value
    }
    private val mutable = MutableStateFlow(active.snapshots.value)
    override val snapshots = mutable.asStateFlow()
    init {
        listOf(mpv).forEach { port -> scope.launch {
            port.snapshots.collect { value -> if (active === port && remote == null) {
                if (waitingForLocal && value.pendingSeek == null && value.seekResult?.succeeded == true && value.seekResult?.id != waitingLocalSeek) {
                    waitingForLocal = false; grantReporting(value)
                }
                mutable.value = value
            } }
        } }
        scope.launch { mpv.buffering.collect { if (active === mpv) mutableBuffer.value = it } }
    }
    override fun dispatch(command: PlaybackCommand): CommandResult {
        if (command is PlaybackCommand.Close && command.sessionId != snapshots.value.sessionId)
            return CommandResult.Rejected(RejectionReason.STALE_SESSION)
        if (command is PlaybackCommand.Close) onBeforeLocalClose?.invoke()
        if (command is PlaybackCommand.Open || command is PlaybackCommand.Close) onLocalOpen?.invoke()
        else remote?.let { return it.dispatch(command) }
        if (command is PlaybackCommand.Open) {
            ownerGeneration++; waitingForLocal = false
            val real = isRealMedia(command.media.id)
            val next = mpv
            if (active !== next) {
                active.dispatch(PlaybackCommand.Close(active.snapshots.value.sessionId))
                active = next
            }
        }
        val result = active.dispatch(command)
        mutable.value = active.snapshots.value
        if (command is PlaybackCommand.Open || command is PlaybackCommand.Close)
            mutableBuffer.value = PlaybackBuffer(mutable.value.sessionId)
        return result
    }

    suspend fun sampleDiagnostics(session: Long): PlaybackDiagnostics? = withContext(Dispatchers.Main.immediate) {
        val target = active
        if (remote != null || snapshots.value.sessionId != session) return@withContext null
        val result = when (target) {
            mpv -> mpv.sampleDiagnostics(session)
            else -> null
        }
        result.takeIf { target === active && remote == null && snapshots.value.sessionId == session }
    }
    suspend fun loadExternalSubtitle(session: Long, subtitle: ExternalSubtitle): Boolean {
        val target = active
        if (remote != null || target.snapshots.value.sessionId != session) return false
        val loaded = when (target) {
            mpv -> mpv.loadExternalSubtitle(session, subtitle)
            else -> false
        }
        if (active === target && remote == null) mutable.value = target.snapshots.value
        return loaded && active === target && remote == null && snapshots.value.sessionId == session
    }
    fun transform(fit: Int, x: Float, y: Float) {
        if (active === mpv) mpv.transform(fit, x, y)
    }
    override fun close() { remoteCollection?.cancel(); mpv.close(); nativeWorker.post { nativeThread.quitSafely() } }
}
