package cn.coldlake.stellar.opensource.playback.reporting

import android.content.Context
import android.os.SystemClock
import cn.coldlake.stellar.opensource.cache.AppCaches
import cn.coldlake.stellar.opensource.cache.cacheNamespace
import cn.coldlake.stellar.opensource.medialibrary.catalog.CatalogDatabase
import cn.coldlake.stellar.opensource.medialibrary.catalog.LocalLibraryRuntime
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ResumeConflict(val localMs: Long, val serverMs: Long)

/** App lifetime, no Activity/player reference. A local commit precedes every corresponding network enqueue. */
internal class PlaybackReportingRuntime internal constructor(
    private val catalog: CatalogDatabase,
    private val resolveProfile: suspend (String) -> ServerProfile?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val verifyTlsCertificates: () -> Boolean = { true },
    private val onPlaybackChanged: () -> Unit = LocalLibraryRuntime::playbackChanged,
) {
    private val records = PlaybackRecordRepository(catalog)
    val storageFailed = records.storageFailed
    private val changes = MutableStateFlow(0L)
    val recordChanges = changes.asStateFlow()
    private val active = mutableSetOf<String>()
    private class Remote(val adapter: MediaServerReportAdapter) { @Volatile var revoked = false }
    private data class Write(val key: PlaybackRecordKey?, val source: EmbyPlayback?, val remote: Remote?, val fact: PlaybackFact)
    // Snapshot floods are normalized before this queue. Adjacent progress merges; boundaries remain explicit.
    private val queue = ArrayDeque<Write>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var writing = false
    private val dispatcher = PlaybackReportDispatcher(scope, SystemClock::elapsedRealtime, ::delivered)
    private val conflictState = MutableStateFlow<ResumeConflict?>(null)
    val conflict = conflictState.asStateFlow()
    private var choice: CompletableDeferred<Long>? = null
    private val choiceLock = Mutex()
    init { scope.launch { for (signal in wake) while (true) {
        val work = synchronized(queue) { if (queue.isEmpty()) null else queue.removeFirst().also { writing = true } } ?: break
        try { write(work) } finally { writing = false }
    } } }
    private suspend fun <T> database(block: () -> T): T = records.database(block)
    private fun publishPlaybackChange() {
        // The record is already committed. A failed projection read must not stop the
        // writer, skip its network enqueue, or turn a manual commit into a failed command.
        try { onPlaybackChanged() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("PlaybackReporting", "Progress notification failed: ${e.javaClass.simpleName}") }
    }
    fun coordinator(key: PlaybackRecordKey?, source: EmbyPlayback?): PlaybackReportingCoordinator {
        // Each replay gets a new adapter; an ended adapter can release its client independently.
        var remote: Remote? = null
        return PlaybackReportingCoordinator({ fact ->
            if (fact.kind == ReportKind.START) remote = source?.let { Remote(EmbyReportAdapter(it) { resolveProfile(it.profile.id) }) }
            synchronized(queue) {
                val tail = queue.lastOrNull()
                val combined = if (tail != null && tail.fact.kind != ReportKind.FINISH && tail.fact.session == fact.session) {
                    queue.removeLast()
                    fact.copy(kind = if (fact.kind == ReportKind.FINISH) ReportKind.FINISH else tail.fact.kind,
                        opensSession = fact.opensSession || tail.fact.opensSession,
                        persist = fact.persist || tail.fact.persist, report = fact.report || tail.fact.report,
                        reasons = fact.reasons + tail.fact.reasons)
                } else fact
                // A stalled disk cannot create an unbounded event log or suspend the player thread.
                if (queue.size >= 64) {
                    val overflow = queue.removeFirst()
                    overflow.key?.let { records.memory(it, overflow.fact) }
                    overflow.remote?.let {
                        // Never bypass the commit queue: that could send an old Start after a new one.
                        it.revoked = true
                        dispatcher.revoke(it.adapter.target, overflow.fact.session)
                    }
                }
                queue.addLast(Write(key, source, remote, combined))
            }
            wake.trySend(Unit)
        }, SystemClock::elapsedRealtime, System::currentTimeMillis)
    }
    private suspend fun write(work: Write) {
        val fact = work.fact
        val adapter = work.remote?.adapter
        synchronized(active) { if (fact.kind == ReportKind.FINISH) active.remove(fact.session) else active.add(fact.session) }
        var record: PlaybackRecord? = null
        var persisted = false
        if (work.key != null) {
            val result = records.commit(work.key, fact, adapter?.target, adapter?.baseline) {
                work.source?.let { catalog.playbackServerBind(target(it.profile), it.item.id, it.version.id, work.key) }
            }
            record = result.record; persisted = result.durable
            if (record == null) {
                adapter?.let { dispatcher.revoke(it.target, fact.session) }
                synchronized(active) { active.remove(fact.session) }
                return
            }
            if (fact.persist) { changes.value++; if (persisted) publishPlaybackChange() }
        }
        // Cache failure cannot undo a commit. Pending state is read from this repository, never the cache.
        if (persisted && fact.kind == ReportKind.FINISH && work.source != null) try {
            AppCaches.current?.data?.invalidateProgressQueries(work.source.profile.cacheNamespace())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        if (fact.report && adapter != null) {
            val event = ReportEnvelope(work.key.takeIf { persisted }, record?.revision ?: 0, fact, adapter)
            if (work.remote?.revoked == true) {
                delivered(event, ReportResult(DeliveryState.Unconfirmed, "StorageBackpressure"))
                if (fact.kind == ReportKind.FINISH) adapter.close()
            } else if (fact.opensSession && fact.kind == ReportKind.FINISH) {
                delivered(event, ReportResult(DeliveryState.Unconfirmed, "NotStarted")); adapter.close()
            } else dispatcher.submit(event)
        }
    }
    private suspend fun delivered(event: ReportEnvelope, result: ReportResult) {
        records.acknowledge(event, result); changes.value++
    }
    suspend fun bindVersion(version: Long, key: PlaybackRecordKey) {
        database { catalog.playbackBind(version, key) }; publishPlaybackChange()
    }
    suspend fun record(key: PlaybackRecordKey): PlaybackRecord? = records.read(key)
    suspend fun resume(profile: ServerProfile, item: EmbyItem, version: String): ResumeChoice {
        val key = PlaybackIdentity.server(profile, item.id, version)
        val local = record(key)
        val target = target(profile)
        val delivery = database { catalog.playbackDelivery(key, target) }
        // An item-level server point is applicable only to its sole/default version.
        val server = item.baseline(version).let { if (item.sources.size > 1 && item.sources.first().id != version) it.copy(positionMs = 0) else it }
        return ResumeResolver.resolve(local, delivery, server, synchronized(active) { local?.session in active })
    }
    suspend fun chooseResume(profile: ServerProfile, item: EmbyItem, version: String): Long {
        val resolved = resume(profile, item, version)
        if (!resolved.conflict) return resolved.positionMs
        return choiceLock.withLock {
            val deferred = CompletableDeferred<Long>()
            choice = deferred; conflictState.value = ResumeConflict(resolved.positionMs, checkNotNull(resolved.serverPositionMs))
            try { deferred.await() } finally { conflictState.value = null; choice = null }
        }
    }
    fun resolveConflict(position: Long?) { if (position == null) choice?.cancel() else choice?.complete(position) }
    suspend fun overlay(profile: ServerProfile, items: List<EmbyItem>, freshServer: Boolean = false): List<EmbyItem> = database {
        val target = target(profile)
        items.map { item ->
            val row = catalog.playbackServerRecord(target, item.id) ?: return@map item
            val local = row.first
            val delivery = catalog.playbackDelivery(local.key, target)
            val isActive = synchronized(active) { local.session in active }
            val pending = delivery == null || delivery.acknowledged < local.revision || delivery.state != DeliveryState.Acknowledged || delivery.confirmationPending
            if (!isActive && !pending) {
                val confirmed = delivery?.baseline?.takeIf { local.ended && !freshServer }
                if (confirmed == null) item else item.copy(resumeMs = confirmed.positionMs, played = confirmed.played, progressPending = false)
            } else item.copy(resumeMs = if (local.resumeEligible) local.positionMs else 0,
                played = local.watched, userStateKnown = true, progressPending = pending)
        }
    }
    suspend fun watchCommand(version: Long, position: Long, duration: Long, watched: Boolean) {
        awaitIdle()
        val locator = database { catalog.playbackVersionLocator(version) }
        val previous = database { catalog.playbackVersionKey(version)?.let(catalog::playbackRead) }
        previous?.let { records.discard(it.key) }
        database { catalog.saveProgress(version, position, duration, watched) }
        publishPlaybackChange(); changes.value++
        if (locator?.startsWith("stellar-server:") != true) return
        val uri = android.net.Uri.parse(locator)
        val profile = resolveProfile(uri.authority.orEmpty()) ?: return
        if (profile.embyServerId != uri.getQueryParameter("server") || profile.embyUserId != uri.getQueryParameter("user")) return
        val target = target(profile)
        previous?.let { dispatcher.revoke(target, it.session) }
        val key = database { catalog.playbackVersionKey(version) } ?: return
        val revision = database { catalog.playbackRead(key)?.revision } ?: return
        database { catalog.playbackManualStatus(key, target, revision, DeliveryState.Pending, null) }
        try {
            dispatcher.command(target) {
                val current = resolveProfile(profile.id)
                if (current == null || current.updatedAt != profile.updatedAt) throw EmbyFailure(ServerError.AUTHENTICATION)
                val baseline = EmbyClient(current, verifyTlsCertificates()).use { api ->
                    api.setUserFlag(EmbyItem(uri.lastPathSegment.orEmpty(), "", "Movie", false), false, watched)
                    readback(api, uri.lastPathSegment.orEmpty(), uri.getQueryParameter("mediaSource"))
                }
                database { catalog.playbackManualStatus(key, target, revision, DeliveryState.Acknowledged,
                    if (baseline == null) "ReadbackPending" else null, baseline) }
                changes.value++
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val state = if ((e as? EmbyFailure)?.reason in setOf(ServerError.AUTHENTICATION, ServerError.PERMISSION))
                DeliveryState.AuthenticationRequired else DeliveryState.Unconfirmed
            database { catalog.playbackManualStatus(key, target, revision, state, "ManualReportFailed") }
        }
    }
    private suspend fun readback(api: EmbyClient, item: String, version: String?): ServerBaseline? = withTimeoutOrNull(1_500) {
        try { api.detail(item).baseline(version) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    suspend fun userCommand(profile: ServerProfile, item: EmbyItem, favorite: Boolean, value: Boolean,
        onLocalCommit: () -> Unit = {}, send: suspend () -> Unit) {
        awaitIdle()
        val target = target(profile)
        val changed = if (favorite) emptyList() else database {
            val previous = catalog.playbackServerRecord(target, item.id)?.first
            previous?.let { records.discard(it.key); dispatcher.revoke(target, it.session) }
            catalog.playbackServerCommand(target, item.id, if (value) item.resumeMs else 0, value)
        }
        if (changed.isNotEmpty()) {
            onLocalCommit(); publishPlaybackChange(); changes.value++
        }
        try {
            dispatcher.command(target) {
                val current = resolveProfile(profile.id)
                if (current == null || current.updatedAt != profile.updatedAt || current.embyServerId != profile.embyServerId || current.embyUserId != profile.embyUserId)
                    throw EmbyFailure(ServerError.AUTHENTICATION)
                send()
                if (changed.isNotEmpty()) {
                    val baseline = EmbyClient(current, verifyTlsCertificates()).use { readback(it, item.id, item.sources.singleOrNull()?.id) }
                    database { changed.forEach { catalog.playbackManualStatus(it.key, target, it.revision,
                        DeliveryState.Acknowledged, if (baseline == null) "ReadbackPending" else null, baseline) } }
                    changes.value++
                }
            }
        } catch (e: Exception) {
            val state = if ((e as? EmbyFailure)?.reason in setOf(ServerError.AUTHENTICATION, ServerError.PERMISSION))
                DeliveryState.AuthenticationRequired else DeliveryState.Unconfirmed
            withContext(NonCancellable) { database { changed.forEach {
                catalog.playbackManualStatus(it.key, target, it.revision, state, "ManualReportFailed")
            } }; changes.value++ }
            throw e
        }
    }
    suspend fun awaitIdle() {
        while (synchronized(queue) { queue.isNotEmpty() || writing }) delay(5)
        database { Unit }
    }
    companion object {
        fun target(profile: ServerProfile) = PlaybackIdentity.key("delivery", profile.kind.name, profile.embyServerId, profile.embyUserId, profile.id).value
        @Volatile private var instance: PlaybackReportingRuntime? = null
        fun current() = instance
        fun get(context: Context): PlaybackReportingRuntime = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val servers = ServerDatabase(app)
                val security = MediaSecuritySettings(app)
                // Reuse the connection, but read current profiles so edits/deletion revoke old sessions.
                PlaybackReportingRuntime(CatalogDatabase.shared(app), { id -> servers.list().firstOrNull { it.id == id } },
                    verifyTlsCertificates = { !security.ignoreCertificateErrors }).also { instance = it }
            }
        }
    }
}
