package cn.coldlake.stellar.opensource.medialibrary.servers

import android.app.Application
import cn.coldlake.stellar.opensource.navigation.MediaNavigation
import cn.coldlake.stellar.opensource.navigation.MediaDestination
import cn.coldlake.stellar.opensource.cache.cacheNamespace
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.coldlake.stellar.opensource.medialibrary.library.LibraryController
import cn.coldlake.stellar.opensource.medialibrary.library.LibraryDatabase
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ServerBusy { CHECKING, SAVING }
internal data class ServersState(
    val servers: List<ServerProfile> = emptyList(),
    val draft: ServerProfile? = null,
    val busy: ServerBusy? = null,
    val error: ServerError? = null,
    val listError: Boolean = false,
    val loaded: Boolean = false,
    val pendingDelete: ServerProfile? = null,
    val deleting: Boolean = false,
    val deleteError: Boolean = false,
    val deletedName: String? = null,
)

internal class ServersController(
    private val scope: CoroutineScope,
    private val repository: ServerRepository,
    private val probe: ServerProbe,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val persistChange: suspend (ServerProfile?, ServerProfile?, () -> Unit) -> Unit = { _, _, write -> withContext(io) { write() } },
    val navigation: MediaNavigation = MediaNavigation(),
    private val authenticate: suspend (ServerProfile) -> ServerProfile = { profile ->
        EmbyClient(profile).use { it.authenticate() }
    },
) {
    private val mutable = MutableStateFlow(ServersState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var checking: Job? = null
    private var storageRevision = 0L
    private var loadGeneration = 0L
    init { reload() }
    fun reload() {
        val revision = storageRevision
        val load = ++loadGeneration
        scope.launch {
            try {
                val records = withContext(io) { repository.list() }
                // Apply to the current UI state after IO; an open form must not be lost.
                if (load == loadGeneration && revision == storageRevision && !state.value.deleting)
                    mutable.value = mutable.value.copy(servers = records, loaded = true, listError = false)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (load == loadGeneration && revision == storageRevision && !state.value.deleting)
                    mutable.value = mutable.value.copy(loaded = true, listError = true)
            }
        }
    }
    fun chooseStorage() {
        if (state.value.busy == null) navigation.push(MediaDestination.Storage)
    }
    fun chooseMediaServer() {
        if (state.value.busy == null) navigation.push(MediaDestination.MediaServerTypes)
    }
    fun edit(profile: ServerProfile) {
        if (state.value.busy != null || state.value.pendingDelete != null) return
        if (state.value.draft?.id != profile.id || navigation.current !is MediaDestination.Form) {
            navigation.removeFrom { it is MediaDestination.Form }
            navigation.push(MediaDestination.Form(profile.id, java.util.UUID.randomUUID().toString()))
        }
        mutable.value = mutable.value.copy(draft = profile, error = null)
    }
    val formSession get() = (navigation.current as? MediaDestination.Form)?.session
    fun oneDriveAuthorized(id: String, accountId: String, label: String, session: String? = formSession) {
        if (session == null || session != formSession) return
        val draft = state.value.draft?.takeIf { it.id == id && it.kind == ServerKind.ONEDRIVE } ?: return
        if (state.value.busy != null) return
        if (draft.cloudAccountId.isNotEmpty() && draft.cloudAccountId != accountId) {
            mutable.value = state.value.copy(error = ServerError.ONEDRIVE_ACCOUNT); return
        }
        edit(draft.copy(username = label, cloudAccountId = accountId,
            address = cn.coldlake.stellar.opensource.medialibrary.onedrive.ONEDRIVE_ADDRESS))
        confirm()
    }
    fun driveAuthorized(id: String, email: String, session: String? = formSession) {
        if (session == null || session != formSession) return
        val draft = state.value.draft?.takeIf { it.id == id && it.kind == ServerKind.GOOGLE_DRIVE } ?: return
        if (state.value.busy != null) return
        edit(draft.copy(username = email, address = cn.coldlake.stellar.opensource.medialibrary.drive.DRIVE_ADDRESS))
        confirm()
    }
    fun back() {
        if (state.value.busy == ServerBusy.SAVING) return
        cancel()
        mutable.value = mutable.value.copy(draft = null, error = null)
        if (navigation.current is MediaDestination.Form || navigation.current == MediaDestination.Storage ||
            navigation.current == MediaDestination.MediaServerTypes) navigation.pop()
    }
    fun cancel() {
        if (state.value.busy == ServerBusy.SAVING) return
        ++generation
        checking?.cancel(); checking = null
        mutable.value = mutable.value.copy(busy = null)
    }
    fun requestDelete(id: String) {
        if (!state.value.loaded || state.value.busy != null || state.value.draft != null || state.value.deleting) return
        val profile = state.value.servers.firstOrNull { it.id == id } ?: return
        mutable.value = mutable.value.copy(pendingDelete = profile, deleteError = false, deletedName = null)
    }
    fun dismissDelete() {
        if (!state.value.deleting) mutable.value = mutable.value.copy(pendingDelete = null, deleteError = false)
    }
    fun clearDeletionNotice() { mutable.value = mutable.value.copy(deletedName = null) }
    fun confirmDelete() {
        if (state.value.deleting || state.value.busy != null || state.value.draft != null) return
        val profile = state.value.pendingDelete ?: return
        // Invalidate outstanding loads before IO so stale rows cannot reappear after deletion.
        ++storageRevision
        mutable.value = mutable.value.copy(deleting = true, deleteError = false)
        scope.launch {
            try {
                persistChange(profile,null) { repository.delete(profile.id) }
                ++storageRevision
                mutable.value = mutable.value.copy(
                    servers = state.value.servers.filterNot { it.id == profile.id },
                    pendingDelete = null, deleting = false, deletedName = profile.name)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(deleting = false, deleteError = true)
            }
        }
    }
    fun confirm() {
        if (state.value.busy != null || state.value.pendingDelete != null || !state.value.loaded) return
        val draft = state.value.draft ?: return
        if (!draft.kind.supported) return
        val profile = draft.normalized()
        if (profile == null) { mutable.value = mutable.value.copy(error = ServerError.INPUT); return }
        val ticket = ++generation
        mutable.value = mutable.value.copy(busy = ServerBusy.CHECKING, error = null)
        checking = scope.launch {
            try {
                val verified = if (profile.kind.mediaServer) authenticate(profile) else profile
                val error = if (profile.kind.mediaServer) null else probe.check(profile)
                ensureActive()
                if (generation != ticket) return@launch
                if (error != null) { mutable.value = mutable.value.copy(busy = null, error = error); return@launch }
                // Cancellation is available throughout CHECKING. Once verified, the tiny local
                // transaction is a distinct SAVING phase; no cancelled probe can reach it.
                mutable.value = mutable.value.copy(busy = ServerBusy.SAVING)
                val now = System.currentTimeMillis()
                val saved = verified.copy(createdAt = profile.createdAt.takeIf { it != 0L } ?: now, updatedAt = now)
                try { persistChange(state.value.servers.firstOrNull { it.id==saved.id },saved) { repository.save(saved) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.value = mutable.value.copy(busy = null, error = ServerError.STORAGE); return@launch }
                ++storageRevision
                mutable.value = mutable.value.copy(busy = null, draft = null, error = null,
                    servers = (state.value.servers.filterNot { it.id == saved.id } + saved).sortedByDescending { it.createdAt })
                navigation.removeFrom {
                    it == MediaDestination.Storage || it == MediaDestination.MediaServerTypes || it is MediaDestination.Form
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: EmbyFailure) {
                if (generation == ticket) mutable.value = mutable.value.copy(busy = null, error = failure.reason)
            }
            catch (_: Exception) {
                if (generation == ticket) mutable.value = mutable.value.copy(busy = null, error = ServerError.NETWORK)
            }
        }
    }
}

internal class ServersViewModel(application: Application, private val savedState: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(application) {
    val navigation = MediaNavigation(savedState)
    private val database = ServerDatabase(application)
    private val libraryDatabase = cn.coldlake.stellar.opensource.medialibrary.cloud.LibraryRegistry(application)
    val library = LibraryController(viewModelScope, libraryDatabase, navigation = navigation, onChanged = {
        cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime.get(application).refreshRoots()
    })
    val controller = ServersController(viewModelScope, database, DirectoryServerProbe(application), navigation = navigation,
        authenticate = { profile -> EmbyClient.fromSettings(application, profile).use { it.authenticate() } },
        persistChange = { old,new,write ->
            val libraryRuntime = cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime.get(application)
            if (old?.kind?.let { it in setOf(ServerKind.SMB, ServerKind.NFS, ServerKind.WEBDAV) || it.cloud } == true) libraryRuntime.configurationChanging()
            val caches=cn.coldlake.stellar.opensource.cache.AppCaches.current
            if(old!=null && (new==null || old.cacheNamespace()!=new.cacheNamespace())) withContext(Dispatchers.Main.immediate) {
                if(emby.state.value.profile?.id==old.id) emby.close()
                if(browser.state.value.profile?.id==old.id) browser.close()
            }
            if(caches!=null) caches.profileChange(old,new,write) else withContext(Dispatchers.IO) { write() }
            libraryRuntime.refreshRoots()
        })
    val browser = RemoteBrowserController(viewModelScope, ProtocolDirectory(application), navigation)
    val emby = EmbyBrowserController(viewModelScope,savedState=savedState,navigation=navigation) { !MediaSecuritySettings(application).ignoreCertificateErrors }
    init {
        val remote = navigation.current as? MediaDestination.Remote
        if (remote != null) viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { database.list().firstOrNull { it.id == remote.profileId && it.kind.remote } }
            // A new explicit open wins over an asynchronous process-restoration lookup.
            if (navigation.current == remote && browser.state.value.profile == null) {
                if (profile != null) browser.restore(profile) else navigation.removeFrom { it is MediaDestination.Remote }
            }
        }
        val restoreEmby = navigation.current as? MediaDestination.Emby
        if(restoreEmby!=null) viewModelScope.launch {
            val profile=withContext(Dispatchers.IO) { database.list().firstOrNull { it.id==restoreEmby.profileId && it.kind.mediaServer } }
            if(navigation.current==restoreEmby && emby.state.value.profile==null) {
                if(profile!=null) emby.open(profile) else navigation.removeFrom { it is MediaDestination.Emby }
            }
        }
    }
    override fun onCleared() { browser.close(); emby.close(); database.close(); libraryDatabase.close() }
}
