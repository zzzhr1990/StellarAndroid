package cn.coldlake.stellar.opensource.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventDispatcherOwner
import cn.coldlake.stellar.opensource.medialibrary.catalog.HomeSection
import com.stellar.player.ui.LocalPageActive
import kotlinx.serialization.Serializable

/** Only lightweight route arguments are saved. Shared players/repositories remain Activity-owned. */
@Serializable internal data class SettingsDestination(val route: String) : NavKey

@Serializable internal sealed interface LibraryDestination : NavKey {
    @Serializable data object Home : LibraryDestination
    @Serializable data class Grid(val section: HomeSection, val source: String?) : LibraryDestination
    @Serializable data class Detail(val entity: Long, val source: String?, val entry: String = java.util.UUID.randomUUID().toString()) : LibraryDestination
    @Serializable data class Season(val entity: Long, val source: String?, val number: Int, val publicId: String? = null,
        val entry: String = java.util.UUID.randomUUID().toString()) : LibraryDestination
    @Serializable data class Person(val entity: Long, val source: String?, val credit: String, val season: Int? = null, val seasonId: String? = null,
        val entry: String = java.util.UUID.randomUUID().toString()) : LibraryDestination
    @Serializable data class Metadata(val card: Long = 0) : LibraryDestination
}

private val LocalAppNavigationOwner = staticCompositionLocalOf<NavigationEventDispatcherOwner?> { null }

/** NavDisplay owns transitions and saved entry state; previews cannot dispatch business actions. */
@Composable internal fun <T : Any> LibraryNavDisplay(
    backStack: List<T>, modifier: Modifier = Modifier, onBack: () -> Unit,
    retainedRoots: List<T> = backStack,
    navigationEnabled: Boolean = true,
    entryMetadata: (T) -> Map<String, Any> = { emptyMap() },
    content: @Composable (T) -> Unit,
) {
    val parentActive = LocalPageActive.current
    // NavEntry factories are cached by keys. Retained root entries still need current
    // visibility, callbacks and parameters when the selected tab or page data changes.
    val activeDestination = rememberUpdatedState(backStack.last())
    val parentIsActive = rememberUpdatedState(parentActive)
    val currentContent = rememberUpdatedState(content)
    // Attach domain dispatchers to the Activity, not to each other. NavigationEvent 1.1.2
    // disposes descendants with their parent; movable NavEntry content can be disposed later.
    val appOwner = LocalAppNavigationOwner.current ?: LocalNavigationEventDispatcherOwner.current
    val dispatcher = rememberNavigationEventDispatcherOwner(enabled = parentActive && navigationEnabled, parent = appOwner)
    CompositionLocalProvider(LocalAppNavigationOwner provides appOwner,
        LocalNavigationEventDispatcherOwner provides dispatcher) {
        val entries = rememberDecoratedNavEntries(backStack = retainedRoots,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = { destination -> NavEntry(destination, metadata = entryMetadata(destination)) {
                val active = parentIsActive.value && destination == activeDestination.value
                CompositionLocalProvider(LocalPageActive provides active) {
                    PageInput(active) { currentContent.value(destination) }
                }
            } })
        NavDisplay(entries = backStack.map { entries[retainedRoots.indexOf(it)] }, modifier = modifier, onBack = onBack)
    }
}

/** Inactive previews and the covered player cannot receive touches or accessibility actions. */
@Composable internal fun PageInput(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().then(if (active) Modifier else Modifier
        .clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })) { content() }
}
