package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.content.Context
import android.net.Uri
import cn.coldlake.stellar.opensource.medialibrary.library.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import com.stellar.player.nativeio.NativeLibraryIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/** A credential-free locator. The connection is resolved only for reads/playback. */
internal object NasLocator {
    const val SCHEME = "stellar-nas"
    fun root(path: LibraryPath, profile: ServerProfile?) = Uri.Builder().scheme(SCHEME).authority(path.sourceId)
        .appendQueryParameter("scope", path.scope).appendQueryParameter("revision", (profile?.updatedAt ?: 0).toString()).build().toString()
    fun file(root: String, path: String) = Uri.parse(root).buildUpon().appendQueryParameter("path", path).build().toString()
    fun isNas(uri: String) = uri.startsWith("$SCHEME:")
    fun profile(uri: String, profiles: List<ServerProfile>): ServerProfile {
        val value = Uri.parse(uri)
        val profile = profiles.firstOrNull { it.id == value.authority } ?: throw IOException("NAS connection removed")
        val path = value.getQueryParameter("path") ?: if (profile.kind == ServerKind.WEBDAV) profile.address else ""
        if (profile.kind !in setOf(ServerKind.WEBDAV, ServerKind.SMB, ServerKind.NFS) ||
            remoteLibraryPath(profile, path).scope != value.getQueryParameter("scope")) throw IOException("NAS source changed")
        return profile
    }
    fun path(uri: String): String = Uri.parse(uri).getQueryParameter("path") ?: throw IOException("NAS path missing")
}

internal fun ServerProfile.scanConnection() = NativeLibraryIo.Connection(
    host = if (kind == ServerKind.SMB) hostPort() else address,
    share = if (kind == ServerKind.SMB) share else path,
    user = username, password = password, domain = domain,
    nfsVersion = if (kind == ServerKind.NFS) nfsVersion else 0,
    port = port.toInt(), uid = uid.toIntOrNull() ?: -1, gid = gid.toIntOrNull() ?: -1)

internal class NasLibrarySource(private val context: Context, private val profiles: List<ServerProfile>,
    private val scanClients: WebDavScanClients? = null) : LibraryFileSource {
    private suspend fun <T> withDav(profile: ServerProfile, address: String, action: suspend (WebDavClient) -> T): T =
        if (scanClients != null) scanClients.use(profile, action) else WebDavClient.fromSettings(context, address,
            profile.username.ifEmpty { null }, profile.password.takeIf { profile.username.isNotEmpty() }).use { action(it) }
    override fun enumerate(root: ScanRoot, directory: String) = channelFlow {
        val profile = NasLocator.profile(NasLocator.file(root.treeUri, directory), profiles)
        var batch = ArrayList<FileFact>(128)
        var bytes = 0
        var complete = true
        suspend fun accept(fact: FileFact) {
            val estimate = (fact.objectId.length + fact.name.length + (fact.version?.length ?: 0)) * 4 + 96
            if (estimate > 1024 * 1024) throw IOException("NAS entry exceeds batch budget")
            if (batch.isNotEmpty() && (batch.size == 128 || bytes + estimate > 1024 * 1024)) {
                send(DiscoveryBatch(batch)); batch = ArrayList(128); bytes = 0
            }
            batch.add(fact); bytes += estimate
        }
        if (profile.kind == ServerKind.WEBDAV) {
            try {
                withDav(profile, directory) { client ->
                    client.scanDirectory(directory) { e -> accept(FileFact(e.address, e.name,
                        if (e.directory) FileKind.DIRECTORY else SafLibrarySource.kind(e.name, ""), e.size, e.modified, e.version)) }
                }
            } catch (_: org.xmlpull.v1.XmlPullParserException) { throw IOException("Incomplete DAV response") }
        } else {
            NativeLibraryIo.enumerate(profile.scanConnection(), directory.trim('/')) { entries ->
                for (e in entries) {
                    if (e.name == "." || e.name == "..") continue
                    if (!validRemoteName(e.name)) {
                        // Unsupported path characters are outside the playable namespace. An
                        // undecodable identity is different: retain incomplete coverage for it.
                        if (e.name.isEmpty() || '\uFFFD' in e.name) complete = false
                        continue
                    }
                    // Reparse points/symlinks are not traversed or presented as playable files.
                    if (!e.directory && !e.regular) continue
                    accept(FileFact(listOf(directory.trim('/'), e.name).filter(String::isNotEmpty).joinToString("/"), e.name,
                        if (e.directory) FileKind.DIRECTORY else SafLibrarySource.kind(e.name, ""),
                        e.size.takeIf { it >= 0 }, e.modified.takeIf { it >= 0 }))
                }
            }
        }
        if (batch.isNotEmpty()) send(DiscoveryBatch(batch))
        send(DiscoveryBatch(emptyList(), complete, if (complete) Coverage.COMPLETE else Coverage.PARTIAL))
    }.buffer(0).flowOn(Dispatchers.IO)

    override suspend fun open(root: ScanRoot, objectId: String) = try {
        read(NasLocator.file(root.treeUri, objectId), LocalNfo.MAX_BYTES).inputStream()
    } catch (error: cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavFailure) {
        if (error.reason == cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavError.LIMIT) throw LocalNfo.InvalidContent()
        throw error
    } catch (_: NativeLibraryIo.ReadLimitExceeded) { throw LocalNfo.InvalidContent() }

    suspend fun read(uri: String, limit: Int): ByteArray {
        val profile = NasLocator.profile(uri, profiles)
        val path = NasLocator.path(uri)
        return if (profile.kind == ServerKind.WEBDAV) withDav(profile, path) { it.readSmallFile(limit, path) }
        else NativeLibraryIo.readSmall(profile.scanConnection(), path.trim('/'), limit)
    }
}

internal class RegisteredLibrarySource(context: Context, profiles: List<ServerProfile>, cloudIndex: cn.coldlake.stellar.opensource.medialibrary.cloud.CloudIndex? = null) : LibraryFileSource, java.io.Closeable {
    private val local = SafLibrarySource(context.contentResolver)
    private val scanClients = WebDavScanClients { profile -> WebDavClient.fromSettings(context, profile.address,
        profile.username.ifEmpty { null }, profile.password.takeIf { profile.username.isNotEmpty() }) }
    private val nas = NasLibrarySource(context, profiles, scanClients)
    private val cloud = cloudIndex?.let { cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLibrarySource(context, profiles, it) }
    private fun source(root: ScanRoot): LibraryFileSource = when {
        cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(root.treeUri) -> checkNotNull(cloud)
        NasLocator.isNas(root.treeUri) -> nas
        else -> local
    }
    override fun enumerate(root: ScanRoot, directory: String) = source(root).enumerate(root, directory)
    override suspend fun open(root: ScanRoot, objectId: String) = source(root).open(root, objectId)
    override fun close() = scanClients.close()
}
