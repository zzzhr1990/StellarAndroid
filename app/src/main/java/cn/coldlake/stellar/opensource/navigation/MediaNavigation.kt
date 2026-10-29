package cn.coldlake.stellar.opensource.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import androidx.savedstate.serialization.encodeToSavedState
import androidx.savedstate.serialization.decodeFromSavedState

/** The only media-library history. No credentials or DTOs; Emby checkpoints are keyed by entry. */
@Serializable internal sealed interface MediaDestination : NavKey {
    @Serializable data object Home : MediaDestination
    @Serializable data object Settings : MediaDestination
    @Serializable data object CatalogMetadata : MediaDestination
    @Serializable data object Paths : MediaDestination
    @Serializable data object Storage : MediaDestination
    @Serializable data object MediaServerTypes : MediaDestination
    @Serializable data class Form(val profileId: String, val session: String) : MediaDestination
    @Serializable data class Remote(val profileId: String, val location: String, val title: String, val session: String) : MediaDestination
    @Serializable data class Emby(val profileId: String, val entry: String) : MediaDestination
}

internal class MediaNavigation(private val savedState: SavedStateHandle? = null) {
    private val serializer = ListSerializer(MediaDestination.serializer())
    val backStack = NavBackStack<MediaDestination>().apply {
        addAll(runCatching {
            savedState?.get<android.os.Bundle>("management_navigation")?.let { decodeFromSavedState(serializer, it) }
        }.getOrNull().orEmpty().filterNot { it is MediaDestination.Form })
        if (firstOrNull() != MediaDestination.Home) { clear(); add(MediaDestination.Home) }
    }
    val current get() = backStack.last()
    fun push(destination: MediaDestination) {
        if (current == destination) return
        backStack.add(destination)
        save()
    }
    fun pop() {
        if (backStack.size > 1) { backStack.removeAt(backStack.lastIndex); save() }
    }
    fun replace(destination: MediaDestination) {
        if (current == destination) return
        backStack[backStack.lastIndex] = destination
        save()
    }
    fun removeFrom(predicate: (MediaDestination) -> Boolean) {
        val index = backStack.indexOfFirst(predicate)
        if (index > 0) { while (backStack.size > index) backStack.removeAt(backStack.lastIndex); save() }
    }
    private fun save() {
        // Draft credentials stay in the Activity ViewModel. After process death return to
        // the picker/list; do not silently persist a password in Android saved state.
        savedState?.set("management_navigation", encodeToSavedState(serializer,
            backStack.takeWhile { it !is MediaDestination.Form }))
    }
}
