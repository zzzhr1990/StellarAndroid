package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.content.Context
import android.net.Uri
import cn.coldlake.stellar.opensource.medialibrary.emby.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MetadataProgress(val running: Boolean = false, val processed: Int = 0,
    val matched: Int = 0, val review: Int = 0, val failed: Int = 0, val waiting: Int = 0, val waitingUntil: Long = 0)
private fun metadataProgress(counts: Map<String,Int>, running: Boolean, until: Long = 0): MetadataProgress {
    val matched = (counts["matched"] ?: 0) + (counts["local"] ?: 0) + (counts["partial"] ?: 0)
    val review = counts["review"] ?: 0; val failed = counts["failed"] ?: 0
    return MetadataProgress(running, matched + review + failed, matched, review, failed,
        (counts["waiting"] ?: 0) + (counts["partial"] ?: 0), until)
}
internal data class MetadataJob(val locale: String, val state: String, val round: Int = 0, val nextAt: Long = 0, val scanRun: Long? = null)
internal data class MetadataCandidate(val input: MetadataInput, val due: Boolean, val pending: Pair<String, Long>?)
internal data class ScanMetadataWork(val id: Long, val run: Long, val generation: Long, val attempts: Int)

internal class LibraryMetadata(private val context: Context, private val db: CatalogDatabase,
    private val changed: () -> Unit, val provider: MetadataProvider = MetadataProvider(db, TmdbMetadataTransport()),
    private val clock: () -> Long = System::currentTimeMillis, private val wait: suspend (Long) -> Unit = { delay(it) },
    private val settings: cn.coldlake.stellar.opensource.settings.LibraryScanSettings = cn.coldlake.stellar.opensource.settings.LibraryScanSettings(context),
    private val fingerprints: FingerprintReader = FileFingerprintReader(context, enabled = { settings.quickHashEnabled })) {
    private val mutable = MutableStateFlow(MetadataProgress())
    val progress = mutable.asStateFlow()
    private val lock = Mutex()
    @Volatile private var active: Job? = null
    @Volatile private var stopped = false
    suspend fun identify(locale: String, resume: Boolean = false) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        var job = if (resume) db.metadataJob()?.takeIf { it.state in setOf("running", "waiting") && it.round < 3 } else MetadataJob(locale, "running")
        if (job == null) { lock.unlock(); return@withContext }
        val language = job.locale
        var currentJob: MetadataJob = job
        val counts = db.metadataCounts(language).toMutableMap()
        fun publish(until: Long = 0) {
            mutable.value = metadataProgress(counts, true, until)
        }
        fun saveJob(state: String, at: Long = 0) {
            currentJob = currentJob.copy(state = state, nextAt = at); db.metadataJob(currentJob)
        }
        fun record(input: MetadataInput, state: String, at: Long, error: String? = null) {
            val old = db.metadataState(input.id, language)
            if (db.metadataAttempt(input, language, state, at, error)) {
                if (old != null) counts[old] = ((counts[old] ?: 0) - 1).coerceAtLeast(0)
                counts[state] = (counts[state] ?: 0) + 1
                publish()
            }
        }
        suspend fun awaitRetry(at: Long, rate: Boolean = false): Boolean {
            val remaining = at - clock()
            if (remaining <= 0) return true
            if (rate) {
                if (currentJob.round >= 3) { saveJob("paused", at); return false }
                currentJob = currentJob.copy(round = currentJob.round + 1)
            }
            saveJob("waiting", at); publish(at)
            // Keep one explicitly started foreground task bounded. Long Retry-After persists for a later foreground entry.
            if (remaining > MAX_WAIT) return false
            wait(remaining); ensureActive(); saveJob("running"); publish(); return true
        }
        stopped = false; active = currentCoroutineContext()[Job]; publish()
        try {
            profiles()
            if (!awaitRetry(currentJob.nextAt)) return@withContext
            while (currentJob.round < 3) {
                currentJob = currentJob.copy(round = currentJob.round + 1); saveJob("running")
                var after = 0L
                while (true) {
                    ensureActive()
                    val ids = db.metadataIds(after); if (ids.isEmpty()) break
                    for (candidate in db.metadataCandidates(ids, language, clock())) {
                        ensureActive()
                        if (!candidate.due) continue
                        if (!awaitRetry(db.providerRetry(MetadataProvider.RATE_KEY), rate = true)) return@withContext
                        val input = candidate.input
                        identifyOne(input, language) { state, at, error -> record(input, state, at, error) }
                    }
                    after = ids.last(); yield()
                }
                val retry = db.nextMetadataRetry(language, clock())
                if (retry == null) { saveJob("complete"); break }
                if (currentJob.round >= 3) { saveJob("paused", retry); break }
                if (!awaitRetry(retry)) break
            }
        } finally {
            if (stopped) saveJob("paused")
            active = null
            mutable.value = mutable.value.copy(running = false, waitingUntil = 0)
            changed(); lock.unlock()
        }
    }
    /** One consumer; the channel only wakes it. SQLite owns all pending work across process death. */
    suspend fun identifyScan(run: Long, language: String, wake: ReceiveChannel<Unit>) = withContext(Dispatchers.IO) {
        lock.withLock {
            stopped = false; active = currentCoroutineContext()[Job]
            var producerDone = false
            var state = "running"
            var nextAt = 0L
            var rateRounds = 0
            var lastRate = 0L
            val counts = db.metadataCounts(language).toMutableMap()
            var savedJob: MetadataJob? = null
            fun publish() {
                val job = MetadataJob(language, state, nextAt = nextAt, scanRun = run)
                if (job != savedJob) { db.metadataJob(job); savedJob = job }
                mutable.value = metadataProgress(counts, true, nextAt)
            }
            suspend fun awaitWork(at: Long = 0): Boolean {
                val remaining = at - clock()
                if (producerDone) {
                    if (remaining !in 1..MAX_WAIT) return false
                    wait(remaining)
                } else {
                    val result = if (remaining > 0) withTimeoutOrNull(minOf(remaining, MAX_WAIT)) { wake.receiveCatching() }
                        else wake.receiveCatching()
                    if (result?.isClosed == true) producerDone = true
                }
                ensureActive()
                return true
            }
            try {
                profiles(); publish()
                while (true) {
                    ensureActive()
                    val rate = db.providerRetry(MetadataProvider.RATE_KEY)
                    if (rate > clock()) {
                        if (rate != lastRate) { lastRate = rate; rateRounds++ }
                        if (rateRounds > 3) { state = "paused"; nextAt = rate; break }
                        state = "waiting"; nextAt = rate; publish()
                        if (!awaitWork(rate)) break
                        continue
                    }
                    state = "running"; nextAt = 0
                    var after = 0L
                    var consumed = false
                    while (true) {
                        ensureActive()
                        val batch = db.scanMetadataBatch(run, after, clock())
                        if (batch.isEmpty()) break
                        for (work in batch) {
                            ensureActive()
                            if (db.providerRetry(MetadataProvider.RATE_KEY) > clock()) break
                            val input = db.scanMetadataInput(work) ?: continue
                            consumed = true
                            var result: String? = null; var retry = 0L
                            val due = db.metadataDue(input, language, clock())
                            if (due) identifyOne(input, language, work) { value, at, error ->
                                val old = db.metadataState(input.id, language)
                                if (db.metadataAttempt(input, language, value, at, error, work)) {
                                    result = value; retry = at
                                    if (old != null) counts[old] = ((counts[old] ?: 0) - 1).coerceAtLeast(0)
                                    counts[value] = (counts[value] ?: 0) + 1
                                    mutable.value = metadataProgress(counts, true)
                                }
                            }
                            else db.scanMetadataPending(input, language)?.let { (value, at) -> result = value; retry = at }
                            ensureActive()
                            db.finishScanMetadata(work, input, language, result, retry, attempted = due)
                        }
                        if (db.providerRetry(MetadataProvider.RATE_KEY) > clock()) break
                        after = batch.last().id; yield()
                    }
                    if (db.providerRetry(MetadataProvider.RATE_KEY) > clock()) continue
                    // Revisit replacements below the old keyset cursor before waiting for another signal.
                    if (consumed) continue
                    if (producerDone) db.pruneScanMetadata(run)
                    nextAt = db.nextScanMetadataRetry(run, clock()) ?: 0
                    state = if (nextAt > 0) "waiting" else "running"
                    publish()
                    if (!awaitWork(nextAt)) {
                        state = if (db.scanMetadataRemaining(run) == 0L) "complete" else "paused"
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                state = "paused"; throw cancelled
            } catch (error: Exception) {
                // Provider/consumer failure must not cancel independent file discovery.
                state = "paused"
                android.util.Log.w("LibraryMetadata", "Queue paused run=$run type=${error.javaClass.simpleName}")
            } finally {
                if (stopped) state = "paused"
                db.metadataJob(MetadataJob(language, state, nextAt = nextAt, scanRun = run))
                active = null
                mutable.value = mutable.value.copy(running = false, waitingUntil = 0)
                changed()
            }
        }
    }
    private suspend fun identifyOne(input: MetadataInput, language: String, work: ScanMetadataWork? = null, onAttempt: (String, Long, String?) -> Unit) {
        val owner = currentCoroutineContext()
        fun record(state: String, at: Long, error: String? = null) { owner.ensureActive(); onAttempt(state, at, error) }
        fun bind(value: MetadataRecord, origin: String): Boolean {
            owner.ensureActive()
            val before = db.homeRevision()
            val accepted = db.bindMetadata(input, value, origin, work)
            if (db.homeRevision() != before) changed()
            return accepted
        }
        var selectedSeries: String? = null
        var knownIdentity = false
        try {
            val local = input.local
            val imported = local?.let(db::importedMatch)
            if (imported != null) {
                if (bind(imported, "server")) record("matched", Long.MAX_VALUE)
            } else if (local != null && local.ids.isEmpty() && input.seriesIds.isEmpty()) {
                record("local", Long.MAX_VALUE)
            } else {
                val query = MediaName.query(input, db.parentQuery(input.id))
                val stored = db.savedMetadata(input.id)
                val previousInputMatches = db.previousInputMatches(input)
                val savedId = if (!previousInputMatches) null else if (query.season != null) stored?.ids?.get("catalog-series") else stored?.ids?.get("catalog")
                knownIdentity = savedId != null
                val savedFingerprint = stored?.takeIf { it.matchMethod == "file_fingerprint" && previousInputMatches }
                val results = if (savedFingerprint != null) MetadataCandidates(savedFingerprint, emptyList())
                    else if (savedId != null) MetadataCandidates(MetadataRecord("catalog:${query.kind}:$savedId", query.kind,
                    stored?.show ?: stored?.title.orEmpty(), ids = mapOf("catalog" to savedId),
                    episodeHint = stored?.ids?.get("catalog-season")?.let { seasonId ->
                        if (stored.season != null && stored.episodes.isNotEmpty()) EpisodeIdentity(savedId, seasonId,
                            stored.ids.getValue("catalog"), stored.season, stored.episodes.first()) else null
                    }), emptyList())
                    else searchFile(input, query, language)
                val selected = results.selected
                selectedSeries = selected?.ids?.get("catalog-series") ?: selected?.ids?.get("catalog")?.takeIf { selected.kind == "series" }
                if (selected == null) record("review", clock() + MetadataProvider.DAY)
                else {
                    var result = provider.resolve(query, selected, language) { primary ->
                        // A changed/new identity becomes usable before auxiliary groups finish. Unchanged refreshes write once.
                        if (stored?.identity != primary.identity || stored.version != primary.version) {
                            val early = if (stored?.identity == primary.identity) primary.copy(
                                details = primary.details.retainFailed(stored.details),
                                seriesDetails = primary.seriesDetails?.retainFailed(stored.seriesDetails ?: LibraryDetails())) else primary
                            if (bind(early, "provider")) record("partial", early.retryAt)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (result.detailsPending && stored?.identity == result.identity) result = result.copy(
                        title = if (result.version == "identity") stored.title else result.title,
                        plot = result.plot ?: stored.plot, poster = result.poster ?: stored.poster,
                        details = if (result.version == "identity") stored.details else result.details.retainFailed(stored.details),
                        seriesDetails = result.seriesDetails?.retainFailed(stored.seriesDetails ?: LibraryDetails()) ?: stored.seriesDetails)
                    if (bind(result, "provider")) {
                        provider.acceptSeries(result)
                        record(if (result.detailsPending) "partial" else "matched",
                            if (result.detailsPending) result.retryAt.takeIf { it > clock() } ?: clock() + 60_000 else clock() + REFRESH_INTERVAL)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val f = failure as? MetadataFailure ?: MetadataFailure("network", clock() + 60_000)
            if (f.reason == "gone") selectedSeries?.let(provider::invalidateSeries)
            val state = when {
                f.retryable -> if (knownIdentity) "partial" else "waiting"
                f.reason == "gone" -> "waiting"
                f.reason in setOf("missing", "request", "coordinates", "kind", "id-conflict", "order") -> "review"
                else -> "failed"
            }
            val at = if (state in setOf("waiting", "partial")) f.retryAt.takeIf { it > clock() } ?: clock() else clock() + MetadataProvider.DAY
            record(state, at, f.diagnostic())
            if (f.attempted) android.util.Log.w("LibraryMetadata", f.diagnostic())
        }
    }
    internal suspend fun searchFile(input: MetadataInput, query: NameQuery, language: String): MetadataCandidates {
        val scope = db.metadataScope(input.id)
        if (input.locked || input.local != null || query.explicitId != null || query.order != "aired" ||
            !settings.quickHashEnabled || !provider.supportsFileFingerprint) return provider.search(query, language, scope)
        val file = db.fingerprintFile(input.id) ?: return provider.search(query, language, scope)
        var key = db.fingerprint(file)
        if (key == null && !file.cloud) {
            key = fingerprints.read(file)
            key?.let { if (!db.saveFingerprint(file, it)) key = null }
        }
        // Re-read the switch after IO: turning it off also suppresses a newly completed/cached key.
        if (!settings.quickHashEnabled) return provider.search(query, language, scope)
        if (key != null) return provider.searchFile(query, input.fileName, language, scope, key)
        val byName = provider.search(query, language, scope)
        if (byName.selected != null || !file.cloud || (file.size ?: 0) <= CLOUD_FINGERPRINT_MIN_BYTES || !settings.quickHashEnabled) return byName
        key = fingerprints.read(file) ?: return byName
        if (!db.saveFingerprint(file, key) || !settings.quickHashEnabled) return byName
        return provider.searchFile(query, input.fileName, language, scope, key)
    }
    /** One visible accepted item; no directory scan, search of unknown files, or job reset. */
    suspend fun refresh(id: Long, locale: String) = withContext(Dispatchers.IO) {
        repeat(3) { attempt ->
            if (!lock.tryLock()) return@withContext
            var retryAt = 0L
            try {
                val input = db.metadataInput(id) ?: return@withContext
                if (!db.providerBound(id) || !db.metadataDue(input, locale, clock())) return@withContext
                if (db.providerRetry(MetadataProvider.RATE_KEY) > clock()) return@withContext
                identifyOne(input, locale) { state, at, error ->
                    db.metadataAttempt(input, locale, state, at, error)
                    retryAt = if (state == "partial") at else 0
                }
            } finally { lock.unlock() }
            // Only short pending-content retries. Release all locks/permits while waiting;
            // the visible page owns cancellation, and longer failures stay persisted.
            val remaining = retryAt - clock()
            if (attempt == 2 || remaining !in 1..10_000 || db.providerRetry(MetadataProvider.RATE_KEY) > clock()) return@withContext
            wait(remaining); ensureActive()
        }
    }
    fun stop() { stopped = true; active?.cancel() }
    suspend fun restoreProgress(locale: String) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        try { mutable.value = metadataProgress(db.metadataCounts(locale), false) }
        finally { lock.unlock() }
    }
    suspend fun resumeJob(): MetadataJob? = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext null
        try {
            val job = db.metadataJob()
            // A paused discovery must first rebuild its directory readiness through explicit Resume.
            if (job?.scanRun != null && db.status().resumable) return@withContext null
            if (job != null && job.state != "complete") return@withContext job.takeIf {
                it.state in setOf("running", "waiting") && it.round < 3 && it.nextAt <= clock() + MAX_WAIT
            }
            db.dueMetadataLocale(clock())?.let { locale ->
                MetadataJob(locale, "waiting").also(db::metadataJob)
            }
        } finally { lock.unlock() }
    }
    companion object { const val MAX_WAIT = 120_000L; const val REFRESH_INTERVAL = 30 * MetadataProvider.DAY }
    suspend fun input(id: Long) = withContext(Dispatchers.IO) { db.metadataInput(id) }
    suspend fun apply(input: MetadataInput, record: MetadataRecord, origin: String = "manual") = withContext(Dispatchers.IO) {
        db.bindMetadata(input, record, origin).also { changed() }
    }
    suspend fun reset(id: Long) = withContext(Dispatchers.IO) { db.resetMetadata(id); changed() }
    suspend fun saved(id: Long) = withContext(Dispatchers.IO) { db.savedMetadata(id) }
    suspend fun retryQueries() = withContext(Dispatchers.IO) { lock.withLock { db.retryMetadata() } }
    suspend fun importFile(input: MetadataInput, uri: Uri) = withContext(Dispatchers.IO) {
        val parsed = context.contentResolver.openInputStream(uri)?.let(LocalNfo::read) ?: throw MetadataFailure("format")
        if (parsed.kind !in setOf("movie", "episode")) throw MetadataFailure("kind")
        apply(input, MetadataRecord.fromNfo(parsed, "import:${input.id}"), "import")
    }
    suspend fun profiles() = withContext(Dispatchers.IO) {
        val current = ServerDatabase(context).use { it.list().filter { p -> p.kind.mediaServer } }
        for (scope in db.importedScopes()) {
            if (current.none { scope.startsWith("${it.id}|${it.embyServerId}|${it.embyUserId}|${it.updatedAt}|") }) db.obsoleteImport(scope)
        }
        current
    }
    suspend fun libraries(profile: ServerProfile) = withContext(Dispatchers.IO) {
        EmbyClient.fromSettings(context, profile).use { it.page(EmbyLocation(EmbySection.LIBRARIES)).items }
    }
    suspend fun importServer(profile: ServerProfile, library: String): String = withContext(Dispatchers.IO) {
        val result = EmbyClient.fromSettings(context, profile).use { api ->
            api.verifyServer()
            ServerCatalogImporter(db).run(profile, library, { offset ->
                val current = profiles().firstOrNull { it.id == profile.id }
                if (current?.updatedAt != profile.updatedAt || current.embyUserId != profile.embyUserId) throw MetadataFailure("source")
                api.catalogPage(library, offset)
            })
        }
        var after = 0L
        while (true) {
            ensureActive(); val ids = db.metadataIds(after); if (ids.isEmpty()) break
            for (id in ids) {
                val input = db.metadataInput(id) ?: continue
                val local = input.local ?: MediaName.parse(input.fileName).let { q ->
                    if (q.explicitId == null || q.season != null) null else MetadataRecord("filename:$id", "movie", q.title, ids = mapOf("tmdb" to q.explicitId))
                } ?: continue
                val record = db.importedMatch(local) ?: continue
                db.bindMetadata(input, record, "server")
            }
            after = ids.last()
        }
        changed(); result
    }
    suspend fun importedScopes() = withContext(Dispatchers.IO) { db.importedScopes() }
    suspend fun importedPage(scope: String, before: Long = Long.MAX_VALUE) = withContext(Dispatchers.IO) { db.importedPage(scope, before) }
}
