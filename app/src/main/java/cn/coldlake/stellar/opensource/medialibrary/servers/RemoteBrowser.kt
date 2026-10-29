package cn.coldlake.stellar.opensource.medialibrary.servers

import android.content.Context
import cn.coldlake.stellar.opensource.navigation.MediaDestination
import cn.coldlake.stellar.opensource.navigation.MediaNavigation
import cn.coldlake.stellar.opensource.medialibrary.ScrollPosition
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavFailure
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavError
import com.stellar.player.nativeio.NativeDirectoryProbe
import com.stellar.player.source.SmbSource
import com.stellar.player.source.NfsSource
import com.stellar.player.source.NfsVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale

internal enum class RemoteFileKind { FOLDER, VIDEO, AUDIO, OTHER }
internal fun remoteFileKind(name: String, directory: Boolean): RemoteFileKind {
    if (directory) return RemoteFileKind.FOLDER
    if (name.equals("VIDEO_TS.IFO", true) || name.equals("index.bdmv", true)) return RemoteFileKind.VIDEO
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "iso", "mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "wmv", "flv", "f4v", "3gp", "3g2", "ogv", "rm", "rmvb", "asf" -> RemoteFileKind.VIDEO
        "mp3", "flac", "aac", "m4a", "m4b", "m4r", "wav", "ogg", "opus", "wma", "ape", "aiff", "aif", "alac", "ac3", "eac3", "dts", "dsf", "dff", "mp2", "amr", "mmf", "wv" -> RemoteFileKind.AUDIO
        else -> RemoteFileKind.OTHER
    }
}
internal data class RemoteEntry(val name: String, val location: String, val kind: RemoteFileKind, val size: Long?)
internal data class RemoteFolder(val location: String, val title: String, val entries: List<RemoteEntry> = emptyList(),
    val loaded: Boolean = false, val loadedAtNanos: Long = 0L, val scroll: ScrollPosition = ScrollPosition()) {
    val index get() = scroll.index
    val offset get() = scroll.offset
}
internal data class BrowserState(val profile: ServerProfile? = null, val folder: RemoteFolder? = null,
    val path: List<String> = emptyList(),
    val loading: Boolean = false, val error: ServerError? = null, val unsupported: Boolean = false)
internal class DirectoryFailure(val reason: ServerError) : Exception(reason.name)
internal fun interface RemoteDirectory { suspend fun list(profile: ServerProfile, location: String): List<RemoteEntry> }

internal class ProtocolDirectory(private val context: Context) : RemoteDirectory {
    override suspend fun list(profile: ServerProfile, location: String): List<RemoteEntry> = try {
        withTimeout(20_000) { withContext(Dispatchers.Default) {
            when (profile.kind) {
                ServerKind.ONEDRIVE, ServerKind.GOOGLE_DRIVE -> cn.coldlake.stellar.opensource.medialibrary.cloud.cloudProvider(context, profile).use {
                    cn.coldlake.stellar.opensource.medialibrary.cloud.browseCloud(it, location)
                }
                ServerKind.WEBDAV -> WebDavClient.fromSettings(context, location, profile.username.ifEmpty { null },
                    profile.password.takeIf { profile.username.isNotEmpty() }).use { client ->
                    client.listDirectory().filter { validRemoteName(it.name) }.map {
                        RemoteEntry(it.name, it.address, remoteFileKind(it.name, it.isDirectory), it.sizeBytes)
                    }
                }
                ServerKind.SMB, ServerKind.NFS -> {
                    val result = if (profile.kind == ServerKind.SMB) NativeDirectoryProbe.listSmb(
                        profile.hostPort(), profile.share, location, profile.username, profile.password, profile.domain)
                    else NativeDirectoryProbe.listNfs(profile.address, profile.path, "/" + location.trimStart('/'), profile.nfsVersion,
                        profile.port.toInt(), profile.uid.toIntOrNull(), profile.gid.toIntOrNull())
                    if (result.result != NativeDirectoryProbe.Result.OK) throw DirectoryFailure(when (result.result) {
                        NativeDirectoryProbe.Result.AUTHENTICATION -> ServerError.AUTHENTICATION
                        NativeDirectoryProbe.Result.PATH -> ServerError.PATH
                        NativeDirectoryProbe.Result.PERMISSION -> ServerError.PERMISSION
                        NativeDirectoryProbe.Result.TIMEOUT -> ServerError.TIMEOUT
                        NativeDirectoryProbe.Result.LIMIT -> ServerError.LIMIT
                        NativeDirectoryProbe.Result.CANCELLED -> throw CancellationException()
                        else -> ServerError.NETWORK
                    })
                    result.entries.filter { validRemoteName(it.name) && (it.directory || it.regular) }.map {
                        RemoteEntry(it.name, listOf(location.trimEnd('/'), it.name).filter(String::isNotEmpty).joinToString("/"),
                            remoteFileKind(it.name, it.directory), it.size)
                    }
                }
                else -> throw DirectoryFailure(ServerError.PROTOCOL)
            }.map { it to it.name.lowercase(Locale.ROOT) }
                .sortedWith(compareBy<Pair<RemoteEntry, String>> { it.first.kind != RemoteFileKind.FOLDER }
                    .thenBy { it.second }.thenBy { it.first.name })
                .map { it.first }
        } }
    } catch (_: TimeoutCancellationException) { throw DirectoryFailure(ServerError.TIMEOUT)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: WebDavFailure) { throw DirectoryFailure(when (failure.reason) {
        WebDavError.AUTHENTICATION -> ServerError.AUTHENTICATION
        WebDavError.FORBIDDEN -> ServerError.PERMISSION
        WebDavError.NOT_FOUND, WebDavError.NOT_COLLECTION -> ServerError.PATH
        WebDavError.TLS -> ServerError.TLS
        WebDavError.TIMEOUT -> ServerError.TIMEOUT
        WebDavError.LIMIT -> ServerError.LIMIT
        WebDavError.NETWORK -> ServerError.NETWORK
        else -> ServerError.PROTOCOL
    }) }
}
internal fun validRemoteName(name: String) = name.isNotEmpty() && name !in listOf(".", "..") &&
    name.none { it.isISOControl() || it == '/' || it == '\\' || it == '\uFFFD' }
