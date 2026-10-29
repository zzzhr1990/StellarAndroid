package cn.coldlake.stellar.opensource.medialibrary.library

import kotlinx.coroutines.*
import cn.coldlake.stellar.opensource.navigation.MediaNavigation
import cn.coldlake.stellar.opensource.navigation.MediaDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class LibraryNotice { ADDED, DELETED, DUPLICATE, PARENT, ERROR, PERMISSION }
internal data class LibraryState(
    val paths: List<LibraryPath> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val loadError: Boolean = false,
    val notice: LibraryNotice? = null, val coveredPath: String = "",
)

internal class LibraryController(private val scope: CoroutineScope, private val repository: LibraryRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO, val navigation: MediaNavigation = MediaNavigation(), private val onChanged: () -> Unit = {}) {
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    init { reload() }
    fun open(value: Boolean) { if (value) navigation.push(MediaDestination.Paths) else if (navigation.current == MediaDestination.Paths) navigation.pop() }
    fun dismissNotice() { mutable.value = state.value.copy(notice = null, coveredPath = "") }
    fun permissionError() { mutable.value = state.value.copy(notice = LibraryNotice.PERMISSION) }
    fun reload() {
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true)
        scope.launch {
            try {
                val paths = withContext(io) { repository.list() }
                mutable.value = state.value.copy(paths = paths, loaded = true, busy = false, loadError = false)
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(loaded = true, busy = false, loadError = true) }
        }
    }
    fun add(candidate: LibraryPath) = add(candidate = { candidate })
    fun add(onNotAdded: () -> Unit = {}, candidate: suspend () -> LibraryPath) {
        if (!state.value.loaded || state.value.busy || state.value.loadError) return
        mutable.value = state.value.copy(busy = true, notice = null)
        scope.launch {
            try {
                when (val result = withContext(io) { repository.add(candidate()) }) {
                    is LibraryAddResult.Added -> mutable.value = state.value.copy(busy = false,
                        paths = (state.value.paths.filterNot { it.id == result.entry.id } + result.entry).sortedBy { it.createdAt },
                        notice = LibraryNotice.ADDED)
                    is LibraryAddResult.Covered -> {
                        withContext(io) { onNotAdded() }
                        mutable.value = state.value.copy(busy = false,
                            notice = if (result.exact) LibraryNotice.DUPLICATE else LibraryNotice.PARENT, coveredPath = result.entry.displayPath)
                    }
                }
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                withContext(io) { runCatching { onNotAdded() } }
                mutable.value = state.value.copy(busy = false,
                    notice = if (failure is SecurityException) LibraryNotice.PERMISSION else LibraryNotice.ERROR)
            }
        }
    }
    fun delete(id: String) {
        if (state.value.busy || state.value.paths.none { it.id == id }) return
        mutable.value = state.value.copy(busy = true, notice = null)
        scope.launch {
            try {
                withContext(io) { repository.delete(id) }
                mutable.value = state.value.copy(busy = false, paths = state.value.paths.filterNot { it.id == id }, notice = LibraryNotice.DELETED)
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(busy = false, notice = LibraryNotice.ERROR) }
        }
    }
}
