package cn.coldlake.stellar.opensource.presentation

import com.stellar.player.contract.PlaybackSnapshot
import com.stellar.player.contract.PlaybackStatus
import com.stellar.player.disc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiscNavigationUi(val available: Boolean = false, val loading: Boolean = false,
    val active: Boolean = false, val failed: Boolean = false, val menu: DiscMenuState = DiscMenuState())

internal class DiscMenuPlayback(private val scope: CoroutineScope,
    private val snapshot: () -> PlaybackSnapshot, private val play: (String) -> Unit,
    private val tracks: (DiscMenuState) -> Unit) {
    private val mutable = MutableStateFlow(DiscNavigationUi())
    val state = mutable.asStateFlow()
    private var menu: DiscMenu? = null
    private var job: Job? = null
    private var generation = 0
    private val lock = Mutex()
    fun available(value: Boolean) { mutable.value = mutable.value.copy(available = value) }
    fun open(source: DiscSource) {
        close();val version = generation
        mutable.value = DiscNavigationUi(available = true, loading = true)
        job = scope.launch {
            var opened: DiscMenu? = null
            try {
                withContext(Dispatchers.IO) { opened = source.openMenu() }
                val current = opened
                if (version != generation || current == null) {
                    current?.close()
                    if (version == generation) mutable.value = DiscNavigationUi(available = true, failed = true)
                    return@launch
                }
                menu = current
                mutable.value = DiscNavigationUi(available = true, active = true)
                var epoch = 1
                play(current.address(epoch))
                while (isActive && generation == version) {
                    val s = snapshot()
                    val next = withContext(Dispatchers.IO) { lock.withLock { current.poll(s.positionMs, s.status == PlaybackStatus.ENDED) } }
                    if (generation != version) break
                    mutable.value = mutable.value.copy(menu = next, failed = next.error != 0 || s.status == PlaybackStatus.ERROR)
                    if (next.epoch != epoch) { epoch = next.epoch; play(current.address(epoch)) }
                    else tracks(next)
                    delay(50)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if(version==generation) mutable.value=mutable.value.copy(loading=false,failed=true) }
            finally { if (menu !== opened) withContext(NonCancellable + Dispatchers.IO) { opened?.close() } }
        }
    }
    fun input(key: DiscMenuKey, x: Int, y: Int) {
        val current = menu ?: return
        scope.launch(Dispatchers.IO) { lock.withLock { current.input(key, x, y) } }
    }
    fun close() {
        generation++;job?.cancel();job=null
        val old=menu;menu=null
        // close only signals cancellation; it never waits for a network read or VM lock.
        old?.close()
        mutable.value=DiscNavigationUi(available=mutable.value.available)
    }
}
