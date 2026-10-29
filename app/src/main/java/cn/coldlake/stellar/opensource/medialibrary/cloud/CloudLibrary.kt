package cn.coldlake.stellar.opensource.medialibrary.cloud

import android.content.Context
import android.net.Uri
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cn.coldlake.stellar.opensource.medialibrary.catalog.*
import cn.coldlake.stellar.opensource.medialibrary.library.*
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** Stable provider/account/drive identity; object IDs never acquire path-prefix semantics. */
internal object CloudLocator {
    fun isCloud(value: String) = value.startsWith("stellar-cloud:")
    fun uri(server: String, ref: CloudRef, version: String? = null) = Uri.Builder().scheme("stellar-cloud").authority(server)
        .appendQueryParameter("provider", ref.provider.name).appendQueryParameter("account", ref.account)
        .appendQueryParameter("drive", ref.drive).appendQueryParameter("object", ref.id)
        .apply { version?.let { appendQueryParameter("version", it) } }.build().toString()
    fun ref(value: String): CloudRef = Uri.parse(value).let {
        CloudRef(CloudKind.valueOf(requireNotNull(it.getQueryParameter("provider"))),
            requireNotNull(it.getQueryParameter("account")), requireNotNull(it.getQueryParameter("drive")),
            requireNotNull(it.getQueryParameter("object")))
    }
    fun file(value: String, id: String) = uri(Uri.parse(value).authority!!, ref(value).copy(id = id))
    fun scope(ref: CloudRef) = Uri.Builder().scheme("cloud-scope").authority(ref.provider.name.lowercase())
        .appendPath(ref.account).appendPath(ref.drive).build().toString()
    fun profile(value: String, profiles: List<ServerProfile>): ServerProfile {
        val ref = ref(value)
        return profiles.firstOrNull { it.id == Uri.parse(value).authority &&
            if (ref.provider == CloudKind.DRIVE) it.kind == ServerKind.GOOGLE_DRIVE && it.username == ref.account
            else it.kind == ServerKind.ONEDRIVE && it.cloudAccountId == ref.account }
            ?: throw IOException("Cloud account removed or changed")
    }
}

internal suspend fun cloudLibraryPath(context: Context, profile: ServerProfile, id: String): LibraryPath =
    cloudProvider(context, profile).use { provider ->
        val obj = provider.metadata(id)
        if (obj.deleted || obj.unsupported || obj.kind != RemoteFileKind.FOLDER) throw IOException("Unsupported cloud root")
        LibraryPath(type = if (profile.kind == ServerKind.GOOGLE_DRIVE) LibraryPathType.GOOGLE_DRIVE else LibraryPathType.ONEDRIVE,
            sourceId = profile.id, scope = CloudLocator.scope(obj.ref), path = Uri.parse(CloudLocator.uri(profile.id, obj.ref)).buildUpon().appendQueryParameter("browse", id).build().toString(),
            pathKey = obj.ref.id, displayPath = "${profile.name} / ${obj.name}")
    }

internal data class CloudSync(val phase: String, val cursor: String?, val terminal: String?, val generation: Long, val baseline: Boolean)
internal data class CloudDirectory(val id: String, val cursor: String?)
internal data class CloudPending(val id: String, val parent: String?, val directory: Boolean)
internal const val CLOUD_SYNC_BATCH_SIZE = 128
internal const val CLOUD_DIRECTORY_QUERY = "SELECT id,cursor FROM cloud_dirs WHERE scope=? AND done=0 ORDER BY rowid LIMIT 1"
// Keep the small queue as the outer loop; an ordinary JOIN can start with the whole object graph.
internal const val CLOUD_PENDING_QUERY = """SELECT i.id,i.parent,i.kind FROM cloud_pending p
    CROSS JOIN cloud_items i ON i.scope=p.scope AND i.id=p.id WHERE p.scope=? ORDER BY p.rowid LIMIT 128"""

