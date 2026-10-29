package cn.coldlake.stellar.opensource.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.navigation3.runtime.NavBackStack
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import cn.coldlake.stellar.opensource.presentation.AppRoute
import cn.coldlake.stellar.opensource.presentation.HomeTab
import kotlinx.serialization.builtins.ListSerializer

/** Only page keys are persisted. Playback, credentials and domain stacks keep their existing owners. */
internal class AppNavigation(private val savedState: SavedStateHandle) {
    private val serializer = ListSerializer(AppRoute.serializer())
    val backStack = NavBackStack<AppRoute>().apply { add(AppRoute.LIBRARY) }
    val current get() = backStack.last()
    val routeChanges = snapshotFlow { current }
    var homeTab by mutableStateOf(HomeTab.MEDIA)
        private set

    fun restore(hasMedia: Boolean) {
        val saved = runCatching {
            savedState.get<android.os.Bundle>("app_navigation")?.let { decodeFromSavedState(serializer, it) }
        }.getOrNull()
        val legacy = savedState.get<String>("route")?.let { name -> AppRoute.entries.firstOrNull { it.name == name } }
        val path = saved ?: buildList {
            add(AppRoute.LIBRARY)
            if (hasMedia && legacy != null && legacy != AppRoute.LIBRARY) add(AppRoute.PLAYER)
        }
        backStack.clear()
        backStack.add(AppRoute.LIBRARY)
        // A stale remote/URI session cannot leave an empty player on the restored stack.
        if (path.firstOrNull() == AppRoute.LIBRARY && (AppRoute.PLAYER !in path || hasMedia)) {
            path.drop(1).distinct().filter { it != AppRoute.LIBRARY }.forEach(backStack::add)
        }
        homeTab = when (val name = savedState.get<String>("homeTab")) {
            "FILES", "SEARCH" -> HomeTab.MEDIA_LIBRARY
            else -> HomeTab.entries.firstOrNull { it.name == name } ?: HomeTab.MEDIA
        }
        savedState.remove<String>("route")
        save()
    }

    fun show(destination: AppRoute) {
        val index = backStack.indexOf(destination)
        if (index >= 0) while (backStack.lastIndex > index) backStack.removeAt(backStack.lastIndex)
        else {
            if (destination == AppRoute.PLAYER) while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            backStack.add(destination)
        }
        save()
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        save()
    }

    fun selectHome(tab: HomeTab) {
        homeTab = tab
        save()
    }

    private fun save() {
        savedState["app_navigation"] = encodeToSavedState(serializer, backStack.toList())
        savedState["homeTab"] = homeTab.name
    }
}
