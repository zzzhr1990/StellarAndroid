package cn.coldlake.stellar.opensource.presentation

import com.stellar.player.contract.MediaDescriptor
import com.stellar.player.ui.RepeatMode

/** Queue edits affect fixtures only. Seed/cursor can be saved with the Android task. */
class PlaybackQueue(items: List<MediaDescriptor>, var mode: RepeatMode = RepeatMode.ALL, var randomState: Long = 41L) {
    var items = items.distinctBy { it.id }; private set
    fun replace(values: List<MediaDescriptor>) { items = values.distinctBy { it.id } }
    fun remove(id: String, currentId: String?): MediaDescriptor? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return items.firstOrNull { it.id == currentId }
        items = items.filterNot { it.id == id }
        return if (id == currentId) items.getOrNull(index.coerceAtMost(items.lastIndex).coerceAtLeast(0))
            else items.firstOrNull { it.id == currentId }
    }
    fun target(currentId: String?, offset: Int, automatic: Boolean = false): MediaDescriptor? {
        val eligible = if (automatic) items.filter { it.durationMs != 0L } else items
        if (eligible.isEmpty()) return null
        val index = eligible.indexOfFirst { it.id == currentId }
        if (automatic && mode == RepeatMode.ONE && index >= 0) return eligible[index]
        if (mode == RepeatMode.RANDOM && eligible.size > 1) {
            val others = eligible.filterNot { it.id == currentId }
            randomState = (randomState * 1664525L + 1013904223L) and 0xffffffffL
            return others[(randomState % others.size).toInt()]
        }
        return eligible[Math.floorMod((if (index < 0) 0 else index) + offset, eligible.size)]
    }
}
