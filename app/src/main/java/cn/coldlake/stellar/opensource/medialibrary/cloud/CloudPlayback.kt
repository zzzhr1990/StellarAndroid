package cn.coldlake.stellar.opensource.medialibrary.cloud

import com.stellar.player.source.StreamSource
import java.io.Closeable
import java.util.UUID

/** Session identity and provider lifetime; no listening socket or temporary HTTP URL. */
internal class CloudPlayback(private val client: CloudMediaClient, private val fileId: String,
    provider: String = "drive", private val profileId: String? = null) : StreamSource, Closeable {
    val id = "$provider-session:${UUID.randomUUID()}"
    private val readers = mutableSetOf<CloudStreamReader>()
    private var closed = false
    suspend fun recordLocator(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val profile = profileId ?: return@withContext null
        val provider = client as? CloudProvider ?: return@withContext null
        try { provider.metadata(fileId).let { CloudLocator.uri(profile, it.ref, it.version) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    override fun createReader(): CloudStreamReader = synchronized(readers) {
        check(!closed)
        lateinit var reader: CloudStreamReader
        reader = CloudStreamReader(client, fileId) { synchronized(readers) { readers -= reader } }
        readers += reader
        reader
    }
    override fun close() {
        val active = synchronized(readers) {
            if (closed) return
            closed = true
            readers.toList()
        }
        active.forEach { it.cancel() }
        client.close()
    }
}