/** Persistent provider graph and page checkpoint share one transaction. Catalog is its replayable projection. */
internal class CloudIndex(context: Context, name: String = "cloud_library.db") : AutoCloseable {
    private val db: SQLiteConnection = context.getDatabasePath(name).let { file ->
        file.parentFile!!.mkdirs()
        BundledSQLiteDriver().open(file.absolutePath).also { c ->
            c.exec("PRAGMA busy_timeout=5000"); c.exec("PRAGMA journal_mode=WAL"); c.exec("PRAGMA synchronous=FULL")
            c.exec("CREATE TABLE IF NOT EXISTS cloud_roots(id TEXT PRIMARY KEY,type TEXT,server TEXT,scope TEXT,uri TEXT,doc TEXT,label TEXT,created INTEGER,updated INTEGER,revision INTEGER,deleted INTEGER)")
            c.exec("CREATE UNIQUE INDEX IF NOT EXISTS cloud_root_identity ON cloud_roots(scope,doc)")
            c.exec("CREATE TABLE IF NOT EXISTS cloud_items(scope TEXT,id TEXT,parent TEXT,name TEXT,kind TEXT,size INTEGER,modified INTEGER,version TEXT,deleted INTEGER,unsupported INTEGER,generation INTEGER,PRIMARY KEY(scope,id))")
            c.exec("CREATE INDEX IF NOT EXISTS cloud_parent ON cloud_items(scope,parent,id)")
            c.exec("CREATE TABLE IF NOT EXISTS cloud_sync(scope TEXT PRIMARY KEY,signature TEXT,phase TEXT,cursor TEXT,terminal TEXT,generation INTEGER,baseline INTEGER)")
            c.exec("CREATE TABLE IF NOT EXISTS cloud_dirs(scope TEXT,id TEXT,cursor TEXT,done INTEGER DEFAULT 0,PRIMARY KEY(scope,id))")
            // Rowid is the implicit last index column, preserving the existing FIFO order.
            c.exec("CREATE INDEX IF NOT EXISTS cloud_dirs_queue ON cloud_dirs(scope,done)")
            c.exec("CREATE TABLE IF NOT EXISTS cloud_pages(scope TEXT,phase TEXT,cursor TEXT,PRIMARY KEY(scope,phase,cursor))")
            c.exec("CREATE TABLE IF NOT EXISTS cloud_pending(scope TEXT,id TEXT,PRIMARY KEY(scope,id))")
            c.exec("CREATE INDEX IF NOT EXISTS cloud_pending_queue ON cloud_pending(scope)")
            c.exec("PRAGMA user_version=2")
        }
    }
    private fun <T> tx(block: () -> T): T {
        db.exec("BEGIN IMMEDIATE")
        try { val result = block(); db.exec("COMMIT"); return result }
        catch (error: Throwable) { db.exec("ROLLBACK"); throw error }
    }
    @Synchronized fun roots(): List<LibraryPath> = db.rows("SELECT id,type,server,scope,uri,doc,label,created,updated,revision FROM cloud_roots WHERE deleted=0 ORDER BY created") {
        LibraryPath(it.getText(0), LibraryPathType.valueOf(it.getText(1)), it.getText(2), it.getText(3), it.getText(4),
            it.getText(5), it.getText(6), it.getLong(7), it.getLong(8), revision = it.getLong(9))
    }
    @Synchronized fun add(root: LibraryPath): LibraryAddResult = tx {
        val old = db.rows("SELECT id,created,revision,deleted FROM cloud_roots WHERE scope=? AND doc=?", root.scope, root.pathKey) {
            listOf(it.getText(0), it.getLong(1), it.getLong(2), it.getLong(3))
        }.firstOrNull()
        if (old != null && old[3] == 0L) return@tx LibraryAddResult.Covered(roots().first { it.id == old[0] }, true)
        val now = System.currentTimeMillis()
        val saved = root.copy(id = old?.get(0) as? String ?: root.id, createdAt = old?.get(1) as? Long ?: now,
            updatedAt = now, revision = (old?.get(2) as? Long ?: 0) + 1)
        db.exec("INSERT OR REPLACE INTO cloud_roots VALUES(?,?,?,?,?,?,?,?,?,?,0)", saved.id, saved.type.name, saved.sourceId,
            saved.scope, saved.path, saved.pathKey, saved.displayPath, saved.createdAt, saved.updatedAt, saved.revision)
        LibraryAddResult.Added(saved)
    }
    @Synchronized fun remove(id: String) { db.exec("UPDATE cloud_roots SET deleted=1,revision=revision+1,updated=? WHERE id=?", System.currentTimeMillis(), id) }
    @Synchronized fun state(scope: String): CloudSync? = db.rows("SELECT phase,cursor,terminal,generation,baseline FROM cloud_sync WHERE scope=?", scope) {
        CloudSync(it.getText(0), it.textOrNull(1), it.textOrNull(2), it.getLong(3), it.getLong(4) == 1L)
    }.firstOrNull()
    @Synchronized fun needsBaseline(scope: String, signature: String) = db.rows("SELECT signature FROM cloud_sync WHERE scope=?", scope) { it.getText(0) }.firstOrNull() != signature
    @Synchronized fun prepare(scope: String, signature: String, seed: String?, initialDelta: Boolean, roots: List<String>, reset: Boolean = false) = tx {
        val old = state(scope)
        val same = db.rows("SELECT signature FROM cloud_sync WHERE scope=?", scope) { it.getText(0) }.firstOrNull() == signature
        if (old == null || !same || reset) {
            db.exec("DELETE FROM cloud_dirs WHERE scope=?", scope); db.exec("DELETE FROM cloud_pages WHERE scope=?", scope)
            db.exec("DELETE FROM cloud_pending WHERE scope=?", scope)
            db.exec("INSERT OR REPLACE INTO cloud_sync VALUES(?,?,?,?,NULL,?,1)", scope, signature,
                if (initialDelta) "feed" else "directories", seed, (old?.generation ?: 0) + 1)
            if (!initialDelta) roots.forEach { db.exec("INSERT OR IGNORE INTO cloud_dirs(scope,id) VALUES(?,?)", scope, it) }
        } else if (old.phase == "done") {
            db.exec("DELETE FROM cloud_dirs WHERE scope=?", scope); db.exec("DELETE FROM cloud_pages WHERE scope=?", scope)
            db.exec("DELETE FROM cloud_pending WHERE scope=?", scope)
            db.exec("UPDATE cloud_sync SET phase='feed',cursor=terminal,baseline=0 WHERE scope=?", scope)
        }
    }
    @Synchronized fun directory(scope: String): CloudDirectory? = db.rows(CLOUD_DIRECTORY_QUERY, scope) {
        CloudDirectory(it.getText(0), it.textOrNull(1))
    }.firstOrNull()
    private fun enqueue(scope: String, id: String) {
        db.exec("INSERT INTO cloud_dirs(scope,id) VALUES(?,?) ON CONFLICT(scope,id) DO UPDATE SET done=0,cursor=NULL", scope, id)
        db.exec("DELETE FROM cloud_pages WHERE scope=? AND phase=?", scope, "dir:$id")
    }
    private fun put(scope: String, item: CloudObject, generation: Long) {
        if (item.deleted) db.exec("""INSERT INTO cloud_items VALUES(?,?,NULL,'','OTHER',NULL,NULL,NULL,1,0,?)
            ON CONFLICT(scope,id) DO UPDATE SET deleted=1,generation=excluded.generation""", scope, item.ref.id, generation)
        else db.exec("""INSERT INTO cloud_items VALUES(?,?,?,?,?,?,?,?,0,?,?) ON CONFLICT(scope,id) DO UPDATE SET
            parent=excluded.parent,name=excluded.name,kind=excluded.kind,size=excluded.size,modified=excluded.modified,
            version=excluded.version,deleted=0,unsupported=excluded.unsupported,generation=excluded.generation""",
            scope, item.ref.id, item.parent, item.name, item.kind.name, item.size, item.modified, item.version, if (item.unsupported) 1 else 0, generation)
    }
    @Synchronized fun remember(scope: String, items: Collection<CloudObject>) = tx {
        val generation = checkNotNull(state(scope)).generation
        items.forEach { put(scope, it, generation) }
    }
    @Synchronized fun page(scope: String, page: CloudPage, directory: CloudDirectory? = null) = tx {
        if (!page.complete) throw IOException("Incomplete cloud page")
        val state = checkNotNull(state(scope))
        val phase = directory?.id?.let { "dir:$it" } ?: "feed"
        val next = page.next
        if (next != null) {
            if (next == (directory?.cursor ?: state.cursor) || db.scalar("SELECT COUNT(*) FROM cloud_pages WHERE scope=? AND phase=? AND cursor=?", scope, phase, next) > 0)
                throw IOException("Repeated cloud cursor")
            db.exec("INSERT INTO cloud_pages VALUES(?,?,?)", scope, phase, next)
        }
        page.objects.forEach {
            put(scope, it, state.generation)
            if (directory == null) db.exec("INSERT OR IGNORE INTO cloud_pending VALUES(?,?)", scope, it.ref.id)
        }
        if (directory != null) {
            db.exec("UPDATE cloud_dirs SET cursor=?,done=? WHERE scope=? AND id=?", next, if (next == null) 1 else 0, scope, directory.id)
            page.objects.filter { !it.deleted && !it.unsupported && it.kind == RemoteFileKind.FOLDER }.forEach {
                db.exec("INSERT OR IGNORE INTO cloud_dirs(scope,id) VALUES(?,?)", scope, it.ref.id)
            }
        } else db.exec("UPDATE cloud_sync SET cursor=?,terminal=?,phase=? WHERE scope=?", next ?: page.terminal,
            page.terminal ?: state.terminal, if (next == null) "compensated" else "feed", scope)
    }
    @Synchronized fun feed(scope: String) { db.exec("UPDATE cloud_sync SET phase='feed' WHERE scope=?", scope) }
    @Synchronized fun pending(scope: String): List<CloudPending> = db.rows(CLOUD_PENDING_QUERY, scope) {
        CloudPending(it.getText(0), it.textOrNull(1), it.getText(2) == "FOLDER")
    }
    // No network work belongs in this transaction. A failed batch keeps every pending item replayable.
    @Synchronized fun resolve(scope: String, pending: List<CloudPending>, ancestors: Collection<CloudObject>,
        roots: List<String>, discoverDirectories: Boolean) = tx {
        val generation = checkNotNull(state(scope)).generation
        ancestors.forEach { put(scope, it, generation) }
        pending.forEach {
            if (discoverDirectories && it.directory && available(scope, it.id) && belongs(scope, it.id, roots))
                enqueue(scope, it.id)
            db.exec("DELETE FROM cloud_pending WHERE scope=? AND id=?", scope, it.id)
        }
    }
    @Synchronized fun complete(scope: String) = tx {
        val state = checkNotNull(state(scope))
        if (state.baseline) db.exec("UPDATE cloud_items SET deleted=1 WHERE scope=? AND generation!=?", scope, state.generation)
        db.exec("UPDATE cloud_sync SET phase='done',baseline=0 WHERE scope=?", scope)
    }
    @Synchronized fun known(scope: String, id: String) = db.scalar("SELECT COUNT(*) FROM cloud_items WHERE scope=? AND id=?", scope, id) != 0L
    @Synchronized fun parent(scope: String, id: String): String? = db.rows("SELECT parent FROM cloud_items WHERE scope=? AND id=?", scope, id) { it.textOrNull(0) }.firstOrNull()
    @Synchronized fun belongs(scope: String, id: String, roots: List<String>): Boolean {
        if (id in roots) return true
        val marks = roots.joinToString(",") { "?" }
        return db.scalar("""WITH RECURSIVE ancestors(id,parent) AS (
            SELECT id,parent FROM cloud_items WHERE scope=? AND id=? AND deleted=0 UNION
            SELECT o.id,o.parent FROM ancestors a JOIN cloud_items o ON o.scope=? AND o.id=a.parent AND o.deleted=0)
            SELECT COUNT(*) FROM ancestors WHERE id IN ($marks) OR parent IN ($marks)""", scope, id, scope, *roots.toTypedArray(), *roots.toTypedArray()) > 0
    }
    @Synchronized fun children(scope: String, parent: String, after: String = ""): List<FileFact> = db.rows(
        "SELECT id,name,kind,size,modified,version FROM cloud_items WHERE scope=? AND parent=? AND id>? AND deleted=0 AND unsupported=0 ORDER BY id LIMIT 128", scope, parent, after) {
        FileFact(it.getText(0), it.getText(1), fileKind(it.getText(1), RemoteFileKind.valueOf(it.getText(2))), it.longOrNull(3), it.longOrNull(4), it.textOrNull(5))
    }
    @Synchronized fun unsupported(scope: String, parent: String) = db.scalar("SELECT COUNT(*) FROM cloud_items WHERE scope=? AND parent=? AND deleted=0 AND unsupported=1", scope, parent) > 0
    @Synchronized fun available(scope: String, id: String) = db.scalar("SELECT COUNT(*) FROM cloud_items WHERE scope=? AND id=? AND deleted=0 AND unsupported=0", scope, id) > 0
    @Synchronized fun unsupportedCount(scope: String, root: String) = db.scalar("""WITH RECURSIVE reach(id) AS (SELECT ? UNION
        SELECT i.id FROM reach r CROSS JOIN cloud_items i WHERE i.scope=? AND i.parent=r.id AND i.deleted=0)
        SELECT COUNT(*) FROM reach r JOIN cloud_items i ON i.scope=? AND i.id=r.id WHERE i.unsupported=1""", root, scope, scope)
    @Synchronized override fun close() = db.close()
}
internal fun fileKind(name: String, kind: RemoteFileKind) = when (kind) {
    RemoteFileKind.FOLDER -> FileKind.DIRECTORY
    RemoteFileKind.VIDEO -> FileKind.VIDEO
    else -> SafLibrarySource.kind(name, "").takeIf { it == FileKind.NFO || it == FileKind.IMAGE } ?: FileKind.OTHER
}

