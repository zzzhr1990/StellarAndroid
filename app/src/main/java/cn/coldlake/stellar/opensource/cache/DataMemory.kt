package cn.coldlake.stellar.opensource.cache

import cn.coldlake.stellar.opensource.medialibrary.servers.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One LRU budget for media snapshots, search results and session directories. */
internal class DataMemory(private val metrics: CacheMetrics) {
    interface Value { val bytes: Int }
    private val entries = LinkedHashMap<String, Value>(16, .75f, true)
    private var bytes = 0L
    @Synchronized fun get(key: String) = entries[key]
    @Synchronized fun put(key: String, value: Value) {
        entries.remove(key)?.let { bytes -= it.bytes }
        if (value.bytes <= 8 * 1024 * 1024) { entries[key] = value; bytes += value.bytes }
        while (bytes > 8 * 1024 * 1024 || entries.size > 100) {
            val first = entries.entries.first(); bytes -= first.value.bytes; entries.remove(first.key)
        }
        metrics.peak("hotBytes", bytes); metrics.peak("hotUnits", entries.size.toLong())
    }
    @Synchronized fun removeWhere(predicate: (String, Value) -> Boolean) {
        entries.entries.removeAll { if (predicate(it.key, it.value)) { bytes -= it.value.bytes; true } else false }
    }
    @Synchronized fun clear() { entries.clear(); bytes = 0 }
}

/** Session-only directory snapshots; no database or media-server query policy. */
internal class DirectoryCache(private val memory: DataMemory, private val clock: CacheClock,
    private val admit: suspend () -> Unit, private val enabled: () -> Boolean) {
    private data class Entry(val entries: List<RemoteEntry>, override val bytes: Int, val time: CacheTime) : DataMemory.Value
    private var generation = 0L
    @Synchronized fun ticket() = generation
    @Synchronized fun get(session: String, path: String): Pair<List<RemoteEntry>, Long>? =
        if (!enabled()) null else (memory.get("directory:$session:$path") as? Entry)
            ?.takeIf { it.time.age(clock) < 120_000 }?.let { it.entries to it.time.age(clock) }
    suspend fun put(session: String, path: String, entries: List<RemoteEntry>, ticket: Long) {
        admit()
        val bytes = withContext(Dispatchers.Default) { entries.sumOf { it.name.toByteArray().size + it.location.toByteArray().size + 32 } }
        synchronized(this) {
            if (enabled() && ticket == generation) memory.put("directory:$session:$path", Entry(entries.toList(), bytes, CacheTime.now(clock)))
        }
    }
    @Synchronized fun close(session: String) { memory.removeWhere { key, _ -> key.startsWith("directory:$session:") } }
    @Synchronized fun clear() { generation++; memory.removeWhere { _, value -> value is Entry } }
}