internal fun ServerProfile.hostPort() = "${if (':' in address) "[$address]" else address}:$port"
internal fun ServerProfile.smbMedia(entry: RemoteEntry) = SmbSource.parse(
    URI("smb", null, address, port.toInt(), "/$share/${entry.location}", null, null).toASCIIString(), username, password, domain)
internal fun ServerProfile.nfsMedia(entry: RemoteEntry) = NfsSource.parse(hostPort(), path, entry.location.trimStart('/'),
    if (nfsVersion == 4) NfsVersion.V4 else NfsVersion.V3, uid, gid)

/** NavBackStack is the only directory history. Flows below are bounded presentation state. */
internal class RemoteBrowserController(private val scope: CoroutineScope, private val directory: RemoteDirectory,
    val navigation: MediaNavigation) {
    constructor(scope: CoroutineScope, directory: RemoteDirectory) : this(scope, directory, MediaNavigation())
    private val mutable = MutableStateFlow(BrowserState())
    val state = mutable.asStateFlow()
    private val pages = linkedMapOf<MediaDestination.Remote, MutableStateFlow<BrowserState>>()
    private var generation = 0L
    private var request: Job? = null
    private val cache get() = cn.coldlake.stellar.opensource.cache.AppCaches.current?.data
    private var cacheSession = java.util.UUID.randomUUID().toString()
    private val current get() = navigation.current as? MediaDestination.Remote
    init { cache?.let { data -> scope.launch {
        data.cleared.collect { event ->
            if (event.sequence > 0 && event.namespace == null && state.value.profile != null) {
                // Visible rows may stay painted; ancestors must no longer reuse invalidated data.
                pages.filterKeys { it != current }.values.forEach { flow ->
                    flow.value = flow.value.copy(folder = flow.value.folder?.let {
                        it.copy(entries = emptyList(), loaded = false, scroll = it.scroll.snapshot())
                    })
                }
                if (event.disabled) { cancel(); current?.let { update(it) { page -> page.copy(loading = false) } } }
                else refresh()
            }
        }
    } } }
    fun page(key: MediaDestination.Remote): kotlinx.coroutines.flow.StateFlow<BrowserState> =
        pages.getOrPut(key) { MutableStateFlow(BrowserState()) }.asStateFlow()

    fun open(profile: ServerProfile) {
        if (!profile.kind.remote) return
        close()
        cacheSession = java.util.UUID.randomUUID().toString()
        val root = when (profile.kind) {
            ServerKind.GOOGLE_DRIVE, ServerKind.ONEDRIVE -> "root"
            ServerKind.WEBDAV -> profile.address
            ServerKind.SMB -> profile.path
            else -> ""
        }
        val key = MediaDestination.Remote(profile.id, root, profile.name, java.util.UUID.randomUUID().toString())
        navigation.push(key)
        pages[key] = MutableStateFlow(BrowserState(profile, RemoteFolder(root, profile.name)))
        mutable.value = pages.getValue(key).value
        load(false)
    }
    fun restore(profile: ServerProfile) {
        navigation.backStack.filterIsInstance<MediaDestination.Remote>().forEachIndexed { index, key ->
            if (key.profileId == profile.id) pages.getOrPut(key) { MutableStateFlow(BrowserState()) }.value = BrowserState(profile,
                RemoteFolder(key.location, key.title), path = navigation.backStack.filterIsInstance<MediaDestination.Remote>()
                    .take(index + 1).drop(1).map { it.title })
        }
        current?.let { mutable.value = pages[it]?.value ?: BrowserState(); load(false) }
    }
    fun enter(entry: RemoteEntry) {
        val parent = current ?: return
        val before = state.value
        if (entry.kind != RemoteFileKind.FOLDER || entry !in before.folder?.entries.orEmpty()) return
        // Keep the existing 32-directory traversal bound, without truncating to a false root.
        if (navigation.backStack.count { it is MediaDestination.Remote } >= 32) {
            update(parent) { it.copy(error = ServerError.LIMIT) }; return
        }
        val key = MediaDestination.Remote(parent.profileId, entry.location, entry.name, parent.session)
        if (key in navigation.backStack) return
        cancel()
        update(parent) { it.copy(loading = false) }
        navigation.push(key)
        pages[key] = MutableStateFlow(BrowserState(before.profile, RemoteFolder(entry.location, entry.name), before.path + entry.name))
        mutable.value = pages.getValue(key).value
        trim()
        load(false)
    }
    fun rememberScroll(index: Int, offset: Int, location: String? = null) {
        val folder = state.value.folder ?: return
        if (location == null || folder.location == location) folder.scroll.save(index, offset)
    }
    fun back() {
        val key = current ?: return
        cancel()
        navigation.pop()
        // NavDisplay may still paint this outgoing flow; removing the lookup releases it after transition.
        pages.remove(key)
        val parent = current
        if (parent == null) {
            cache?.directories?.close(cacheSession); pages.clear(); mutable.value = BrowserState()
        } else {
            mutable.value = pages.getValue(parent).value
            if (state.value.folder?.let { !it.loaded || System.nanoTime() - it.loadedAtNanos >= 30_000_000_000L } == true) load(false)
        }
    }
    fun close() {
        cancel(); cache?.directories?.close(cacheSession)
        navigation.removeFrom { it is MediaDestination.Remote }
        pages.clear(); mutable.value = BrowserState()
    }
    fun unsupported(show: Boolean) { current?.let { key -> update(key) { it.copy(unsupported = show) } } }
    fun refresh() = load(true)
    private fun update(key: MediaDestination.Remote, change: (BrowserState) -> BrowserState) {
        val flow = pages[key] ?: return
        flow.value = change(flow.value)
        if (current == key) mutable.value = flow.value
    }
    private fun trim() {
        // Three recent directory payloads; older entries retain only keys and live checkpoints.
        val keep = navigation.backStack.filterIsInstance<MediaDestination.Remote>().takeLast(3).toSet()
        pages.filterKeys { it !in keep }.values.forEach { flow ->
            val folder = flow.value.folder ?: return@forEach
            if (folder.loaded) flow.value = flow.value.copy(folder = folder.copy(entries = emptyList(), loaded = false,
                scroll = folder.scroll.snapshot()), loading = false)
        }
    }
    private fun load(force: Boolean) {
        val key = current ?: return
        val profile = state.value.profile ?: return
        val folder = state.value.folder ?: return
        cancel()
        val ticket = generation
        val session = cacheSession
        update(key) { it.copy(loading = true, error = null) }
        request = scope.launch {
            try {
                val cacheTicket = cache?.directories?.ticket()
                // Never replace a currently painted directory with an older cached response on refresh.
                val cached = if (!folder.loaded) cache?.directories?.get(session, folder.location) else null
                ensureActive()
                if (ticket != generation || current != key) return@launch
                if (cached != null) {
                    update(key) { it.copy(folder = it.folder!!.copy(entries = cached.first, loaded = true, loadedAtNanos = System.nanoTime() - cached.second * 1_000_000)) }
                    if (!force && cached.second < 30_000) { update(key) { it.copy(loading = false) }; return@launch }
                }
                val entries = directory.list(profile, folder.location)
                ensureActive()
                if (ticket != generation || current != key) return@launch
                if (cacheTicket != null) cache?.directories?.put(session, folder.location, entries, cacheTicket)
                ensureActive()
                if (ticket == generation && current == key) update(key) {
                    it.copy(loading = false, folder = it.folder!!.copy(
                        entries = if (it.folder.entries == entries) it.folder.entries else entries,
                        loaded = true, loadedAtNanos = System.nanoTime()))
                }
                trim()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (ticket == generation && current == key) update(key) {
                    it.copy(loading = false, error = (failure as? DirectoryFailure)?.reason ?: ServerError.NETWORK)
                }
            }
        }
    }
    private fun cancel() { ++generation; request?.cancel(); request = null }
}
