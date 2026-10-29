package cn.coldlake.stellar.opensource.presentation

import cn.coldlake.stellar.opensource.playback.reporting.*
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stellar.player.contract.*
import cn.coldlake.stellar.opensource.medialibrary.isRealMedia
import cn.coldlake.stellar.opensource.medialibrary.networkMedia
import com.stellar.player.source.SmbSource
import com.stellar.player.source.NfsSource
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavPlayback
import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyPlayback
import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyEpisodeQueue
import com.stellar.player.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@kotlinx.serialization.Serializable
enum class AppRoute : androidx.navigation3.runtime.NavKey { LIBRARY, PLAYER }
enum class HomeTab { MEDIA, MEDIA_LIBRARY, SETTINGS }
data class PlayerUiState(val notice: RejectionReason? = null, val feature: PlayerFeature? = null)

class PlayerViewModel(private val savedState: SavedStateHandle,
    private val controlsPreferences: android.content.SharedPreferences? = null,
    ignoreCertificateErrors: () -> Boolean = { false },
    private val appContext: android.content.Context? = null) : ViewModel(), PlayerPanelActions {
    internal val navigation = cn.coldlake.stellar.opensource.navigation.AppNavigation(savedState)
    private var equalizerPreference = EqualizerPreference.decode(
        controlsPreferences?.getString(EqualizerPreference.KEY, null) ?: savedState.get<String>(EqualizerPreference.KEY))
    private val reportingRuntime by lazy { appContext?.let(PlaybackReportingRuntime::get) }
    private var reporting: PlaybackReportingCoordinator? = null
    private var reportingPoll: kotlinx.coroutines.Job? = null
    private val recordKeys = mutableMapOf<String, PlaybackRecordKey>()
    private var catalogVersion: Long? = null
    private var resumeOpen: kotlinx.coroutines.Job? = null
    private fun recordKey(media: MediaDescriptor): PlaybackRecordKey? = recordKeys[media.id]
        ?: embySource?.takeIf { it.id == media.id }?.let { PlaybackIdentity.server(it.profile, it.item.id, it.version.id) }
        ?: PlaybackIdentity.locator(media.id)
    private fun finishReporting(reason: FinishReason = FinishReason.CLOSED) {
        reportingPoll?.cancel(); reportingPoll = null
        reporting?.observe(port.snapshots.value, port.ownerGeneration)
        reporting?.finish(reason); reporting = null
    }
    private fun openPlayback(media: MediaDescriptor, restore: PlaybackRestore? = null): CommandResult {
        finishReporting(FinishReason.REPLACED)
        val result = port.dispatch(PlaybackCommand.Open(media, restore, equalizerPreference,
            appContext?.let { cn.coldlake.stellar.opensource.settings.SubtitleLanguageSettings(it).targetLanguage() }))
        if (result is CommandResult.Accepted && !media.discNavigation) {
            reporting = reportingRuntime?.coordinator(recordKey(media), embySource?.takeIf { it.id == media.id })?.also {
                it.bind(media.id, port.snapshots.value.sessionId, port.ownerGeneration)
            }
            reporting?.let { coordinator ->
                reportingPoll = viewModelScope.launch {
                    while (true) { delay(1_000); coordinator.observe(snapshot.value, port.ownerGeneration) }
                }
            }
        }
        return result
    }

    override fun setEqualizer(settings: EqualizerSettings) {
        val s = snapshot.value
        if (port.dispatch(PlaybackCommand.SetEqualizer(s.sessionId, settings)) is CommandResult.Accepted) {
            equalizerPreference = settings.copy(bandsDb = settings.bandsDb.toList())
            val encoded = EqualizerPreference.encode(equalizerPreference)
            controlsPreferences?.edit { putString(EqualizerPreference.KEY, encoded) }
            savedState[EqualizerPreference.KEY] = encoded
        }
    }
    private var smbSource: SmbSource? = null
    private var nfsSource: NfsSource? = null
    private var webDavSource: WebDavPlayback? = null
    private var dvdSource: com.stellar.player.disc.DiscSource? = null
    private val dvdAddresses = mutableMapOf<String, String>()
    private var dvdOpen: kotlinx.coroutines.Job? = null
    private var dvdGeneration = 0L
    private val dvdLoadingState = MutableStateFlow(false)
    val dvdLoading = dvdLoadingState.asStateFlow()
    private val discMenuPlayback = DiscMenuPlayback(viewModelScope, { snapshot.value }, ::playDiscMenu, ::discMenuTracks)
    val discNavigation = discMenuPlayback.state
    private var discTitleBeforeMenu: MediaDescriptor? = null
    private var discTitlePosition = 0L
    private var discMenuMediaId: String? = null
    fun openDiscMenu() {
        val source = dvdSource ?: return
        if (!discNavigation.value.active) {
            discTitleBeforeMenu = snapshot.value.media
            discTitlePosition = snapshot.value.positionMs
        }
        discMenuPlayback.open(source)
    }
    fun discMenuInput(key: com.stellar.player.disc.DiscMenuKey, x: Int = 0, y: Int = 0) = discMenuPlayback.input(key, x, y)
    private fun discMenuTracks(menu: com.stellar.player.disc.DiscMenuState) {
        val s=snapshot.value
        if(s.media?.discNavigation!=true)return
        val audio=s.media?.audioTracks?.getOrNull(menu.audio)?.id
        if(audio!=null && s.selectedAudioId!=audio)port.dispatch(PlaybackCommand.SelectTrack(s.sessionId,TrackKind.AUDIO,audio))
        val subtitle=if(menu.interactive && dvdSource?.bluray==false) null else s.media?.subtitleTracks?.getOrNull(menu.subtitle)?.id
        if(s.selectedSubtitleId!=subtitle)port.dispatch(PlaybackCommand.SelectTrack(s.sessionId,TrackKind.SUBTITLE,subtitle))
    }
    private fun playDiscMenu(address: String) {
        val source = dvdSource ?: return
        val id = discMenuMediaId ?: "${if (source.bluray) "bluray-session" else "dvd-session"}:${java.util.UUID.randomUUID()}/menu".also { discMenuMediaId = it }
        dvdAddresses[id] = address
        openQueued(MediaDescriptor(id, appContext?.getString(cn.coldlake.stellar.opensource.R.string.disc_menu) ?: "",
            MediaKind.VIDEO, null, discNavigation = true))
    }
    fun exitDiscMenu() {
        discMenuPlayback.close()
        discMenuMediaId?.let { dvdAddresses.remove(it) };discMenuMediaId=null
        discTitleBeforeMenu?.let { openQueued(it, discTitlePosition) }
    }
    private var cloudSource: cn.coldlake.stellar.opensource.medialibrary.cloud.CloudPlayback? = null
    private var embySource: EmbyPlayback? = null
    private var episodeQueue: EmbyEpisodeQueue? = null
    private var episodeLoad: kotlinx.coroutines.Job? = null
    private var episodeOpen: kotlinx.coroutines.Job? = null
    private var episodeOpenGeneration = 0L
    private var catalogOpen: kotlinx.coroutines.Job? = null
    private fun closeWebDav() {
        episodeOpenGeneration++
        episodeOpen?.cancel(); episodeOpen = null
        episodeLoad?.cancel(); episodeLoad = null; episodeQueue = null
        panelState.value = panelState.value.copy(episodeQueue = false, queuePreparing = false)
        cancelDvdOpen()
        discMenuPlayback.close();discMenuPlayback.available(false);discMenuMediaId=null;discTitleBeforeMenu=null
        dvdSource?.close(); dvdSource = null; dvdAddresses.clear()
        finishReporting(); catalogVersion = null
        resumeOpen?.cancel(); resumeOpen = null; recordKeys.clear()
        savedState.remove<Long>("catalogVersion")
        embySource?.close(); embySource = null
        webDavSource?.close(); webDavSource = null
        cloudSource?.close(); cloudSource = null
    }
    private val seekSettings = appContext?.let { cn.coldlake.stellar.opensource.settings.PlaybackSeekSettings(it) }
    private val outputSettings = appContext?.let { cn.coldlake.stellar.opensource.settings.PlaybackOutputSettings(it) }
    private val bufferSettings = appContext?.let { cn.coldlake.stellar.opensource.settings.PlaybackBufferSettings(it) }
    private val subtitleAppearance = appContext?.let { cn.coldlake.stellar.opensource.settings.SubtitleAppearanceSettings(it) }
    private val subtitleAppearanceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mpv.updateSubtitleAppearance()
    }
    private val port = PlaybackRouter(viewModelScope,
        resolveSmb = { id -> smbSource?.takeIf { it.id == id } },
        resolveNfs = { id -> nfsSource?.takeIf { it.id == id } },
        resolveWebDav = { id -> dvdAddresses[id] ?: webDavSource?.takeIf { it.id == id }?.address },
        resolveHttp = { id -> embySource?.takeIf { it.id == id }?.source },
        resolveStream = { id -> cloudSource?.takeIf { it.id == id } },
        ignoreCertificateErrors = ignoreCertificateErrors,
        accurateSeek = { seekSettings?.accurateSeek ?: true },
        mpvDiskCache = appContext?.let { cn.coldlake.stellar.opensource.settings.PlaybackCacheSettings.mpvDiskCache(it) },
        subtitleAppearance = { subtitleAppearance?.snapshot().orEmpty() },
        bufferOptions = { bufferSettings?.options ?: com.stellar.player.nativeio.PlaybackBufferOptions() },
        context = appContext,
        forceSdr = { outputSettings?.forceSdr ?: false })
    val mpv get() = port.mpv
    val buffering get() = port.buffering
    val diagnostics = PlayerDiagnostics(port)
    internal val isCasting get() = port.casting
    internal val casting = appContext?.let { context -> cn.coldlake.stellar.opensource.casting.CastingController(context, port,
        sourceAddress = { id -> webDavSource?.takeIf { it.id == id }?.address
            ?: cn.coldlake.stellar.opensource.medialibrary.resolveRealMedia(id) },
        sourceHttp = { id -> embySource?.takeIf { it.id == id }?.source },
        sourceStream = { id -> cloudSource?.takeIf { it.id == id } },
        sourceSmb = { id -> smbSource?.takeIf { it.id == id } },
        sourceNfs = { id -> nfsSource?.takeIf { it.id == id } },
        sourceContainer = { id -> embySource?.takeIf { it.id == id }?.let { playback ->
            playback.item.sources.firstOrNull { it.id == playback.version.id }?.container
        } }) }
    fun transform(fit: Int, x: Float, y: Float) = port.transform(fit, x, y)
    private val localMedia = savedState.get<ArrayList<String>>("localMedia").orEmpty().chunked(3).filter { it.size == 3 }.map {
        MediaDescriptor(it[0], it[1], MediaKind.valueOf(it[2]), null)
    }.filterNot { it.id.startsWith("bluray-session:") || it.id.startsWith("dvd-session:") || it.id.startsWith("smb-session:") || it.id.startsWith("nfs-session:") || it.id.startsWith("webdav-session:") || it.id.startsWith("emby-session:") || it.id.startsWith("drive-session:") || it.id.startsWith("onedrive-session:") }.toMutableList()
    private fun findMedia(id: String?): MediaDescriptor? = localMedia.firstOrNull { it.id == id }
    val snapshot = port.snapshots
    val interactions = PlayerInteractionController({ snapshot.value }, port::dispatch, SystemClock::elapsedRealtime,
        ::scheduleInteractionTick)
    private fun scheduleInteractionTick(delayMs: Long) {
        viewModelScope.launch { delay(delayMs); interactions.tick() }
    }
    private val state = MutableStateFlow(PlayerUiState())
    val ui = state.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private val queue = PlaybackQueue(savedState.get<ArrayList<String>>("queue")?.mapNotNull(::findMedia) ?: emptyList(),
        RepeatMode.entries.firstOrNull { it.name == savedState.get<String>("repeat") } ?: RepeatMode.ALL,
        savedState["randomState"] ?: 41L)
    private val panelState = MutableStateFlow(PlayerPanelState(queue = queue.items, repeat = queue.mode,
        style = restoreStyle(savedState.get<ArrayList<String>>("subtitleStyle")), delayMs = savedState["subtitleDelay"] ?: 0))
    val panels = panelState.asStateFlow()
    private var customStyle = restoreStyle(savedState.get<ArrayList<String>>("customStyle"))
    private val tools = PlaybackTools(restoreTools(savedState.get<ArrayList<String>>("tools"), savedState.get<ArrayList<String>>("bookmarks")).let {
        it.copy(doubleTapSeek = controlsPreferences?.getBoolean("doubleTapSeek", it.doubleTapSeek) ?: it.doubleTapSeek,
            doubleTapPlay = controlsPreferences?.getBoolean("doubleTapPlay", it.doubleTapPlay) ?: it.doubleTapPlay,
            hideWhenPaused = controlsPreferences?.getBoolean("hideWhenPaused", it.hideWhenPaused) ?: it.hideWhenPaused)
    })
    private val interactionSettings = controlsPreferences?.let { cn.coldlake.stellar.opensource.settings.PlaybackInteractionSettings(it) }
    private var interactionOptions = interactionSettings?.read()
    private var hidePausedControls = controlsPreferences?.getBoolean("hideWhenPaused", true) ?: tools.state.hideWhenPaused
    private val interactionListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != EqualizerPreference.KEY) {
            interactionOptions = interactionSettings?.read()
            hidePausedControls = controlsPreferences?.getBoolean("hideWhenPaused", true) ?: tools.state.hideWhenPaused
            interactions.interrupt()
            syncTools()
        }
    }

    init {
        controlsPreferences?.registerOnSharedPreferenceChangeListener(interactionListener)
        subtitleAppearance?.preferences?.registerOnSharedPreferenceChangeListener(subtitleAppearanceListener)
        val restored = findMedia(savedState["mediaId"])
        if (restored != null) {
            openPlayback(restored, if (isRealMedia(restored.id)) PlaybackRestore(
                savedState["position"] ?: 0L, savedState["speed"] ?: 1f, savedState["subtitle"], savedState["audio"], savedState["video"],
                savedState["subtitleDelay"] ?: 0, savedState.get<ArrayList<String>>("externalSubtitle")?.takeIf { it.size == 3 }?.let { ExternalSubtitle(it[0], it[1], it[2]) },
                secondarySubtitleId = savedState["secondarySubtitle"]) else null)
            val session = snapshot.value.sessionId

        }
        port.onBeforeLocalClose = { finishReporting() }
        port.onReportingOwnerChanged = { value, generation -> reporting?.transfer(value.sessionId, generation) }
        navigation.restore(restored != null)
        state.value = PlayerUiState()
        interactions.restorePresentation(savedState["scale"] ?: 1f, savedState["brightness"] ?: 0.5f, savedState["volume"] ?: 0.5f)
        tools.closeMini()
        syncTools()
        interactions.setEnabled(navigation.current == AppRoute.PLAYER)
        viewModelScope.launch {
            var previousStatus = snapshot.value.status
            var previousMedia: MediaDescriptor? = null
            snapshot.collect { value ->
                if (!value.simulated && panels.value.delayMs != value.subtitleDelayMs)
                    panelState.value = panels.value.copy(delayMs = value.subtitleDelayMs)
                reporting?.observe(value, port.ownerGeneration)
                if (!value.simulated && value.media?.discNavigation != true && value.status == PlaybackStatus.ENDED && previousStatus != PlaybackStatus.ENDED) {
                    advanceQueueAtEnd(PlaybackStatus.PLAYING)
                }
                val actualMedia = value.media
                if (!value.simulated && actualMedia != null && actualMedia != previousMedia) {
                    queue.replace(queue.items.map { if (it.id == actualMedia.id) actualMedia else it })
                    syncQueue()
                }
                previousMedia = actualMedia
                previousStatus = value.status
                // Capture completed seeks/track changes even while the foreground clock is stopped.
                if (value.status != PlaybackStatus.PLAYING) checkpointPlayback()
                interactions.tick()
            }
        }
        viewModelScope.launch {
            foreground.collectLatest { active ->
                if (!active) return@collectLatest
                var previous = SystemClock.elapsedRealtime()
                while (true) {
                    delay(if (snapshot.value.status == PlaybackStatus.PLAYING || tools.state.remainingMs != null) 250 else 1_000)
                    val now = SystemClock.elapsedRealtime()
                    if (navigation.current != AppRoute.LIBRARY || tools.state.mini) {
                        val elapsed = (now - previous).coerceIn(0, 1000)
                        if (tools.state.remainingMs != null) {
                            if (tools.advance(elapsed)) command(PlaybackCommand.Pause(snapshot.value.sessionId))
                            syncTools()
                        }
                    }
                    previous = now
                    interactions.tick()
                    if (snapshot.value.status == PlaybackStatus.PLAYING) checkpointPlayback()
                }
            }
        }
    }

    override fun onCleared() {
        controlsPreferences?.unregisterOnSharedPreferenceChangeListener(interactionListener)
        casting?.close()
        subtitleAppearance?.preferences?.unregisterOnSharedPreferenceChangeListener(subtitleAppearanceListener)
        closeWebDav(); port.close()
    }

    internal fun openExternalLocal(source: MediaDescriptor) {
        // An earlier asynchronous catalog lookup must not replace the user's newer file.
        catalogOpen?.cancel(); catalogOpen = null
        openLocal(listOf(source))
    }

    fun openLocal(sources: List<MediaDescriptor>, initialPosition: Long? = null) {
        if (sources.isEmpty()) return
        require(sources.all { it.id.startsWith("local:") })
        val disc = sources.firstOrNull { cn.coldlake.stellar.opensource.medialibrary.dvd.isDiscName(it.title) || cn.coldlake.stellar.opensource.medialibrary.dvd.isDiscName(it.id.removePrefix("local:").toUri().lastPathSegment.orEmpty()) }
        if (disc != null && appContext != null) {
            val media = disc
            loadDvd(appContext, media.title, initialPosition, PlaybackIdentity.locator(media.id)) {
                cn.coldlake.stellar.opensource.medialibrary.dvd.openLocalDvd(appContext, media.id.removePrefix("local:").toUri())
            }
            return
        }
        closeWebDav()
        smbSource = null; nfsSource = null
        openSources(sources, initialPosition)
    }

    fun openNetwork(address: String): Boolean {
        val source = networkMedia(address) ?: return false
        val location = source.id.removePrefix("network:")
        val path = java.net.URI(location).path.orEmpty()
        if (appContext != null && (cn.coldlake.stellar.opensource.medialibrary.dvd.isDiscName(path.trimEnd('/').substringAfterLast('/')) || path.endsWith('/'))) {
            loadDvd(appContext, source.title, 0) { cn.coldlake.stellar.opensource.medialibrary.dvd.openHttpDisc(appContext, location) }
            return true
        }
        closeWebDav()
        smbSource = null; nfsSource = null
        openSources(listOf(source))
        return true
    }

    fun openNfs(source: NfsSource) {
        closeWebDav()
        nfsSource = source; smbSource = null
        openSources(listOf(MediaDescriptor(source.id, source.title, MediaKind.VIDEO, null)))
    }

    fun openSmb(source: SmbSource) {
        closeWebDav()
        smbSource = source; nfsSource = null
        openSources(listOf(MediaDescriptor(source.id, source.title, MediaKind.VIDEO, null)))
    }

    private fun openSources(sources: List<MediaDescriptor>, initialPosition: Long? = null) {
        localMedia.clear(); localMedia.addAll(sources)
        queue.replace(sources); syncQueue()
        val media = sources.first()
        if (initialPosition != null) openQueued(media, initialPosition)
        else resumeOpen = viewModelScope.launch {
            val point = recordKey(media)?.let { reportingRuntime?.record(it) }?.takeIf { it.resumeEligible }?.positionMs ?: 0
            openQueued(media, point)
        }
    }

    internal fun openEmby(source: EmbyPlayback) {
        closeWebDav(); smbSource = null; nfsSource = null; embySource = source
        val episodes = source.takeIf { it.item.type == "Episode" && it.item.seriesId.isNotEmpty() }?.let(::EmbyEpisodeQueue)
        episodeQueue = episodes
        localMedia.clear(); localMedia.add(source.media)
        queue.replace(episodes?.media ?: listOf(source.media)); syncQueue()
        openQueued(source.media, initialPosition = source.resumeMs)
        if (episodes != null) episodeLoad = viewModelScope.launch {
            try {
                episodes.load {
                    if (episodeQueue === episodes) {
                        queue.replace(episodes.media.map { entry -> snapshot.value.media?.takeIf { it.id == entry.id } ?: entry })
                        syncQueue()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (episodeQueue === episodes) panelState.value = panels.value.copy(message = PanelMessage.EPISODES_UNAVAILABLE)
            }
        }
    }

    private fun openEpisode(media: MediaDescriptor, keepPanel: Boolean, play: Boolean) {
        val episodes = episodeQueue ?: return
        val ticket = ++episodeOpenGeneration
        episodeOpen?.cancel()
        panelState.value = panels.value.copy(queuePreparing = true, message = null)
        episodeOpen = viewModelScope.launch {
            var prepared: EmbyPlayback? = null
            try {
                prepared = episodes.prepare(media.id)
                if (ticket != episodeOpenGeneration || episodeQueue !== episodes || !episodes.contains(media.id)) return@launch
                finishReporting(FinishReason.REPLACED); embySource?.close()
                catalogVersion = null; savedState.remove<Long>("catalogVersion")
                // Keep the old stream alive until negotiation succeeds; failed selection preserves playback.
                embySource = prepared
                val next = prepared
                prepared = null
                localMedia.clear(); localMedia.add(next.media)
                openQueued(next.media, keepPanel = keepPanel, play = play)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == episodeOpenGeneration && episodeQueue === episodes)
                    panelState.value = panels.value.copy(message = PanelMessage.EPISODE_UNAVAILABLE)
            } finally {
                prepared?.close()
                if (ticket == episodeOpenGeneration) panelState.value = panels.value.copy(queuePreparing = false)
            }
        }
    }

    internal fun openRemote(context: android.content.Context, profile: ServerProfile, entry: RemoteEntry, initialPosition: Long? = null) {
        if (profile.kind.remote && cn.coldlake.stellar.opensource.medialibrary.dvd.isDiscName(entry.name)) {
            openDvd(context, profile, entry, initialPosition); return
        }
        if (entry.kind !in setOf(RemoteFileKind.VIDEO, RemoteFileKind.AUDIO)) return
        val kind = if (entry.kind == RemoteFileKind.AUDIO) MediaKind.AUDIO else MediaKind.VIDEO
        if (profile.kind.cloud) {
            openCloud(cn.coldlake.stellar.opensource.medialibrary.cloud.CloudPlayback(
                cn.coldlake.stellar.opensource.medialibrary.cloud.cloudProvider(context.applicationContext, profile),
                entry.location, if (profile.kind == ServerKind.ONEDRIVE) "onedrive" else "drive", profile.id), entry.name, kind, initialPosition)
            return
        }
        val source = when (profile.kind) {
            ServerKind.SMB -> profile.smbMedia(entry)?.let {
                closeWebDav(); smbSource = it; nfsSource = null
                MediaDescriptor(it.id, entry.name, kind, null)
            }
            ServerKind.NFS -> profile.nfsMedia(entry)?.let {
                closeWebDav(); nfsSource = it; smbSource = null
                MediaDescriptor(it.id, entry.name, kind, null)
            }
            ServerKind.WEBDAV -> {
                val playback = WebDavPlayback(WebDavClient.fromSettings(context, entry.location,
                    profile.username.ifEmpty { null }, profile.password.takeIf { profile.username.isNotEmpty() }), entry.name, kind == MediaKind.AUDIO)
                closeWebDav(); webDavSource = playback; smbSource = null; nfsSource = null
                MediaDescriptor(playback.id, entry.name, kind, null)
            }
            else -> null
        } ?: return
        recordKeys[source.id] = PlaybackIdentity.remote(profile, entry.location)
        openSources(listOf(source), initialPosition)
    }

    internal fun openCatalog(context: android.content.Context, card: cn.coldlake.stellar.opensource.medialibrary.catalog.CatalogCard, position: Long = 0) {
        catalogOpen?.cancel()
        catalogOpen = viewModelScope.launch {
            try {
                val previousSession = snapshot.value.sessionId
                if (cn.coldlake.stellar.opensource.medialibrary.catalog.CatalogServerLocator.isServer(card.uri)) {
                    openEmby(cn.coldlake.stellar.opensource.medialibrary.catalog.CatalogServerLocator.prepare(context, card.uri, position))
                } else if (!cn.coldlake.stellar.opensource.medialibrary.catalog.NasLocator.isNas(card.uri) && !cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(card.uri)) {
                    openLocal(listOf(MediaDescriptor("local:${card.uri}", card.title, MediaKind.VIDEO, null)), position)
                } else {
                val profile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cn.coldlake.stellar.opensource.medialibrary.servers.ServerDatabase(context).use { db ->
                        if (cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(card.uri))
                            cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.profile(card.uri, db.list())
                        else cn.coldlake.stellar.opensource.medialibrary.catalog.NasLocator.profile(card.uri, db.list())
                    }
                }
                if (cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(card.uri)) {
                    val ref = cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.ref(card.uri)
                    openCloud(cn.coldlake.stellar.opensource.medialibrary.cloud.CloudPlayback(
                        cn.coldlake.stellar.opensource.medialibrary.cloud.cloudProvider(context, profile, ref), ref.id,
                        if (profile.kind == ServerKind.ONEDRIVE) "onedrive" else "drive", profile.id), card.title, MediaKind.VIDEO, position)
                } else openRemote(context, profile, RemoteEntry(card.title,
                    cn.coldlake.stellar.opensource.medialibrary.catalog.NasLocator.path(card.uri), RemoteFileKind.VIDEO, null), position)
                }
                resumeOpen?.join()
                val openingDisc = dvdOpen
                openingDisc?.join()
                if (openingDisc?.isCancelled == true || (openingDisc != null && snapshot.value.sessionId == previousSession)) return@launch
                savedState["catalogVersion"] = card.id
                catalogVersion = card.id
                snapshot.value.media?.let { media -> recordKey(media)?.let { reportingRuntime?.bindVersion(card.id, it) } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                android.widget.Toast.makeText(context, cn.coldlake.stellar.opensource.R.string.scan_source_unavailable, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun openCloud(source: cn.coldlake.stellar.opensource.medialibrary.cloud.CloudPlayback, title: String, kind: MediaKind, initialPosition: Long? = null) {
        closeWebDav(); cloudSource = source; smbSource = null; nfsSource = null
        resumeOpen = viewModelScope.launch {
            source.recordLocator()?.let { PlaybackIdentity.locator(it)?.let { key -> recordKeys[source.id] = key } }
            if (cloudSource === source) openSources(listOf(MediaDescriptor(source.id, title, kind, null)), initialPosition)
        }
    }
    internal fun attachCatalog(context: android.content.Context) {
        if (catalogVersion != null) return
        val version = savedState.get<Long>("catalogVersion") ?: return
        val media = snapshot.value.media ?: return
        catalogVersion = version
        recordKey(media)?.let { key -> viewModelScope.launch { PlaybackReportingRuntime.get(context).bindVersion(version, key) } }
    }

    fun open(media: MediaDescriptor) {
        if (port.casting && snapshot.value.media?.id == media.id) { showCastingControls(); return }
        closeWebDav()
        smbSource = null; nfsSource = null
        queue.replace(listOf(media))
        syncQueue()
        openQueued(media, initialPosition = 83_000L)
    }

    private fun openQueued(media: MediaDescriptor, initialPosition: Long = 0L, keepPanel: Boolean = false, play: Boolean = true) {
        if (!media.discNavigation && discNavigation.value.active) discMenuPlayback.close()
        if (episodeQueue?.contains(media.id) == true && embySource?.id != media.id) {
            openEpisode(media, keepPanel, play); return
        }
        val previousPanel = panelState.value.active.takeIf { keepPanel }
        interactions.interrupt(true)
        tools.reset(); syncTools()
        openPlayback(media, if (isRealMedia(media.id)) PlaybackRestore(initialPosition,
            audioId = embySource?.initialAudioId, restoreSubtitleSelection = false) else null)
        val s = snapshot.value
        if (s.simulated && s.canSeek) port.dispatch(PlaybackCommand.Seek(s.sessionId, initialPosition.coerceAtMost((media.durationMs ?: Long.MAX_VALUE) / 2)))
        if (play) port.dispatch(PlaybackCommand.Play(s.sessionId))
        navigation.show(AppRoute.PLAYER)
        state.value = state.value.copy(notice = null, feature = null)
        interactions.mediaChanged()
        interactions.setEnabled(true)
        panelState.value = panelState.value.copy(active = previousPanel, delayMs = 0, message = null)
        interactions.panel(previousPanel != null)
        checkpoint()
    }

    internal fun setAudioGain(session: Long, decibels: Float): Boolean =
        port.dispatch(PlaybackCommand.SetAudioGain(session, decibels)) is CommandResult.Accepted

    fun command(command: PlaybackCommand) {
        // An explicit command ends the previous gesture before applying the user's new intent.
        if (command is PlaybackCommand.InSession && command.sessionId != snapshot.value.sessionId) return
        interactions.interrupt()
        val result = port.dispatch(command)
        interactions.tick()
        state.value = state.value.copy(notice = (result as? CommandResult.Rejected)?.reason)
        checkpoint()
    }

    fun back() {
        if (panels.value.active != null) { panelBack(); return }
        interactions.interrupt(true)
        if (port.casting && navigation.current == AppRoute.PLAYER) {
            navigation.show(AppRoute.LIBRARY)
            state.value = state.value.copy(feature = null, notice = null)
            interactions.setEnabled(false)
            checkpoint()
            return
        }
        when (navigation.current) {
            AppRoute.PLAYER -> {
                discMenuPlayback.close()
                episodeOpenGeneration++; episodeOpen?.cancel(); episodeOpen = null
                finishReporting(); catalogVersion = null; savedState.remove<Long>("catalogVersion")
                tools.closeMini(); syncTools()
                port.dispatch(if (snapshot.value.simulated) PlaybackCommand.Pause(snapshot.value.sessionId)
                    else PlaybackCommand.Close(snapshot.value.sessionId))
                navigation.pop()
                state.value = state.value.copy(notice = null)
                if (smbSource != null || nfsSource != null || webDavSource != null || embySource != null || cloudSource != null) {
                    closeWebDav()
                    smbSource = null; nfsSource = null
                    localMedia.clear(); queue.replace(emptyList()); syncQueue()
                }
            }
            AppRoute.LIBRARY -> navigation.selectHome(HomeTab.MEDIA)
        }
        interactions.setEnabled(navigation.current == AppRoute.PLAYER)
        checkpoint()
    }


    fun selectHomeTab(tab: HomeTab) {
        if (navigation.current != AppRoute.LIBRARY) return
        closeFeature()
        interactions.setEnabled(false)
        navigation.selectHome(tab)
        state.value = state.value.copy(notice = null)
        checkpoint()
    }

    fun feature(feature: PlayerFeature) {
        if (feature == PlayerFeature.REPEAT) {
            setRepeat(RepeatMode.entries[(panels.value.repeat.ordinal + 1) % RepeatMode.entries.size])
            interactions.show()
            return
        }
        val panel = when (feature) {
            PlayerFeature.PLAYLIST -> CorePanel.PLAYLIST
            PlayerFeature.SUBTITLES -> CorePanel.SUBTITLES
            PlayerFeature.SPEED -> CorePanel.SPEED
            PlayerFeature.AUDIO -> CorePanel.AUDIO
            PlayerFeature.MORE -> CorePanel.MORE
            PlayerFeature.CAST -> CorePanel.CAST
            PlayerFeature.SNAPSHOT -> CorePanel.SNAPSHOT
            else -> null
        }
        if (panel != null) { showPanel(panel); return }
        dismissPanel()
        if (feature == PlayerFeature.LOCK) interactions.toggleLock()
        else { interactions.panel(true); state.value = state.value.copy(feature = feature) }
        checkpoint()
    }
    fun closeFeature() { state.value = state.value.copy(feature = null); dismissPanel() }
    fun showCastingControls() {
        if (!port.casting) return
        navigation.show(AppRoute.PLAYER)
        state.value = state.value.copy(feature = null, notice = null)
        interactions.interrupt(true)
    }
    fun configurationChanged() { closeFeature(); interactions.interrupt(true); checkpoint() }
    fun saveCommittedState() { interactions.interrupt(); checkpoint() }
    fun adjacent(offset: Int) {
        val target = if (episodeQueue != null) {
            val index = queue.items.indexOfFirst { it.id == snapshot.value.media?.id }
            if (index < 0) null else queue.items.getOrNull(index + offset)
        } else queue.target(snapshot.value.media?.id, offset)
        target?.let { openQueued(it) }
    }
    override fun adjacentMedia(offset: Int) = adjacent(offset)



    fun foreground(active: Boolean) {
        foreground.value = active
        interactions.setEnabled(active && navigation.current == AppRoute.PLAYER)
        if (!active) {
            episodeOpenGeneration++; episodeOpen?.cancel(); episodeOpen = null
            panelState.value = panels.value.copy(queuePreparing = false)
            closeFeature()
            if (!port.casting) port.dispatch(PlaybackCommand.Pause(snapshot.value.sessionId))
            checkpoint()
        }
    }

    private fun checkpoint() {
        val s = snapshotForRestore()
        savedState["localMedia"] = ArrayList(localMedia.filterNot { it.id.startsWith("bluray-session:") || it.id.startsWith("dvd-session:") || it.id.startsWith("smb-session:") || it.id.startsWith("nfs-session:") || it.id.startsWith("webdav-session:") || it.id.startsWith("emby-session:") || it.id.startsWith("drive-session:") || it.id.startsWith("onedrive-session:") }
            .flatMap { listOf(it.id, it.title, it.kind.name) })
        val restoring = !s.simulated && s.status == PlaybackStatus.BUFFERING && s.mediaInfo == null &&
            savedState.get<String>("mediaId") == s.media?.id
        savedState["mediaId"] = s.media?.id
        savedState["session"] = s.sessionId
        // Preparation is asynchronous. Do not replace an existing restore point
        // with the adapter's temporary zero/default values before metadata arrives.
        if (!restoring) {
        savedState["position"] = s.framePositionMs ?: s.positionMs
        savedState["speed"] = if (port.casting) s.speed else interactions.committedSpeed
        savedState["subtitle"] = s.selectedSubtitleId
        savedState["secondarySubtitle"] = s.selectedSecondarySubtitleId
        savedState["externalSubtitle"] = s.externalSubtitle?.let { arrayListOf(it.id, it.label, it.path) }
        if (!s.simulated) savedState["subtitleDelay"] = s.subtitleDelayMs
        savedState["audio"] = s.selectedAudioId
        savedState["video"] = s.selectedVideoId
        }
        // In-flight visual transformations are rolled back before Android saves the task.
        savedState["scale"] = interactions.state.value.scale
        savedState["brightness"] = interactions.simulatedBrightness
        savedState["volume"] = interactions.simulatedVolume
        savedState["queue"] = ArrayList(queue.items.map { it.id })
        savedState["repeat"] = queue.mode.name
        savedState["randomState"] = queue.randomState
        savedState["subtitleStyle"] = saveStyle(panels.value.style)
        savedState["customStyle"] = saveStyle(customStyle)
        savedState["subtitleDelay"] = if (s.simulated) panels.value.delayMs else s.subtitleDelayMs
        savedState["tools"] = saveTools(tools.state)
        savedState["bookmarks"] = ArrayList(tools.state.bookmarks.flatMap { listOf(it.id.toString(), it.positionMs.toString(), it.title) })
    }

    /** The periodic clock needs scalar progress only; structural saves remain event-driven. */
    private fun checkpointPlayback() {
        val s = snapshotForRestore()
        if (!s.simulated && s.status == PlaybackStatus.BUFFERING && s.mediaInfo == null) return
        saveIfChanged("position", s.framePositionMs ?: s.positionMs)
        saveIfChanged("speed", if (port.casting) s.speed else interactions.committedSpeed)
        saveIfChanged("subtitle", s.selectedSubtitleId)
        saveIfChanged("secondarySubtitle", s.selectedSecondarySubtitleId)
        saveIfChanged("externalSubtitle", s.externalSubtitle?.let { arrayListOf(it.id, it.label, it.path) })
        if (!s.simulated) saveIfChanged("subtitleDelay", s.subtitleDelayMs)
        saveIfChanged("audio", s.selectedAudioId)
        saveIfChanged("video", s.selectedVideoId)
    }

    private fun <T> saveIfChanged(key: String, value: T) {
        if (savedState.get<T>(key) != value) savedState[key] = value
    }

    private fun snapshotForRestore(): PlaybackSnapshot = if (port.casting)
        port.localSnapshot.copy(positionMs = snapshot.value.positionMs, framePositionMs = null) else snapshot.value

    private fun syncQueue() { panelState.value = panels.value.copy(queue = queue.items, repeat = queue.mode, episodeQueue = episodeQueue != null) }
    private fun advanceQueueAtEnd(before: PlaybackStatus) {
        if (port.casting) return // Remote queues need receiver-specific loading; never start the next item locally.
        if (embySource != null && episodeQueue == null) return
        if (before != PlaybackStatus.PLAYING || snapshot.value.status != PlaybackStatus.ENDED) return
        val next = (if (episodeQueue != null && queue.mode != RepeatMode.ONE) {
            val index = queue.items.indexOfFirst { it.id == snapshot.value.media?.id }
            if (index < 0) null else queue.items.getOrNull(index + 1)
        } else queue.target(snapshot.value.media?.id, 1, automatic = true)) ?: return
        if (next.id == snapshot.value.media?.id) port.dispatch(PlaybackCommand.Play(snapshot.value.sessionId))
        else openQueued(next, keepPanel = true)
    }
    private var portraitPanelParent: CorePanel? = null

    fun showPortraitSubpanel(panel: CorePanel) {
        portraitPanelParent = when (panel) {
            CorePanel.SUBTITLE_STYLE, CorePanel.SUBTITLE_DELAY, CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH -> CorePanel.AUDIO_SUBTITLES
            CorePanel.CUSTOM_SPEED -> CorePanel.PLAYBACK
            CorePanel.TIMER, CorePanel.EFFECT, CorePanel.INFO, CorePanel.FRAME,
                CorePanel.SYNC, CorePanel.SHARE, CorePanel.CONTROLS, CorePanel.TOOLS -> CorePanel.MORE
            else -> null
        }
        showPanel(panel)
    }

    internal fun openDiscFolder(context: android.content.Context, uri: android.net.Uri) {
        loadDvd(context, context.getString(cn.coldlake.stellar.opensource.R.string.disc_folder_title), null, PlaybackIdentity.locator(uri.toString())) {
            cn.coldlake.stellar.opensource.medialibrary.dvd.openLocalDisc(context, uri, tree = true)
        }
    }

    private fun openDvd(context: android.content.Context, profile: ServerProfile, entry: RemoteEntry, position: Long?) {
        loadDvd(context, entry.name, position, PlaybackIdentity.remote(profile, entry.location)) { cn.coldlake.stellar.opensource.medialibrary.dvd.openRemoteDisc(context, profile, entry) }
    }
    private fun loadDvd(context: android.content.Context, name: String, position: Long?, baseKey: PlaybackRecordKey? = null,
        load: suspend () -> com.stellar.player.disc.DiscSource) {
        cancelDvdOpen()
        val generation = dvdGeneration
        dvdOpen = viewModelScope.launch {
            dvdLoadingState.value = true
            try {
                val source = load()
                if (generation != dvdGeneration) { source.close(); return@launch }
                dvdOpen = null
                closeWebDav(); smbSource = null; nfsSource = null; dvdSource = source
                discMenuPlayback.available(true)
                val id = java.util.UUID.randomUUID().toString()
                val items = source.titles.sortedByDescending { it.durationMs }.map { title ->
                    val mediaId = "${if (source.bluray) "bluray-session" else "dvd-session"}:$id/${title.number}"
                    dvdAddresses[mediaId] = source.address(title.number)
                    baseKey?.let { recordKeys[mediaId] = PlaybackIdentity.discTitle(it, title.number.toString()) }
                    MediaDescriptor(mediaId, context.getString(if (source.bluray) cn.coldlake.stellar.opensource.R.string.bluray_title_name else cn.coldlake.stellar.opensource.R.string.dvd_title_name, name, title.number),
                        MediaKind.VIDEO, title.durationMs, chapters = title.chapters.mapIndexed { index, ms -> MediaChapter(index + 1, ms) })
                }
                openSources(items, position)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is com.stellar.player.disc.EncryptedDiscException -> cn.coldlake.stellar.opensource.R.string.disc_encrypted
                    is cn.coldlake.stellar.opensource.medialibrary.dvd.DiscFolderPermissionException -> cn.coldlake.stellar.opensource.R.string.disc_folder_permission
                    else -> cn.coldlake.stellar.opensource.R.string.dvd_open_failed
                }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
            finally { if (generation == dvdGeneration || dvdOpen == null) dvdLoadingState.value = false }
        }
    }
    fun cancelDvdOpen() { dvdGeneration++; dvdOpen?.cancel(); dvdOpen = null; dvdLoadingState.value = false }
    override fun selectChapter(number: Int) {
        val s = snapshot.value
        s.media?.chapters?.firstOrNull { it.number == number }?.let {
            if (s.canSeek) command(PlaybackCommand.Seek(s.sessionId, it.positionMs))
        }
    }

    override fun showPanel(panel: CorePanel) {
        if (panel == CorePanel.CAST && !snapshot.value.simulated) {
            dismissPanel(); casting?.openPanel(); interactions.panel(true); return
        }
        if (panel == CorePanel.BOOKMARKS) return // Temporarily withdrawn; keep saved bookmarks for future restoration.
        if (panel == portraitPanelParent ||
            (portraitPanelParent == CorePanel.PLAYBACK && panel == CorePanel.SPEED) ||
            (portraitPanelParent == CorePanel.AUDIO_SUBTITLES && panel == CorePanel.SUBTITLES)) {
            dismissPanel(); return
        }
        if (!snapshot.value.simulated && panel in setOf(CorePanel.SUBTITLE_STYLE, CorePanel.SUBTITLE_DELAY,
                CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH, CorePanel.EFFECT, CorePanel.SYNC,
                CorePanel.CAST, CorePanel.SHARE, CorePanel.SNAPSHOT)) {
            tools.message(ToolMessage.UNAVAILABLE); syncTools()
            panelState.value = panels.value.copy(active = panel)
            interactions.panel(true)
            return
        }
        if (panel == CorePanel.CUSTOM_SPEED) {
            speedRejection(snapshot.value.speed, true)?.let { panelState.value = panels.value.copy(message = it); return }
        }
        state.value = state.value.copy(feature = null)
        tools.message(null); syncTools()
        interactions.panel(true)
        if (panel == CorePanel.FRAME && snapshot.value.media != null) {
            port.dispatch(PlaybackCommand.Pause(snapshot.value.sessionId))
        }
        panelState.value = panels.value.copy(active = panel, message = null)
        checkpoint()
    }
    override fun dismissPanel() {
        portraitPanelParent = null
        panelState.value = panels.value.copy(active = null, message = null)
        interactions.panel(false)
        checkpoint()
    }
    override fun panelBack() {
        when (panels.value.active) {
            CorePanel.SUBTITLE_STYLE, CorePanel.SUBTITLE_DELAY, CorePanel.SUBTITLE_IMPORT, CorePanel.SUBTITLE_SEARCH -> showPanel(CorePanel.SUBTITLES)
            CorePanel.CUSTOM_SPEED -> showPanel(CorePanel.SPEED)
            CorePanel.TIMER, CorePanel.EFFECT, CorePanel.INFO, CorePanel.SYNC, CorePanel.SHARE, CorePanel.CONTROLS, CorePanel.TOOLS -> showPanel(CorePanel.MORE)
            else -> dismissPanel()
        }
    }
    override fun selectMedia(id: String) {
        queue.items.firstOrNull { it.id == id }?.let {
            if (snapshot.value.media?.id != id) openQueued(it, keepPanel = true)
            else if (episodeQueue != null) {
                episodeOpenGeneration++; episodeOpen?.cancel(); episodeOpen = null
                panelState.value = panels.value.copy(queuePreparing = false)
            }
        }
    }
    override fun removeMedia(id: String) {
        episodeQueue?.remove(id)
        val current = snapshot.value
        val next = queue.remove(id, current.media?.id)
        syncQueue()
        if (current.media?.id == id) {
            if (next != null) openQueued(next, keepPanel = true, play = current.status == PlaybackStatus.PLAYING)
            else clearQueue()
        }
        checkpoint()
    }
    override fun clearQueue() {
        interactions.interrupt(true)
        tools.reset(); syncTools()
        closeWebDav()
        port.dispatch(PlaybackCommand.Close(snapshot.value.sessionId))
        queue.replace(emptyList()); syncQueue()
        panelState.value = panels.value.copy(delayMs = 0, message = null)
        interactions.mediaChanged(); interactions.panel(panels.value.active != null)
        checkpoint()
    }
    override fun setRepeat(mode: RepeatMode) { queue.mode = mode; syncQueue(); checkpoint() }
    override fun selectTrack(kind: TrackKind, id: String?) {
        command(PlaybackCommand.SelectTrack(snapshot.value.sessionId, kind, id))
        panelState.value = panels.value.copy(message = if (state.value.notice == RejectionReason.TRACK_UNAVAILABLE) PanelMessage.TRACK_UNAVAILABLE else null)
    }
    override fun selectSecondarySubtitle(id: String?) {
        command(PlaybackCommand.SelectSecondarySubtitle(snapshot.value.sessionId, id))
        panelState.value = panels.value.copy(message = if (state.value.notice == RejectionReason.TRACK_UNAVAILABLE) PanelMessage.TRACK_UNAVAILABLE else null)
    }
    internal suspend fun loadExternalSubtitle(session: Long, subtitle: ExternalSubtitle): Boolean {
        val accepted = if (port.casting) casting?.loadExternalSubtitle(session, subtitle) == true else port.loadExternalSubtitle(session, subtitle)
        if (accepted) checkpoint()
        return accepted
    }
    override fun importSubtitle(id: String) = Unit


    override fun setSubtitleStyle(style: SubtitleStyle) {
        if (!snapshot.value.capabilities.canStyleSubtitle) return
        val bounded = style.copy(preset = SubtitlePreset.CUSTOM, size = style.size.coerceIn(17, 24),
            opacity = style.opacity.coerceIn(0f, 1f), outlineOpacity = style.outlineOpacity.coerceIn(0f, 1f),
            shadow = style.shadow.coerceIn(-3f, 3f), bottom = style.bottom.coerceIn(0, 255))
        customStyle = bounded
        panelState.value = panels.value.copy(style = bounded); checkpoint()
    }
    override fun setSubtitlePreset(preset: SubtitlePreset) {
        if (!snapshot.value.capabilities.canStyleSubtitle) return
        panelState.value = panels.value.copy(style = if (preset == SubtitlePreset.CUSTOM) customStyle else SubtitleStyle.preset(preset)); checkpoint()
    }
    override fun setSubtitleDelay(delayMs: Int) {
        if (!snapshot.value.capabilities.canDelaySubtitle) return
        val bounded = delayMs.coerceIn(-60_000, 60_000)
        if (!snapshot.value.simulated && port.dispatch(PlaybackCommand.SetSubtitleDelay(snapshot.value.sessionId, bounded)) !is CommandResult.Accepted) return
        panelState.value = panels.value.copy(delayMs = bounded); checkpoint()
    }
    private fun speedRejection(speed: Float, custom: Boolean): PanelMessage? = speedRejection(snapshot.value, speed, custom)
    override fun setSpeed(speed: Float, custom: Boolean) {
        val error = speedRejection(speed, custom)
        panelState.value = panels.value.copy(message = error)
        if (error == null) command(PlaybackCommand.SetSpeed(snapshot.value.sessionId, speed))
        checkpoint()
    }


    private fun syncTools() {
        val options = interactionOptions
        if (options != null) tools.interactionOptions(options, hidePausedControls)
        panelState.value = panels.value.copy(tools = tools.state)
        interactions.configureSeek(tools.state.dragByTime, tools.state.dragSeconds, tools.state.dragPercent)
        interactions.configureControls(tools.state)
        if (options != null) interactions.configureInteraction(options)
    }
    override fun tool(event: ToolEvent) {
        interactions.interrupt()
        val s = snapshot.value
        if (!s.simulated && event == ToolEvent.Hardware) {
            val result = port.dispatch(PlaybackCommand.SetHardwareDecoding(s.sessionId, !s.hardwareDecoding))
            tools.message(if (result is CommandResult.Accepted) null else ToolMessage.UNAVAILABLE)
            syncTools()
            return
        }
        if (!s.simulated && (event is ToolEvent.Effect || event is ToolEvent.Sync ||
                event is ToolEvent.Cast || event == ToolEvent.Mini || event == ToolEvent.Background)) {
            tools.message(ToolMessage.UNAVAILABLE); syncTools(); return
        }
        tools.apply(event, s)
        if (event is ToolEvent.DoubleTapSeek || event is ToolEvent.DoubleTapPlay || event is ToolEvent.HideWhenPaused) {
            controlsPreferences?.edit()?.putBoolean("doubleTapSeek", tools.state.doubleTapSeek)
                ?.putBoolean("doubleTapPlay", tools.state.doubleTapPlay)?.putBoolean("hideWhenPaused", tools.state.hideWhenPaused)?.apply()
        }
        if (event is ToolEvent.SeekButton) interactionSettings?.save("forward_seconds", tools.state.seekSeconds.toFloat())
        if (event is ToolEvent.RewindButton) interactionSettings?.save("rewind_seconds", tools.state.rewindSeconds.toFloat())
        when (event) {
            is ToolEvent.Frame -> command(PlaybackCommand.StepFrame(s.sessionId, event.direction))
            ToolEvent.ResumeFrames -> if (port.dispatch(PlaybackCommand.Play(s.sessionId)) == CommandResult.Accepted) dismissPanel()
            is ToolEvent.GoBookmark -> tools.state.bookmarks.firstOrNull { it.id == event.id }?.let {
                if (s.canSeek) { command(PlaybackCommand.Seek(s.sessionId, it.positionMs)); dismissPanel() }
                else tools.message(ToolMessage.UNAVAILABLE)
            }
            ToolEvent.Mini -> if (tools.state.mini) {
                dismissPanel(); navigation.show(AppRoute.LIBRARY); interactions.setEnabled(false)
            }
            else -> Unit
        }
        syncTools(); checkpoint()
    }
    fun restoreMini() {
        tools.closeMini(); syncTools(); navigation.show(AppRoute.PLAYER)
        interactions.setEnabled(true); interactions.show(); checkpoint()
    }
    fun closeMini() { tools.closeMini(); syncTools(); command(PlaybackCommand.Pause(snapshot.value.sessionId)) }

    fun retry() { snapshot.value.media?.let { openQueued(it) } }

    /** Explicit user-approved recovery after the system folder picker replaces the video Surface. */
    internal fun reopenAfterScreenshotFolder(expectedSession: Long, pausedPositionMs: Long? = null): Long? {
        if (snapshot.value.sessionId != expectedSession || snapshot.value.simulated || navigation.current != AppRoute.PLAYER) return null
        interactions.interrupt()
        val previous = snapshot.value
        val media = previous.media ?: return null
        openPlayback(media, PlaybackRestore(pausedPositionMs ?: previous.framePositionMs ?: previous.positionMs,
            previous.speed, previous.selectedSubtitleId, previous.selectedAudioId, previous.selectedVideoId,
            previous.subtitleDelayMs, previous.externalSubtitle, secondarySubtitleId = previous.selectedSecondarySubtitleId))
        val session = snapshot.value.sessionId
        if (previous.status == PlaybackStatus.PLAYING || previous.status == PlaybackStatus.BUFFERING)
            port.dispatch(PlaybackCommand.Play(session))
        if (previous.audioGainDb != 0f) viewModelScope.launch {
            val ready = snapshot.first { it.sessionId != session || it.status != PlaybackStatus.BUFFERING }
            if (ready.sessionId == session && ready.capabilities.canSetAudioGain) setAudioGain(session, previous.audioGainDb)
        }
        checkpoint()
        return session
    }

}

private fun saveTools(t: PlayerToolsState) = arrayListOf(t.remainingMs?.toString().orEmpty(), t.timerMinutes.toString(), t.effect.name,
    t.nextBookmarkId.toString(), t.syncMs.toString(), t.hardware.toString(), t.background.toString(), t.castDevice.orEmpty(), t.fit.name,
    t.flipHorizontal.toString(), t.flipVertical.toString(), t.seekSeconds.toString(), t.dragByTime.toString(), t.dragSeconds.toString(), t.dragPercent.toString(),
    t.doubleTapSeek.toString(), t.doubleTapPlay.toString(), t.hideWhenPaused.toString(), t.rewindSeconds.toString())
private fun restoreTools(v: ArrayList<String>?, bookmarks: ArrayList<String>?): PlayerToolsState = runCatching {
    requireNotNull(v)
    PlayerToolsState(remainingMs = v[0].toLongOrNull(), timerMinutes = v[1].toInt(), effect = AudioEffect.valueOf(v[2]),
        bookmarks = bookmarks.orEmpty().chunked(3).map { PlaybackBookmark(it[0].toLong(), it[1].toLong(), it[2]) }, nextBookmarkId = v[3].toLong(),
        syncMs = v[4].toInt(), hardware = v[5].toBoolean(), background = v[6].toBoolean(), castDevice = v[7].ifEmpty { null }, fit = ScreenFit.valueOf(v[8]),
        flipHorizontal = v[9].toBoolean(), flipVertical = v[10].toBoolean(), seekSeconds = v[11].toInt(), dragByTime = v[12].toBoolean(),
        dragSeconds = v[13].toInt(), dragPercent = v[14].toInt(),
        doubleTapSeek = v.getOrNull(15)?.toBoolean() ?: false, doubleTapPlay = v.getOrNull(16)?.toBoolean() ?: true,
        hideWhenPaused = v.getOrNull(17)?.toBoolean() ?: true, rewindSeconds = v.getOrNull(18)?.toInt() ?: 10)
}.getOrDefault(PlayerToolsState())

internal fun speedRejection(snapshot: PlaybackSnapshot, speed: Float, custom: Boolean): PanelMessage? {
    val caps = snapshot.capabilities
    if (!caps.canSetSpeed || snapshot.media?.kind != MediaKind.VIDEO || (snapshot.durationMs ?: 0) <= 0) return PanelMessage.SPEED_UNAVAILABLE
    if (!speed.isFinite() || speed !in maxOf(0.5f, caps.minSpeed)..minOf(8f, caps.maxSpeed) || kotlin.math.abs(speed * 10 - kotlin.math.round(speed * 10)) > 0.001f) return PanelMessage.INVALID_SPEED

    return null
}
private fun saveStyle(s: SubtitleStyle) = arrayListOf(s.preset.name, s.size.toString(), s.bold.toString(), s.color.name,
    s.opacity.toString(), s.outline.name, s.outlineOpacity.toString(), s.shadow.toString(), s.bottom.toString())
private fun restoreStyle(values: ArrayList<String>?): SubtitleStyle = runCatching {
    requireNotNull(values)
    SubtitleStyle(SubtitlePreset.valueOf(values[0]), values[1].toInt(), values[2].toBoolean(), SubtitleColor.valueOf(values[3]),
        values[4].toFloat(), SubtitleColor.valueOf(values[5]), values[6].toFloat(), values[7].toFloat(), values[8].toInt())
}.getOrDefault(SubtitleStyle())