internal class LibraryRegistry(context: Context) : LibraryRepository, AutoCloseable {
    private val local = LibraryDatabase(context)
    private val cloud by lazy { CloudIndex(context) }
    override fun list() = (local.list() + cloud.roots()).sortedBy { it.createdAt }
    override fun add(candidate: LibraryPath) = if (candidate.type.cloud) cloud.add(candidate) else local.add(candidate)
    override fun delete(id: String) { if (cloud.roots().any { it.id == id }) cloud.remove(id) else local.delete(id) }
    override fun close() { local.close(); cloud.close() }
}
internal val LibraryPathType.cloud get() = this == LibraryPathType.GOOGLE_DRIVE || this == LibraryPathType.ONEDRIVE

internal class CloudLibrarySource(private val context: Context, private val profiles: List<ServerProfile>,
    private val index: CloudIndex) : LibraryFileSource {
    override fun enumerate(root: ScanRoot, directory: String) = flow {
        // The provider sync has already committed its graph. SQL windows never aggregate a directory.
        if (!index.available(root.scope, root.documentId)) {
            emit(DiscoveryBatch(emptyList(), true, Coverage.COMPLETE)); return@flow
        }
        var after = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = index.children(root.scope, directory, after)
            if (page.isEmpty()) break
            emit(DiscoveryBatch(page)); after = page.last().objectId
        }
        // Coverage warnings are recorded per root by CloudLibrarySync. They must not
        // prevent NFO parsing or explicit removals in the supported part of this graph.
        emit(DiscoveryBatch(emptyList(), true, Coverage.COMPLETE))
    }
    override suspend fun open(root: ScanRoot, objectId: String) = try {
        read(CloudLocator.file(root.treeUri, objectId), LocalNfo.MAX_BYTES).inputStream()
    } catch (_: CloudReadLimitExceeded) { throw LocalNfo.InvalidContent() }
    suspend fun read(uri: String, limit: Int): ByteArray = cloudProvider(context, CloudLocator.profile(uri, profiles), CloudLocator.ref(uri)).use {
        try { it.smallFile(CloudLocator.ref(uri).id, limit) }
        catch (failure: DirectoryFailure) { throw IOException("Cloud sidecar unavailable", failure) }
    }
}
