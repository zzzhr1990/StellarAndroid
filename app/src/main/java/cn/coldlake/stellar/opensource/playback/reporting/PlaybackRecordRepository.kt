package cn.coldlake.stellar.opensource.playback.reporting

import cn.coldlake.stellar.opensource.medialibrary.catalog.CatalogDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RecordCommit(val record: PlaybackRecord?, val durable: Boolean)

/** Durable local authority with an explicit, bounded fallback when storage is unavailable. */
internal class PlaybackRecordRepository(val catalog: CatalogDatabase) {
    private val mutex = Mutex()
    private var recovered = false
    private data class Unsaved(val record: PlaybackRecord, val opensSession: Boolean)
    private val unsaved = LinkedHashMap<PlaybackRecordKey, Unsaved>()
    private val failure = MutableStateFlow(false)
    val storageFailed = failure.asStateFlow()
    suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock {
        if (!recovered) { catalog.playbackRecover(); recovered = true }
        block()
    } }
    fun discard(key: PlaybackRecordKey) = synchronized(unsaved) { unsaved.remove(key); failure.value = unsaved.isNotEmpty() }
    suspend fun read(key: PlaybackRecordKey): PlaybackRecord? {
        synchronized(unsaved) { unsaved[key]?.let { return it.record } }
        return try { database { catalog.playbackRead(key) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure.value = true; null }
    }
    fun memory(key: PlaybackRecordKey, fact: PlaybackFact): RecordCommit = synchronized(unsaved) {
        val old = unsaved[key]
        val record = PlaybackRecord(key, fact.positionMs, fact.durationMs, old?.record?.watched == true || fact.finish == FinishReason.ENDED,
            fact.positionMs > 0 && fact.finish != FinishReason.ENDED, fact.lastWatchedAt, (old?.record?.revision ?: 0) + 1,
            fact.session, fact.sequence, fact.kind == ReportKind.FINISH)
        unsaved[key] = Unsaved(record, fact.opensSession || old?.opensSession == true)
        while (unsaved.size > 128) unsaved.remove(unsaved.keys.first())
        failure.value = true
        RecordCommit(record, false)
    }
    suspend fun commit(key: PlaybackRecordKey, fact: PlaybackFact, target: String?, baseline: ServerBaseline?,
        before: () -> Unit = {}): RecordCommit {
        val pending = synchronized(unsaved) { unsaved[key]?.takeIf { it.record.session == fact.session } }
        val value = if (pending != null) fact.copy(opensSession = fact.opensSession || pending.opensSession, persist = true) else fact
        return try {
            val record = database { before(); catalog.playbackCommit(key, value, target, baseline) }
            synchronized(unsaved) { unsaved.remove(key); failure.value = unsaved.isNotEmpty() }
            RecordCommit(record, true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { memory(key, value) }
    }
    suspend fun acknowledge(event: ReportEnvelope, result: ReportResult) {
        val key = event.key ?: return
        try { database { catalog.playbackAck(key, event.adapter.target, event.fact, event.revision, result.state, result.error, result.baseline) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure.value = true }
    }
}
