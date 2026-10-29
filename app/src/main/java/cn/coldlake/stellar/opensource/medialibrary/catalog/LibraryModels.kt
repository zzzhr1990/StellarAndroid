package cn.coldlake.stellar.opensource.medialibrary.catalog

import java.io.InputStream

/** Provider IDs remain opaque. A grant URI is a locator, never the file identity. */
internal data class ScanRoot(val id: String, val scope: String, val documentId: String,
    val treeUri: String, val revision: Long, val label: String)
internal enum class FileKind { DIRECTORY, VIDEO, NFO, IMAGE, OTHER }
internal data class FileFact(val objectId: String, val name: String, val kind: FileKind,
    val size: Long?, val modified: Long?, val version: String? = null) {
    val stem get() = name.substringBeforeLast('.', name).lowercase(java.util.Locale.ROOT)
}
internal enum class Coverage { COMPLETE, PARTIAL, FAILED, CANCELLED }
internal data class DiscoveryBatch(val entries: List<FileFact>, val complete: Boolean = false,
    val coverage: Coverage = Coverage.PARTIAL)
internal interface LibraryFileSource {
    /** Flow collection owns and closes the enumeration session; no persistent SAF cursor offset. */
    fun enumerate(root: ScanRoot, directory: String): kotlinx.coroutines.flow.Flow<DiscoveryBatch>
    suspend fun open(root: ScanRoot, objectId: String): InputStream
}
internal data class ScanClaim(val run: Long, val epoch: Long)
internal data class DirectoryWork(val scope: String, val documentId: String, val root: ScanRoot, val phase: String)
internal data class CatalogCard(val id: Long, val title: String, val fileName: String, val uri: String,
    val kind: String = "file", val year: Int? = null, val plot: String? = null,
    val show: String? = null, val season: Int? = null, val episode: Int? = null,
    val poster: String? = null, val posterVersion: String? = null,
    val metadataSource: String? = null, val locked: Boolean = false, val episodes: List<Int> = emptyList())
internal data class ScanStatus(val run: Long = 0, val state: String = "idle", val discovered: Long = 0,
    val parsed: Long = 0, val directory: String = "", val roots: Int = 0, val error: Boolean = false) {
    val running get() = state == "running"
    val resumable get() = state == "paused"
}
internal data class NfoData(val kind: String, val title: String?, val year: Int?, val plot: String?,
    val show: String?, val season: Int?, val episode: Int?, val providerIds: String?, val details: LibraryDetails = LibraryDetails())
internal data class NfoWork(val id: Long, val objectId: String, val revision: Long)
internal class ParsedNfo(val item: NfoWork, val data: NfoData?) {
    val details = data?.details?.json()?.toString()
    // Bound retained parsed payloads as well as record count. Encoded details are reused at commit.
    val estimatedBytes = 512L + 4L * listOf(data?.title, data?.plot, data?.show, data?.providerIds, details).sumOf { it?.length?.toLong() ?: 0 }
}
internal data class RootScanHistory(val attempted: Long?, val completed: Long?, val outcome: String?)
internal class ObsoleteScan : kotlinx.coroutines.CancellationException("Scan claim is no longer current")
