package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.medialibrary.cloud.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

/** One scan budget, independent sources, and a single final absence settlement. */
internal class LibraryDiscovery(
    private val catalog: CatalogDatabase,
    private val source: LibraryFileSource,
    private val index: CloudIndex,
    private val provider: (ScanRoot) -> CloudProvider,
    private val workers: Int = 1,
    private val directoryReady: suspend () -> Unit = {},
    private val progress: suspend () -> Unit = {},
) {
    suspend fun execute(claim: ScanClaim) = coroutineScope {
        val permits = Semaphore(workers.coerceIn(1, 4))
        val scanner = LibraryScanner(catalog, source, workers, permits, directoryReady, progress)
        val earlySource = object : LibraryFileSource {
            override fun enumerate(root: ScanRoot, directory: String) = cloudDirectoryEntries(index, root, directory, listedOnly = true)
            override suspend fun open(root: ScanRoot, objectId: String) = source.open(root, objectId)
        }
        val earlyScanner = LibraryScanner(catalog, earlySource, 1, permits, directoryReady, progress)
        val cloud = CloudLibrarySync(index, catalog, provider, workers, permits,
            directoryReady = { scope, directory ->
                catalog.cloudDirectory(claim, scope, directory)?.let { earlyScanner.scanDirectory(claim, it) }
            },
            scopeReady = { scope ->
                catalog.restartCloudScope(claim, scope)
                scanner.drain(claim, scope = scope)
            }, progress = progress)
        listOf(
            async { scanner.drain(claim, localOnly = true) },
            async { cloud.execute(claim) },
        ).awaitAll()
        // Also handles dependent re-projection requested by final sidecar/root settlement.
        scanner.execute(claim)
    }
}
