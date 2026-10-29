package cn.coldlake.stellar.opensource.casting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.stellar.player.contract.*
import cn.coldlake.stellar.opensource.presentation.PlaybackRouter
import com.stellar.player.source.HttpSource
import com.stellar.player.source.SmbSource
import com.stellar.player.source.NfsSource
import com.stellar.player.source.NativeRemoteFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity-independent session owner. The UI contains no SDK objects or source credentials. */
internal class CastingController(
    private val context: Context,
    private val router: PlaybackRouter,
    private val sourceAddress: (String) -> String?,
    private val sourceHttp: (String) -> HttpSource?,
    private val sourceSmb: (String) -> SmbSource? = { null },
    private val sourceNfs: (String) -> NfsSource? = { null },
    private val sourceContainer: (String) -> String? = { null },
    private val sourceStream: (String) -> com.stellar.player.source.StreamSource? = { null },
) : AutoCloseable, PlaybackPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(CastState())
    val state = mutable.asStateFlow()
    val panel = MutableStateFlow(false)
    private val remote = MutableStateFlow(PlaybackSnapshot())
    override val snapshots = remote.asStateFlow()
    private val dlna = DlnaAdapter(context, scope)
    private var adapter: CastAdapter? = null
    private var server: CastMediaServer? = null
    private var preparingSource: CastByteSource? = null
    private var original: PlaybackSnapshot? = null
    private var activeMedia: CastMedia? = null
    private var importedSubtitle: CastSubtitle? = null
    private var subtitleSelected = false
    private var subtitleOperation: Job? = null
    internal val activeToken: Long? get() = if (router.casting) generation else null
    internal val localSubtitlePath: String? get() = original?.externalSubtitle?.path
    private var network: CastNetwork? = null
    private var operation: Job? = null
    private var statusJob: Job? = null
    private var discovery: Job? = null
    private var stopping: Job? = null
    private var generation = 0L
    private val commandMutex = Mutex()
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var wakeRenewal: Job? = null
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) { scope.launch {
            if (network?.network == lost) invalidateNetwork()
        } }
        override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) { scope.launch {
            val selected = network
            if (selected?.network == changed && !selected.matches(properties)) invalidateNetwork()
        } }
    }
    private fun invalidateNetwork() {
        cancelSession(); stopDiscovery(); network = null
        mutable.value = state.value.copy(failure = CastFailure.DISCONNECTED, discovering = false)
    }
    init {
        router.onLocalOpen = ::cancelSession
        scope.launch { dlna.devices.collect { mutable.value = state.value.copy(devices = it) } }
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), networkCallback)
    }
    fun permitted() = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    fun openPanel() { panel.value = true; if (permitted()) discover() else mutable.value = state.value.copy(failure = CastFailure.PERMISSION) }
    fun dismissPanel() {
        panel.value = false
        if (!router.casting && !state.value.connecting && stopping?.isActive != true) stopDiscovery()
    }
    fun permissionResult(granted: Boolean) { if (granted && panel.value) discover() else mutable.value = state.value.copy(failure = CastFailure.PERMISSION) }
    fun discover() {
        if (!permitted()) { mutable.value = state.value.copy(failure = CastFailure.PERMISSION); return }
        if (discovery?.isActive == true) return
        val lan = CastNetwork.find(context)
        if (lan == null) { mutable.value = state.value.copy(failure = CastFailure.NO_NETWORK); return }
        network = lan
        if (multicast == null) multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("stellar-casting").apply { setReferenceCounted(false); acquire() }
        mutable.value = state.value.copy(discovering = true, failure = null)
        discovery = scope.launch {
            try {
                dlna.discover(lan)
                delay(6000)
                dlna.checkDiscoveryAccess()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = castNetworkFailure(e, CastFailure.UNAVAILABLE)
                if (failure == CastFailure.VPN_BLOCKED && router.casting) cancelSession()
                stopDiscovery()
                mutable.value = state.value.copy(failure = failure)
            }
            finally { mutable.value = state.value.copy(discovering = false) }
        }
    }
    fun connect(device: CastDevice) {
        if (router.casting || operation?.isActive == true) return
        val local = router.snapshots.value
        val localMedia = local.media ?: return
        if (local.simulated) return
        // Reject unsupported source types before pausing the phone or starting a service.
        if (localMedia.discNavigation || (localMedia.id.startsWith("emby-session:") &&
                (device.protocol != CastProtocol.DLNA || sourceHttp(localMedia.id) == null))) {
            mutable.value = state.value.copy(failure = CastFailure.SOURCE)
            return
        }
        val lan = CastNetwork.find(context)
        if (lan == null) { mutable.value = state.value.copy(failure = CastFailure.NO_NETWORK); return }
        if (!permitted()) { mutable.value = state.value.copy(failure = CastFailure.PERMISSION); return }
        if (device.protocol == CastProtocol.DLNA && network != lan) { discover(); return }
        val token = ++generation
        original = local
        network = lan
        mutable.value = state.value.copy(device = device, connecting = true, failure = null, subtitleFailed = false)
        operation = scope.launch {
            val selected = dlna
            adapter = selected
            var prepared: PreparedMedia? = null
            var loadStarted = false
            var failureStage = CastFailure.LOAD
            pendingRemoteSeek = null; remoteSeekResult = null
            remote.value = snapshot(local, RemoteStatus(PlaybackStatus.BUFFERING, local.positionMs, local.durationMs))
            try {
                if (device.protocol == CastProtocol.DLNA) dlna.checkConnection(device)
                ensureActive()
                failureStage = CastFailure.SOURCE
                router.attachRemote(this@CastingController, scope)
                CastingService.start(context, this@CastingController)
                val localSubtitle = local.externalSubtitle
                if (device.protocol == CastProtocol.DLNA && localSubtitle != null && local.selectedSubtitleId == localSubtitle.id) {
                    try { importedSubtitle = withContext(Dispatchers.IO) { CastSubtitle.read(localSubtitle) }; subtitleSelected = true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutable.value = state.value.copy(subtitleFailed = true) }
                }
                prepared = prepare(local, device.protocol, lan, forceRelay = subtitleSelected)
                ensureActive()
                val base = checkNotNull(prepared).media
                server = prepared?.server; prepared = null
                if (subtitleSelected && (server == null || !base.mime.startsWith("video/"))) {
                    importedSubtitle = null; subtitleSelected = false
                    mutable.value = state.value.copy(subtitleFailed = true)
                }
                val media = base.withSubtitle(server?.setSubtitle(importedSubtitle.takeIf { subtitleSelected }))
                activeMedia = media
                commandMutex.withLock {
                    loadStarted = true
                    failureStage = CastFailure.LOAD
                    withTimeout(30000) { selected.load(device, media, local.positionMs, local.status == PlaybackStatus.PLAYING) }
                }
                // VPN unicast control can work while inbound connections from the TV are blocked.
                // Only a relay needs this reverse path; a direct media URL remains usable without it.
                val relay = server
                if (relay != null && !withContext(Dispatchers.IO) { lan.allowsBinding(context) }) {
                    if (withTimeoutOrNull(12000) { relay.awaitMediaRequest(); true } != true) {
                        withContext(Dispatchers.IO) { lan.requireAccess(context) }
                        error("Receiver did not request relayed media")
                    }
                }
                ensureActive()
                if (generation != token) return@launch
                mutable.value = state.value.copy(connecting = false)
                dismissPanel()
                statusJob = scope.launch { selected.status.collect { status ->
                    if (state.value.subtitleBusy) return@collect
                    remote.value = snapshot(local, status)
                    if (status.failure in setOf(CastFailure.DISCONNECTED, CastFailure.LOAD, CastFailure.VPN_BLOCKED)) {
                        cancelSession()
                    }
                    mutable.value = state.value.copy(failure = status.failure, volume = status.volume, canPause = status.canPause)
                } }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val stopped = !loadStarted || withContext(NonCancellable) {
                    commandMutex.withLock { runCatching { withTimeout(12000) { selected.stop() } }.isSuccess }
                }
                if (generation == token) {
                    if (router.casting) router.detachRemote(play = stopped && local.status == PlaybackStatus.PLAYING && e !is CastVpnBlockedException)
                    cleanupMedia(); adapter = null; original = null
                    mutable.value = state.value.copy(device = null, connecting = false,
                        failure = castNetworkFailure(e, failureStage))
                }
            } finally { prepared?.server?.close() }
        }
    }
    private var remoteSeekSequence = 0L
    private var pendingRemoteSeek: PendingSeek? = null
    private var remoteSeekResult: SeekResult? = null
    private fun snapshot(local: PlaybackSnapshot, status: RemoteStatus) = PlaybackSnapshot(
        sessionId = local.sessionId, simulated = false, remotePlayback = true, positionMs = status.positionMs,
        status = if (state.value.subtitleBusy) PlaybackStatus.BUFFERING else status.status,
        mediaInfo = local.mediaInfo, details = local.details,
        pendingSeek = pendingRemoteSeek, seekResult = remoteSeekResult,
        remoteSubtitleFailed = state.value.subtitleFailed,
        externalSubtitle = importedSubtitle?.original,
        selectedSubtitleId = importedSubtitle?.original?.id.takeIf { subtitleSelected },
        media = local.media?.copy(durationMs = status.durationMs ?: local.durationMs,
            subtitleTracks = listOfNotNull(importedSubtitle?.original?.let { TrackDescriptor(it.id, TrackKind.SUBTITLE, it.label) }),
            audioTracks = emptyList(), videoTracks = emptyList(), chapters = emptyList(),
            capabilities = Capabilities(canSeek = status.canSeek && !state.value.subtitleBusy, canSetSpeed = false, canSelectSubtitle = canChangeSubtitle(status),
                canSelectAudio = false, canStyleSubtitle = false, canDelaySubtitle = false, canImportSubtitle = canChangeSubtitle(status), canAdjustDevice = false)),
    )
    private fun canChangeSubtitle(status: RemoteStatus): Boolean = adapter === dlna && !state.value.connecting && !state.value.subtitleBusy &&
        activeMedia?.mime?.startsWith("video/") == true && status.canSeek && status.canPause &&
        (status.durationMs ?: original?.durationMs ?: 0) > 0 && status.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED) && dlna.canReloadSubtitles()

    internal suspend fun loadExternalSubtitle(session: Long, subtitle: ExternalSubtitle): Boolean = updateSubtitle(session, subtitle, true)

    private suspend fun updateSubtitle(session: Long, source: ExternalSubtitle?, enabled: Boolean): Boolean {
        if (session != snapshots.value.sessionId || !canChangeSubtitle(dlna.status.value) || operation?.isActive == true) return false
        val device = state.value.device ?: return false
        val local = original ?: return false
        val previousMedia = activeMedia ?: return false
        val previousSubtitle = importedSubtitle
        val previousSelected = subtitleSelected
        val previousServer = server
        val token = generation
        subtitleOperation = currentCoroutineContext()[Job]
        mutable.value = state.value.copy(subtitleBusy = true, subtitleFailed = false)
        remote.value = snapshot(local, dlna.status.value)
        var prepared: PreparedMedia? = null
        var loadStarted = false
        try {
            val candidate = if (!enabled || source == previousSubtitle?.original) previousSubtitle
                else withContext(Dispatchers.IO) { CastSubtitle.read(checkNotNull(source)) }
            currentCoroutineContext().ensureActive()
            if (enabled && server == null) prepared = prepare(local, CastProtocol.DLNA, checkNotNull(network), forceRelay = true)
            currentCoroutineContext().ensureActive()
            return commandMutex.withLock {
                if (generation != token) return@withLock false
                // Read receiver progress now; the two-second UI poll may already be stale.
                val before = withTimeout(12000) { dlna.currentPlayback() }
                remote.value = snapshot(local, before)
                val base = prepared?.media ?: previousMedia
                prepared?.let { server = it.server }; prepared = null
                loadStarted = true
                val media = base.withSubtitle(server?.setSubtitle(candidate.takeIf { enabled }))
                try {
                    withTimeout(30000) { dlna.load(device, media, before.positionMs, before.status == PlaybackStatus.PLAYING) }
                    currentCoroutineContext().ensureActive()
                    if (generation != token) return@withLock false
                    importedSubtitle = candidate; subtitleSelected = enabled; activeMedia = media
                    true
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    // The receiver may have applied part of the failed load. Restore the previous request once.
                    val restored = try {
                        val url = previousServer?.setSubtitle(previousSubtitle.takeIf { previousSelected })
                        val previous = previousMedia.withSubtitle(url)
                        withTimeout(30000) { dlna.load(device, previous, before.positionMs, before.status == PlaybackStatus.PLAYING) }
                        activeMedia = previous
                        true
                    } catch (restoreError: Exception) {
                        if (restoreError is CancellationException && restoreError !is TimeoutCancellationException) throw restoreError
                        false
                    }
                    if (generation == token) {
                        if (server !== previousServer) { server?.close(); server = previousServer }
                        if (!restored) { cancelSession(); mutable.value = state.value.copy(failure = CastFailure.CONTROL) }
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) {
                if (loadStarted && generation == token) cancelSession()
                throw e
            }
            if (generation == token) mutable.value = state.value.copy(subtitleFailed = true)
            return false
        } finally {
            prepared?.server?.close()
            if (generation == token) {
                subtitleOperation = null
                mutable.value = state.value.copy(subtitleBusy = false)
                remote.value = snapshot(local, dlna.status.value)
            }
        }
    }
    private class PreparedMedia(val media: CastMedia, val server: CastMediaServer? = null)
    private suspend fun prepare(local: PlaybackSnapshot, protocol: CastProtocol, lan: CastNetwork, forceRelay: Boolean = false): PreparedMedia {
        val media = checkNotNull(local.media)
        require(!media.discNavigation)
        val http = sourceHttp(media.id)
        val smb = sourceSmb(media.id)
        val nfs = sourceNfs(media.id)
        val stream = sourceStream(media.id)
        // NAS credentials stay in the source object; no synthetic credential-bearing URI.
        val address = if (smb != null || nfs != null || stream != null) "" else http?.address ?: checkNotNull(sourceAddress(media.id))
        val uri = if (address.startsWith('/')) Uri.fromFile(java.io.File(address)) else Uri.parse(address)
        var extension = sourceContainer(media.id)?.substringBefore(',')?.lowercase()?.takeIf { it.isNotBlank() }
            ?: media.title.substringAfterLast('.', "").lowercase().ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase() }
        var mime = when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> { require(protocol == CastProtocol.DLNA); "video/quicktime" }
            "webm" -> "video/webm"
            "mkv" -> { require(protocol == CastProtocol.DLNA); "video/x-matroska" }
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> error("DASH is not supported for DLNA casting")
            else -> if (uri.scheme == "content") withContext(Dispatchers.IO) {
                context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
            } else null
        }
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        val direct = uri.scheme in setOf("http", "https") && !loopback && http == null && uri.userInfo == null
        if (direct && (!forceRelay || extension in setOf("m3u8", "mpd"))) return PreparedMedia(CastMedia(address, media.title, checkNotNull(mime), local.durationMs))
        require(extension !in setOf("m3u8", "mpd")) // Proxying a manifest alone cannot make its children accessible.
        val source = when {
            stream != null -> NasCastSource(checkNotNull(mime), stream::createReader)
            smb != null -> NasCastSource(checkNotNull(mime)) { NativeRemoteFile.smb(smb) }
            nfs != null -> NasCastSource(checkNotNull(mime)) { NativeRemoteFile.nfs(nfs) }
            uri.scheme in setOf("content", "file") -> LocalCastSource(context, uri, checkNotNull(mime))
            else -> { require(uri.scheme in setOf("http", "https")); HttpCastSource(http ?: HttpSource(address, emptyMap()), mime) }
        }
        preparingSource = source
        var relay: CastMediaServer? = null
        try {
            keepRelayAwake()
            withContext(Dispatchers.IO) {
                source.open(true, null).use {
                    require(it.code == 200)
                    if (mime == null) {
                        // Emby's /stream has no extension. Reuse this HEAD when metadata is absent.
                        val contentType = it.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
                        extension = when (contentType) {
                            "video/x-matroska", "video/matroska" -> { require(protocol == CastProtocol.DLNA); "mkv" }
                            "video/mp4" -> "mp4"
                            "video/webm" -> "webm"
                            "video/quicktime" -> { require(protocol == CastProtocol.DLNA); "mov" }
                            "audio/mpeg" -> "mp3"
                            "audio/mp4" -> "m4a"
                            "audio/aac" -> "aac"
                            "audio/wav", "audio/x-wav" -> "wav"
                            "audio/flac", "audio/x-flac" -> "flac"
                            else -> error("Unsupported source")
                        }
                        mime = contentType
                    }
                }
                ensureActive()
                relay = CastMediaServer(lan.address, source, extension.ifBlank { "mp4" })
            }
            return PreparedMedia(CastMedia(checkNotNull(relay).url, media.title, checkNotNull(mime), local.durationMs), relay)
        } catch (e: Exception) { relay?.close() ?: source.close(); throw e }
        finally { if (preparingSource === source) preparingSource = null }
    }
    private fun keepRelayAwake() {
        mutable.value = state.value.copy(relaying = true)
        if (wake != null) return
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StellarPlayer:casting").apply { setReferenceCounted(false) }
        wake = lock
        // Renew only while this session exists; a stopped event loop cannot hold an unbounded lock.
        wakeRenewal = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) { lock.acquire(10 * 60 * 1000L); delay(5 * 60 * 1000L) }
        }
    }
    override fun dispatch(command: PlaybackCommand): CommandResult {
        if (command !is PlaybackCommand.InSession || command.sessionId != snapshots.value.sessionId) return CommandResult.Rejected(RejectionReason.STALE_SESSION)
        val selected = adapter ?: return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        if (state.value.connecting || state.value.subtitleBusy || state.value.failure == CastFailure.DISCONNECTED) return CommandResult.Rejected(RejectionReason.OPERATION_BUSY)
        if (command is PlaybackCommand.SelectTrack) {
            if (command.kind != TrackKind.SUBTITLE || !canChangeSubtitle(selected.status.value) ||
                (command.trackId != null && command.trackId != importedSubtitle?.original?.id)) return CommandResult.Rejected(RejectionReason.TRACK_UNAVAILABLE)
            if (command.trackId == snapshots.value.selectedSubtitleId && !state.value.subtitleFailed) return CommandResult.Accepted
            scope.launch(start = CoroutineStart.UNDISPATCHED) { updateSubtitle(command.sessionId, importedSubtitle?.original, command.trackId != null) }
            return CommandResult.Accepted
        }
        if (command is PlaybackCommand.Seek && !snapshots.value.canSeek) return CommandResult.Rejected(RejectionReason.SEEK_UNAVAILABLE)
        if (command is PlaybackCommand.Pause && !selected.status.value.canPause) return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        if (command !is PlaybackCommand.Play && command !is PlaybackCommand.Pause && command !is PlaybackCommand.Seek) return CommandResult.Rejected(RejectionReason.INVALID_STATE)
        val token = generation
        val seek = (command as? PlaybackCommand.Seek)?.let { PendingSeek(++remoteSeekSequence, it.positionMs) }
        if (seek != null) { pendingRemoteSeek = seek; remote.value = snapshots.value.copy(pendingSeek = seek) }
        scope.launch {
            commandMutex.withLock {
                if (token != generation) return@withLock
                try {
                    when (command) {
                        is PlaybackCommand.Play -> selected.play()
                        is PlaybackCommand.Pause -> selected.pause()
                        is PlaybackCommand.Seek -> {
                            selected.seek(command.positionMs.coerceIn(0, snapshots.value.durationMs ?: Long.MAX_VALUE))
                            if (token == generation && pendingRemoteSeek?.id == seek?.id) {
                                pendingRemoteSeek = null
                                remoteSeekResult = SeekResult(checkNotNull(seek).id, selected.status.value.positionMs, true)
                                remote.value = snapshots.value.copy(positionMs = selected.status.value.positionMs, pendingSeek = null, seekResult = remoteSeekResult)
                            }
                        }
                        else -> Unit
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (token == generation) {
                    if (pendingRemoteSeek?.id == seek?.id && seek != null) {
                        pendingRemoteSeek = null; remoteSeekResult = SeekResult(seek.id, null, false)
                        remote.value = snapshots.value.copy(pendingSeek = null, seekResult = remoteSeekResult)
                    }
                    mutable.value = state.value.copy(failure = castNetworkFailure(e, CastFailure.CONTROL))
                } }
            }
        }
        return CommandResult.Accepted
    }
    fun volume(value: Float) {
        if (state.value.connecting || state.value.subtitleBusy || state.value.volume == null) return
        val selected = adapter ?: return
        scope.launch { commandMutex.withLock {
            try { selected.volume(value) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(failure = castNetworkFailure(e, CastFailure.CONTROL)) }
        } }
    }
    fun togglePlayback() {
        val snapshot = snapshots.value
        dispatch(if (snapshot.status == PlaybackStatus.PLAYING) PlaybackCommand.Pause(snapshot.sessionId)
            else PlaybackCommand.Play(snapshot.sessionId))
    }
    fun seekTo(positionMs: Long) { dispatch(PlaybackCommand.Seek(snapshots.value.sessionId, positionMs)) }
    fun seekBy(offsetMs: Long) = seekTo((snapshots.value.positionMs + offsetMs).coerceAtLeast(0))
    fun returnToPhone() {
        if (state.value.connecting || state.value.subtitleBusy || operation?.isActive == true) return
        val selected = adapter ?: return
        val token = generation
        operation = scope.launch {
            try {
                commandMutex.withLock { withTimeout(12000) { selected.stop() } }
                if (token != generation) return@launch
                val position = snapshots.value.positionMs
                statusJob?.cancel(); statusJob = null
                router.detachRemote(position, play = true)
                cleanupMedia(); original = null; adapter = null
                mutable.value = state.value.copy(device = null, failure = null)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (token == generation) mutable.value = state.value.copy(failure = castNetworkFailure(e, CastFailure.CONTROL))
            }
        }
    }
    fun cancelSession() {
        generation++
        subtitleOperation?.cancel(); subtitleOperation = null
        operation?.cancel(); operation = null; statusJob?.cancel(); statusJob = null
        val selected = adapter; adapter = null; original = null
        if (router.casting) router.detachRemote(snapshots.value.positionMs)
        cleanupMedia()
        mutable.value = state.value.copy(device = null, connecting = false, subtitleBusy = false, subtitleFailed = false)
        val token = generation
        if (selected != null) stopping = scope.launch {
            commandMutex.withLock { runCatching { withTimeout(12000) { selected.stop() } } }
            if (generation == token && !panel.value) stopDiscovery()
        } else if (!panel.value && stopping?.isActive != true) stopDiscovery()
    }
    private fun cleanupMedia() {
        mutable.value = state.value.copy(relaying = false, volume = null)
        preparingSource?.close(); preparingSource = null
        server?.close(); server = null
        activeMedia = null; importedSubtitle = null; subtitleSelected = false
        wakeRenewal?.cancel(); wakeRenewal = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        CastingService.finish(this)
    }
    private fun stopDiscovery() {
        discovery?.cancel(); discovery = null
        if (adapter !== dlna) dlna.close()
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
    }
    override fun close() {
        cancelSession()
        connectivity.unregisterNetworkCallback(networkCallback)
        router.onLocalOpen = null
        // Allow bounded remote Stop / UPnP shutdown to drain; no Activity is retained.
        scope.launch { stopping?.join(); stopDiscovery(); delay(500); scope.cancel() }
    }
}
