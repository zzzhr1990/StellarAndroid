package cn.coldlake.stellar.opensource.medialibrary.cloud

import android.content.Context
import cn.coldlake.stellar.opensource.medialibrary.catalog.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import java.io.IOException
import cn.coldlake.stellar.opensource.network.HttpRateLimited

internal class CloudLibrarySync(private val index: CloudIndex, private val catalog: CatalogDatabase,
    private val provider: (ScanRoot) -> CloudProvider, private val workers: Int = 1,
    private val progress: suspend () -> Unit = {}) {
    val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    suspend fun execute(claim: ScanClaim) {
        // Different accounts/drives may progress together; each feed/checkpoint remains ordered.
        val scopes = catalog.roots().filter { CloudLocator.isCloud(it.treeUri) }.groupBy { it.scope }.entries.toList()
        for (batch in scopes.chunked(workers.coerceIn(1, 4))) coroutineScope {
            batch.map { (scope, roots) -> async { synchronizeScope(claim, scope, roots) } }.awaitAll()
        }
    }
    private suspend fun synchronizeScope(claim: ScanClaim, scope: String, roots: List<ScanRoot>) {
            try {
                provider(roots.first()).use { client ->
                    val signature = roots.sortedBy { it.id }.joinToString(";") { "${it.id}:${it.revision}:${it.documentId}" }
                    suspend fun prepare(reset: Boolean) {
                        val seed = if (reset || index.needsBaseline(scope, signature)) client.start() else null
                        catalog.checkClaim(claim)
                        index.prepare(scope, signature, seed, client.capabilities.initialDelta, roots.map { it.documentId }, reset)
                        val metadata = mutableListOf<CloudObject>()
                        for (root in roots) {
                            val obj = try { client.metadata(root.documentId) }
                            catch (failure: DirectoryFailure) {
                                // A previously selected folder may have been deleted. Let the
                                // authoritative feed settle it; a metadata 404 alone is not a sweep.
                                if (failure.reason == ServerError.PATH && index.known(scope, root.documentId)) continue
                                throw failure
                            }
                            if (CloudLocator.scope(obj.ref) != root.scope || obj.unsupported) throw IOException("Cloud scope changed")
                            metadata += obj
                        }
                        catalog.checkClaim(claim); index.remember(scope, metadata)
                    }
                    prepare(false)
                    var reset = false
                    while (true) {
                        try { synchronize(claim, scope, roots, client); break }
                        catch (expired: CloudCursorExpired) {
                            if (reset) throw expired
                            reset = true; prepare(true)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (limited: HttpRateLimited) { throw limited }
            catch (error: Exception) {
                failures += if (error is DirectoryFailure) error.reason.name else error.javaClass.simpleName
                catalog.failScope(claim, scope)
            }
            progress()
    }
    private suspend fun synchronize(claim: ScanClaim, scope: String, roots: List<ScanRoot>, client: CloudProvider) {
        val rootIds = roots.map { it.documentId }
        var compensationRounds = 0
        while (true) {
            currentCoroutineContext().ensureActive(); catalog.checkClaim(claim)
            var listed = false
            while (true) {
                val directory = index.directory(scope) ?: break
                val page = client.children(directory.id, directory.cursor)
                validateScope(page, roots.first())
                catalog.checkClaim(claim); index.page(scope, page, directory)
                preview(claim, scope, roots, page.objects); progress(); listed = true
            }
            val state = checkNotNull(index.state(scope))
            if (state.phase == "directories" || listed && state.phase == "compensated") index.feed(scope)
            while (index.state(scope)?.phase == "feed") {
                val page = client.changes(index.state(scope)?.cursor)
                validateScope(page, roots.first())
                catalog.checkClaim(claim); index.page(scope, page)
                preview(claim, scope, roots, page.objects); progress()
            }
            // This queue is committed with every feed page. A crash after the page cannot
            // lose an unknown-parent lookup or the discovery of a moved-in directory.
            while (true) {
                currentCoroutineContext().ensureActive(); catalog.checkClaim(claim)
                val pending = index.pending(scope)
                if (pending.isEmpty()) break
                val ancestors = linkedMapOf<String, CloudObject>()
                if (!client.capabilities.initialDelta) {
                    for (item in pending) {
                        var parent = item.parent
                        val visited = mutableSetOf<String>()
                        while (parent != null && parent !in rootIds && visited.add(parent)) {
                            currentCoroutineContext().ensureActive()
                            if (visited.size > 512) throw IOException("Cloud ancestry exceeds supported depth")
                            if (parent !in ancestors && !index.known(scope, parent)) {
                                val obj = client.metadata(parent)
                                validateScope(CloudPage(listOf(obj), null), roots.first())
                                ancestors[parent] = obj
                                // Bound staged metadata even for a batch with many deep ancestry chains.
                                // Early ancestor commits are safe: pending work is still durable until resolve.
                                if (ancestors.size == CLOUD_SYNC_BATCH_SIZE) {
                                    currentCoroutineContext().ensureActive(); catalog.checkClaim(claim)
                                    index.remember(scope, ancestors.values); ancestors.clear()
                                }
                            }
                            parent = if (parent in ancestors) ancestors.getValue(parent).parent else index.parent(scope, parent)
                        }
                    }
                }
                currentCoroutineContext().ensureActive(); catalog.checkClaim(claim)
                index.resolve(scope, pending, ancestors.values, rootIds, !client.capabilities.initialDelta)
            }
            if (index.directory(scope) == null) break
            if (++compensationRounds > 8) throw IOException("Cloud tree keeps changing; resume later")
        }
        catalog.checkClaim(claim); index.complete(scope); catalog.cloudReady(claim, scope)
        roots.filter { index.unsupportedCount(scope, it.documentId) > 0 }.forEach { catalog.cloudPartial(claim, it.id) }
    }
    private fun validateScope(page: CloudPage, root: ScanRoot) {
        val expected = CloudLocator.ref(root.treeUri)
        if (page.objects.any { it.ref.provider != expected.provider || it.ref.account != expected.account ||
            it.ref.drive.isNotEmpty() && it.ref.drive != expected.drive && !it.unsupported }) throw IOException("Cloud page scope mismatch")
    }
    private fun preview(claim: ScanClaim, scope: String, roots: List<ScanRoot>, objects: List<CloudObject>) {
        val byParent = objects.filter { !it.deleted && !it.unsupported && it.parent != null }.groupBy { it.parent!! }
        for (root in roots) {
            byParent.forEach { (parent, entries) ->
                if (!index.belongs(scope, parent, listOf(root.documentId))) return@forEach
                entries.chunked(128).forEach { chunk ->
                    catalog.commitBatch(claim, DirectoryWork(scope, parent, root, "listing"), chunk.map {
                        FileFact(it.ref.id, it.name, fileKind(it.name, it.kind), it.size, it.modified, it.version)
                    })
                }
            }
        }
    }
}
