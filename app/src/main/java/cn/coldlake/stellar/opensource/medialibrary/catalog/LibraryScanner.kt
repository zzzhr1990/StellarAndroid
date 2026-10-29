package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import cn.coldlake.stellar.opensource.medialibrary.library.LibraryDatabase
import cn.coldlake.stellar.opensource.medialibrary.library.LibraryPathType
import cn.coldlake.stellar.opensource.medialibrary.library.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import cn.coldlake.stellar.opensource.network.HttpRateLimited
import cn.coldlake.stellar.opensource.network.MAX_RATE_WAIT

internal class LibraryScanner(private val catalog: CatalogDatabase, private val source: LibraryFileSource,
    private val workers: Int = 1,
    private val directoryReady: suspend () -> Unit = {},
    private val progress: suspend () -> Unit = {}) {
    suspend fun execute(claim: ScanClaim) {
        while (true) {
            currentCoroutineContext().ensureActive()
            // A single coordinator selects a bounded batch; no worker can take the same directory.
            val batch = mutableListOf<DirectoryWork>()
            repeat(workers.coerceIn(1, 4)) { catalog.next(claim, batch)?.let(batch::add) }
            if (batch.isEmpty()) {
                if (catalog.finish(claim) != "running") return
                continue
            }
            // awaitAll also propagates a source's CancellationException; launch/join
            // treats a cancelled child as normal and would select the same work again.
            coroutineScope { batch.map { work -> async { scanDirectory(claim, work) } }.awaitAll() }
        }
    }
    private suspend fun scanDirectory(claim: ScanClaim, work: DirectoryWork) {
            var listed = work.phase == "metadata"
            try {
                if (work.phase != "metadata") {
                    catalog.startDirectory(claim, work)
                    var complete = false
                    // catch is upstream of collect: provider/setup/parsing failures are local,
                    // while database/progress failures still terminate the run.
                    flow { emitAll(source.enumerate(work.root, work.documentId)) }
                        .catch { error -> throw sourceFailure(error) }.collect { batch ->
                        currentCoroutineContext().ensureActive()
                        if (batch.entries.isNotEmpty()) catalog.commitBatch(claim, work, batch.entries)
                        if (batch.complete && batch.coverage == Coverage.COMPLETE) complete = true
                        progress()
                    }
                    catalog.listed(claim, work, complete)
                    if (!complete) return
                    listed = true
                }
                var after = 0L
                while (true) {
                    val nfos = catalog.pendingNfo(claim, work, after)
                    if (nfos.isEmpty()) break
                    val parsed = ArrayList<ParsedNfo>(nfos.size)
                    var bytes = 0L
                    try { for (nfo in nfos) {
                        currentCoroutineContext().ensureActive()
                        val data = try { LocalNfo.read(source.open(work.root, nfo.objectId)) }
                            catch (_: org.xmlpull.v1.XmlPullParserException) { null }
                            catch (_: LocalNfo.InvalidContent) { null }
                            catch (error: Exception) { throw sourceFailure(error) }
                        currentCoroutineContext().ensureActive()
                        val result = ParsedNfo(nfo, data)
                        if (parsed.isNotEmpty() && bytes + result.estimatedBytes > LocalNfo.MAX_BYTES) {
                            catalog.parsedBatch(claim, parsed); parsed.clear(); bytes = 0
                        }
                        parsed.add(result); bytes += result.estimatedBytes
                    } } catch (failure: LibrarySourceFailure) {
                        // Preserve successfully read siblings before retrying the failed sidecar next scan.
                        if (parsed.isNotEmpty()) catalog.parsedBatch(claim, parsed)
                        throw failure
                    }
                    catalog.parsedBatch(claim, parsed)
                    after = nfos.last().id
                    progress()
                }
                after = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    after = catalog.project(claim, work, after) ?: break
                    progress()
                    yield()
                }
                catalog.directoryDone(claim, work)
                directoryReady()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LibrarySourceFailure) {
                if (listed) catalog.metadataFailed(claim, work) else catalog.listed(claim, work, false)
                // The persisted frontier identifies the directory. Never log response text, URLs or credentials.
                android.util.Log.w("LibraryScan", "Source failure run=${claim.run} root=${work.root.id} type=${error.message}")
            }
            progress()
    }

    private class LibrarySourceFailure(type: String) : IOException(type)
    private suspend fun sourceFailure(error: Throwable): LibrarySourceFailure {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException || error is HttpRateLimited || error !is Exception) throw error
        return LibrarySourceFailure(error.javaClass.simpleName)
    }
    companion object { const val RATE_KEY = "scan|rate|1" }
}

