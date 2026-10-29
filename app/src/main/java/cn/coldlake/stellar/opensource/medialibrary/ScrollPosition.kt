package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** ViewModel-owned navigation checkpoint, deliberately outside observable business state.
 * The active reader supplies the exact position even when navigation happens during a fling.
 * Disposal checkpoints only its own reader, so an old page cannot overwrite a new page.
 * Accessed on the UI thread; this retains Activity/navigation state, not process-death state.
 */
internal class ScrollPosition {
    private var saved = 0 to 0
    private var reader: (() -> Pair<Int, Int>)? = null
    val current: Pair<Int, Int> get() = reader?.invoke() ?: saved
    val index: Int get() = current.first
    val offset: Int get() = current.second

    fun save(index: Int, offset: Int) { saved = index.coerceAtLeast(0) to offset.coerceAtLeast(0) }
    fun snapshot() = ScrollPosition().also { copy -> current.let { copy.save(it.first, it.second) } }
    fun attach(read: () -> Pair<Int, Int>): () -> Unit {
        reader = read
        return {
            if (reader === read) { saved = read(); reader = null }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun rememberPageList(position: ScrollPosition, windowFraction: Float = .5f): LazyListState {
    // Keep a bounded, symmetric window so reversing direction does not discard and
    // immediately recreate whole nested shelves at the edge of the retained range.
    val list = remember(position, windowFraction) { position.current.let { (i, o) ->
        LazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = windowFraction, behindFraction = windowFraction),
            firstVisibleItemIndex = i, firstVisibleItemScrollOffset = o)
    } }
    DisposableEffect(position, list) {
        val detach = position.attach { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        onDispose { detach() }
    }
    return list
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun rememberPageGrid(position: ScrollPosition): LazyGridState {
    val grid = remember(position) { position.current.let { (i, o) ->
        LazyGridState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = .5f, behindFraction = .5f),
            firstVisibleItemIndex = i, firstVisibleItemScrollOffset = o)
    } }
    DisposableEffect(position, grid) {
        val detach = position.attach { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
        onDispose { detach() }
    }
    return grid
}
