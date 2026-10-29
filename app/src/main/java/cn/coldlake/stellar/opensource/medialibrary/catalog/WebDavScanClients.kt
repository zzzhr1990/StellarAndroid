package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import cn.coldlake.stellar.opensource.medialibrary.webdav.WebDavClient
import java.io.Closeable

/** A scan owns immutable profiles and at most four reusable DAV sessions. No global auth cache. */
internal class WebDavScanClients(private val create: (ServerProfile) -> WebDavClient) : Closeable {
    private class Entry(val client: WebDavClient, var users: Int = 0)
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var closed = false

    suspend fun <T> use(profile: ServerProfile, action: suspend (WebDavClient) -> T): T {
        val entry = synchronized(this) {
            check(!closed)
            entries[profile.id]?.also { it.users++ } ?: run {
                if (entries.size == 4) entries.entries.firstOrNull { it.value.users == 0 }?.let {
                    entries.remove(it.key); it.value.client.close()
                }
                Entry(create(profile), 1).also { if (entries.size < 4) entries[profile.id] = it }
            }
        }
        try { return action(entry.client) }
        finally {
            synchronized(this) {
                entry.users--
                if (entries[profile.id] !== entry) entry.client.close()
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        entries.values.forEach { it.client.close() }
        entries.clear()
    }
}