/** Application lifetime only; the service owns the active Job, SQLite owns resumable work. */
internal class LocalLibraryRuntime private constructor(private val context: Context, name: String = "library_catalog.db", private val registered: Boolean = true,
    private val registry: LibraryRepository = cn.coldlake.stellar.opensource.medialibrary.cloud.LibraryRegistry(context)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val catalog = if (name == "library_catalog.db") CatalogDatabase.shared(context) else CatalogDatabase(context, name)
    val metadata by lazy { LibraryMetadata(context, catalog, changed = { publish(true) }) }
    private val cloudIndex by lazy { cn.coldlake.stellar.opensource.medialibrary.cloud.CloudIndex(context) }
    private val servers = cn.coldlake.stellar.opensource.medialibrary.servers.ServerDatabase(context)
    private val mutableProfiles = MutableStateFlow(emptyMap<String,cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile>())
    val serverProfiles = mutableProfiles.asStateFlow()
    private val mutable = MutableStateFlow(ScanStatus())
    val status = mutable.asStateFlow()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private val mutableHomeUpdates = MutableStateFlow(HomeUpdates())
    val homeUpdates = mutableHomeUpdates.asStateFlow()
    private val runLock = Mutex()
    @Volatile private var active: Job? = null
    private var lastPublish = 0L
    private var publishedProjection = -1L
    private val ready = scope.async {
        catalog.initialize(); if (registered) synchronizeRoots(); publish(true)
    }
    init {
        scope.launch {
            ready.await()
            while (catalog.projectHomeBatch()) { publish(); yield() }
            publish(true)
        }
    }
    private fun synchronizeRoots() {
        val profiles = servers.list().associateBy { it.id }
        mutableProfiles.value = profiles
        catalog.syncRoots(registry.list().map {
            if (it.type == LibraryPathType.LOCAL) {
                val uri = Uri.parse(it.path)
                ScanRoot(it.id, "${it.sourceId}|${it.scope}", DocumentsContract.getTreeDocumentId(uri), it.path, it.revision, it.displayPath)
            } else if (cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(it.path)) {
                val profile = profiles[it.sourceId]
                ScanRoot(it.id, it.scope, cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.ref(it.path).id,
                    it.path, it.revision + (profile?.updatedAt ?: 0), it.displayPath)
            } else {
                val profile = profiles[it.sourceId]
                ScanRoot(it.id, "${it.sourceId}|${it.scope}", if (it.type == LibraryPathType.WEBDAV) it.path.trimEnd('/') + "/" else it.path.trim('/'),
                    NasLocator.root(it, profile), it.revision + (profile?.updatedAt ?: 0), it.displayPath)
            }
        })
    }
    @Synchronized private fun publish(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < 300) return
        lastPublish = now
        mutable.value = catalog.status()
        mutableHomeUpdates.value = catalog.homeUpdates()
        val projection = catalog.homeRevision()
        if (force || projection != publishedProjection) {
            publishedProjection = projection
            mutableChanges.value += 1
        }
    }
    @Synchronized private fun publishPlayback() {
        mutableHomeUpdates.value = catalog.homeUpdates()
        val revision = catalog.homeRevision()
        if (revision != publishedProjection) { publishedProjection = revision; mutableChanges.value += 1 }
    }
    fun refreshRoots() = scope.launch {
        try {
            ready.await(); synchronizeRoots()
            if (catalog.status().state == "cancelled") active?.cancel()
            while (catalog.projectHomeBatch()) { publish(); yield() }
            publish(true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
    }
    suspend fun runScan(resume: Boolean, force: Boolean = false, locale: String = metadataLanguage(
        cn.coldlake.stellar.opensource.platform.LanguagePreference.wrap(context).resources.configuration.locales[0].toLanguageTag())) = withContext(Dispatchers.IO) {
        if (!runLock.tryLock()) return@withContext
        try {
            ready.await(); synchronizeRoots()
            val claim = catalog.begin(resume, force) ?: return@withContext
            active = currentCoroutineContext()[Job]
            publish(true)
            val language = catalog.prepareScanMetadata(claim, locale)
            supervisorScope {
                val wake = Channel<Unit>(Channel.CONFLATED)
                val consumer = async { metadata.identifyScan(claim.run, language, wake) }
                try {
                    val retryAt = catalog.providerRetry(LibraryScanner.RATE_KEY)
                    val remaining = retryAt - System.currentTimeMillis()
                    if (remaining > MAX_RATE_WAIT) throw HttpRateLimited(retryAt)
                    if (remaining > 0) delay(remaining)
                    if (retryAt > 0) catalog.clearProviderRetry(LibraryScanner.RATE_KEY)
                    val profiles = serverProfiles.value.values.toList()
                    val workers = cn.coldlake.stellar.opensource.settings.LibraryScanSettings(context).scanThreads
                    cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLibrarySync(cloudIndex, catalog, { root ->
                        cn.coldlake.stellar.opensource.medialibrary.cloud.cloudProvider(context,
                            cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.profile(root.treeUri, profiles))
                    }, workers = workers) { publish() }.execute(claim)
                    RegisteredLibrarySource(context, profiles, cloudIndex).use { source ->
                        LibraryScanner(catalog, source, workers = workers, directoryReady = { wake.trySend(Unit) }) { publish() }.execute(claim)
                    }
                    while (catalog.projectHomeBatch()) { publish(); yield() }
                } catch (limited: HttpRateLimited) {
                    catalog.providerRetry(LibraryScanner.RATE_KEY, limited.retryAt)
                    catalog.stop("paused")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { catalog.stop("failed") }
                finally { wake.close(); publish(true) }
                consumer.await()
            }
        } catch (limited: HttpRateLimited) {
            catalog.providerRetry(LibraryScanner.RATE_KEY, limited.retryAt)
            catalog.stop("paused")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { if (catalog.status().running) catalog.stop("paused") }
            throw cancelled
        } catch (_: Exception) {
            runCatching { catalog.stop("failed") }
            mutable.value = mutable.value.copy(state = "failed", error = true)
        } finally {
            active = null
            runCatching { publish(true) }
            runLock.unlock()
        }
    }
    fun stop(cancel: Boolean = false): Job {
        metadata.stop() // Mark explicit cancellation before the foreground service can destroy its coroutine.
        return scope.launch {
            ready.await()
            catalog.stop(if (cancel) "cancelled" else "paused")
            active?.cancel()
            publish(true)
        }
    }
    fun launchFailed() { mutable.value = mutable.value.copy(error = true) }
    suspend fun configurationChanging() = withContext(Dispatchers.IO) {
        ready.await(); metadata.stop(); catalog.stop("cancelled"); active?.cancel(); publish(true)
    }
    suspend fun page(before: Long = Long.MAX_VALUE) = withContext(Dispatchers.IO) { ready.await(); catalog.page(before) }
    suspend fun identify(locale: String, resume: Boolean = false) = withContext(Dispatchers.IO) {
        ready.await()
        val job = if (resume) catalog.metadataJob() else null
        if (job?.scanRun != null) {
            val wake = Channel<Unit>(Channel.CONFLATED).apply { close() }
            metadata.identifyScan(job.scanRun, job.locale, wake)
        } else metadata.identify(locale, resume)
    }
    suspend fun history() = withContext(Dispatchers.IO) { ready.await(); catalog.history() }
    suspend fun refreshCards(ids: List<Long>) = withContext(Dispatchers.IO) { ready.await(); catalog.cardsById(ids) }
    suspend fun <T> home(block: CatalogHome.() -> T): T = withContext(Dispatchers.IO) { ready.await(); catalog.home(block) }
    suspend fun recordProgress(id: Long, position: Long, duration: Long, watched: Boolean) = withContext(Dispatchers.IO) {
        if (instance === this@LocalLibraryRuntime) cn.coldlake.stellar.opensource.playback.reporting.PlaybackReportingRuntime.get(context)
            .watchCommand(id, position, duration, watched)
        else catalog.saveProgress(id, position, duration, watched)
        publish(true)
    }
    suspend fun awaitReady() { ready.await() }
    suspend fun serverVersions(card: CatalogCard): List<Long> = withContext(Dispatchers.IO) {
        ready.await(); val input=catalog.serverRecord(card.id) ?: return@withContext emptyList()
        val versions=CatalogServerLocator.versions(context,card.uri)
        val ids=catalog.serverVersions(card.id,input,versions)
        while(catalog.projectHomeBatch()) { yield() }; publish(true); ids
    }
    fun changed() { publish(true) }
    fun scanProgress() { publish() }
    fun close() { scope.cancel(); catalog.close(); servers.close() }
    companion object {
        fun playbackChanged() { instance?.publishPlayback() }
        fun isolated(context: Context, name: String) = LocalLibraryRuntime(context.applicationContext, name, false)
        fun isolated(context: Context, name: String, registry: LibraryRepository) = LocalLibraryRuntime(context.applicationContext, name, true, registry)
        @Volatile private var instance: LocalLibraryRuntime? = null
        fun get(context: Context): LocalLibraryRuntime = instance ?: synchronized(this) {
            instance ?: LocalLibraryRuntime(context.applicationContext).also { instance = it }
        }
    }
}
