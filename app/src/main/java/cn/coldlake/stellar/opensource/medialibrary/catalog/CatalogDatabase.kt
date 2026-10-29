package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.playback.reporting.*
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** One serialized writer/short reader. All callers run off the main thread. No destructive migration. */
internal class CatalogDatabase(context: Context, name: String = "library_catalog.db") : AutoCloseable {
    private var projectionRevision = 0L
    private var homeUpdates = HomeUpdates()
    private var homeSources: Set<String>? = null
    private var sourcesChanged = false
    private var transactionsTotal = 0L
    @Synchronized fun homeUpdates(): HomeUpdates = homeUpdates
    @Synchronized fun homeRevision(): Long = projectionRevision
    val file: File = context.getDatabasePath(name)
    private val db: SQLiteConnection by lazy {
        file.parentFile!!.mkdirs()
        BundledSQLiteDriver().open(file.absolutePath).also { c ->
            c.exec("PRAGMA busy_timeout=5000"); c.exec("PRAGMA journal_mode=WAL")
            c.exec("PRAGMA synchronous=FULL"); c.exec("PRAGMA foreign_keys=ON")
            val version = c.rows("PRAGMA user_version") { it.getLong(0) }.single()
            require(version in 0..17) { "Unsupported catalog version" }
            if (version == 0L) {
                c.exec("BEGIN IMMEDIATE")
                try { SCHEMA.forEach { c.exec(it) }; c.exec("PRAGMA user_version=2"); c.exec("COMMIT") }
                catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version == 1L) {
                c.exec("BEGIN IMMEDIATE")
                try { c.exec("ALTER TABLE objects ADD COLUMN version TEXT"); c.exec("PRAGMA user_version=2"); c.exec("COMMIT") }
                catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 2L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("CREATE TABLE IF NOT EXISTS cloud_ready(run INTEGER,scope TEXT,PRIMARY KEY(run,scope))")
                    c.exec("CREATE TABLE IF NOT EXISTS cloud_partial(run INTEGER,root TEXT,PRIMARY KEY(run,root))")
                    c.exec("PRAGMA user_version=3"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 3L) {
                c.exec("BEGIN IMMEDIATE")
                try { METADATA_SCHEMA.forEach { c.exec(it) }; c.exec("PRAGMA user_version=4"); c.exec("COMMIT") }
                catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 4L) {
                c.exec("BEGIN IMMEDIATE")
                try { CatalogHome.schema.forEach { c.exec(it) }; c.exec("PRAGMA user_version=5"); c.exec("COMMIT") }
                catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 5L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("ALTER TABLE imported_record ADD COLUMN virtual INTEGER NOT NULL DEFAULT 0")
                    c.exec("CREATE TABLE home_server_media(parent INTEGER,source TEXT,record INTEGER UNIQUE,PRIMARY KEY(parent,source))")
                    c.exec("PRAGMA user_version=6"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 6L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("ALTER TABLE metadata_attempt ADD COLUMN error TEXT")
                    c.exec("CREATE TABLE metadata_job(id INTEGER PRIMARY KEY CHECK(id=1),locale TEXT NOT NULL,state TEXT NOT NULL,round INTEGER NOT NULL,next_at INTEGER NOT NULL)")
                    c.exec("DELETE FROM provider_query WHERE key LIKE 'stellar|resolve|2|series|%' OR key LIKE 'stellar|orders|%' OR key LIKE 'stellar|entries|%'")
                    c.exec("DELETE FROM provider_retry WHERE key='catalog'")
                    c.exec("UPDATE metadata_attempt SET state='waiting',retry_at=0 WHERE state='failed'")
                    c.exec("PRAGMA user_version=7"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 7L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("ALTER TABLE nfo ADD COLUMN details TEXT")
                    c.exec("CREATE TABLE wall_artwork(owner INTEGER NOT NULL,role TEXT NOT NULL,image INTEGER NOT NULL,PRIMARY KEY(owner,role))")
                    // Keep accepted/manual bindings visible while the next explicit scan/identify fills new fields.
                    c.exec("UPDATE metadata_attempt SET state='waiting',retry_at=0 WHERE NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=metadata_attempt.object AND b.origin='manual')")
                    c.exec("PRAGMA user_version=8"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 8L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    // Entity responses cached before ratings existed must be revalidated on the next identification.
                    // Keep artwork/credits/search caches, accepted identities, higher-priority bindings and progress.
                    c.exec("DELETE FROM provider_query WHERE key LIKE 'stellar|entity|%'")
                    c.exec("DELETE FROM provider_retry WHERE key LIKE 'stellar|entity|%'")
                    c.exec("""UPDATE metadata_attempt SET state='partial',retry_at=0,error=NULL
                        WHERE EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=metadata_attempt.object
                            AND b.origin='provider' AND b.identity LIKE 'catalog:%')
                        AND NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=metadata_attempt.object
                            AND b.origin IN ('manual','import','server'))""")
                    c.exec("PRAGMA user_version=9"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 9L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("CREATE INDEX home_progress_continue ON home_progress(updated DESC,version DESC) WHERE position>0 AND watched=0")
                    c.exec("DROP INDEX seen_parent")
                    c.exec("CREATE INDEX seen_parent ON seen(run,scope,parent,kind)")
                    c.exec("PRAGMA user_version=10"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 10L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    // Keep cached responses; store their UTF-8 size once, with payload last.
                    c.exec("CREATE TABLE provider_query_v11(key TEXT PRIMARY KEY,expires INTEGER NOT NULL,bytes INTEGER NOT NULL,payload TEXT NOT NULL)")
                    c.exec("INSERT INTO provider_query_v11 SELECT key,expires,length(CAST(payload AS BLOB)),payload FROM provider_query")
                    c.exec("DROP TABLE provider_query")
                    c.exec("ALTER TABLE provider_query_v11 RENAME TO provider_query")
                    c.exec("CREATE INDEX provider_query_expiry ON provider_query(expires DESC,key,bytes)")
                    c.exec("PRAGMA user_version=11"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 11L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("ALTER TABLE provider_query ADD COLUMN etag TEXT")
                    c.exec("CREATE INDEX metadata_refresh ON metadata_attempt(state,retry_at)")
                    // Existing raw responses already contain the new fields: reparse them, preserving caches and identities.
                    c.exec("""UPDATE metadata_attempt SET state='partial',retry_at=0,error=NULL
                        WHERE EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=metadata_attempt.object AND b.origin='provider')
                        AND NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=metadata_attempt.object AND b.origin IN ('manual','import','server'))""")
                    c.exec("PRAGMA user_version=12"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 12L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("CREATE TABLE IF NOT EXISTS file_fingerprint(object INTEGER PRIMARY KEY REFERENCES objects(id) ON DELETE CASCADE,stamp TEXT NOT NULL,algorithm TEXT NOT NULL,size INTEGER NOT NULL,hash TEXT NOT NULL)")
                    c.exec("PRAGMA user_version=13"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 13L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("DROP INDEX IF EXISTS frontier_work")
                    // Equal run keys are ordered by the implicit rowid, across all runnable phases.
                    c.exec("CREATE INDEX frontier_work ON frontier(run) WHERE phase IN ('pending','listing','metadata')")
                    c.exec("PRAGMA user_version=14"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 14L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    c.exec("CREATE TABLE IF NOT EXISTS scan_metadata(run INTEGER PRIMARY KEY,locale TEXT NOT NULL)")
                    c.exec("""CREATE TABLE IF NOT EXISTS scan_metadata_queue(object INTEGER PRIMARY KEY,run INTEGER NOT NULL,
                        input TEXT NOT NULL,edit INTEGER NOT NULL,generation INTEGER NOT NULL DEFAULT 1,
                        retry_at INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0)""")
                    c.exec("CREATE INDEX IF NOT EXISTS scan_metadata_due ON scan_metadata_queue(run,retry_at,object)")
                    c.exec("CREATE INDEX IF NOT EXISTS scan_metadata_work ON scan_metadata_queue(run,object) WHERE attempts<3")
                    if (c.rows("PRAGMA table_info(metadata_job)") { it.getText(1) }.none { it == "scan_run" })
                        c.exec("ALTER TABLE metadata_job ADD COLUMN scan_run INTEGER")
                    c.exec("PRAGMA user_version=15"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 15L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    val columns = c.rows("PRAGMA table_info(home_entity)") { it.getText(1) }
                    for (column in listOf("content_revision", "progress_revision")) if (column !in columns)
                        c.exec("ALTER TABLE home_entity ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                    CatalogHome.artworkTriggers.forEach { c.exec(it) }
                    c.exec("PRAGMA user_version=16"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
            if (version <= 16L) {
                c.exec("BEGIN IMMEDIATE")
                try {
                    val hasResume = c.rows("PRAGMA table_info(home_progress)") { it.getText(1) }.contains("resume_eligible")
                    PlaybackRecordStore.schema.forEach { sql ->
                        if (!(hasResume && (sql.startsWith("ALTER TABLE home_progress") || sql.startsWith("UPDATE home_progress")))) c.exec(sql)
                    }
                    PlaybackRecordStore(c).migrate()
                    c.exec("PRAGMA user_version=17"); c.exec("COMMIT")
                } catch (error: Throwable) { c.exec("ROLLBACK"); throw error }
            }
        }
    }
    @Synchronized fun fingerprintFile(id: Long): FingerprintFile? = db.rows("""SELECT o.scope,o.doc,o.size,o.modified,o.version,r.uri,r.revision
        FROM objects o JOIN member m ON m.object=o.id JOIN roots r ON r.id=m.root
        WHERE o.id=? AND m.misses<2 AND r.active=1 ORDER BY r.id LIMIT 1""", id) {
        FingerprintFile(id, it.getText(0), it.getText(1), it.longOrNull(2), it.longOrNull(3), it.textOrNull(4), it.getText(5), it.getLong(6))
    }.firstOrNull()
    @Synchronized fun fingerprint(file: FingerprintFile): com.stellar.player.source.ColdLakeHashKey? {
        if (!file.reusable || fingerprintFile(file.id) != file) return null
        return db.rows("SELECT algorithm,size,hash FROM file_fingerprint WHERE object=? AND stamp=?", file.id, file.stamp) {
            com.stellar.player.source.ColdLakeHashKey(it.getText(0), it.getLong(1), it.getText(2))
        }.firstOrNull()?.takeIf { it.fileSize == file.size && it.algorithmVersion == com.stellar.player.source.ColdLakeHash.ALGORITHM_VERSION }
    }
    @Synchronized fun saveFingerprint(file: FingerprintFile, key: com.stellar.player.source.ColdLakeHashKey): Boolean {
        if (!file.reusable || key.fileSize != file.size || fingerprintFile(file.id) != file) return false
        db.exec("INSERT INTO file_fingerprint VALUES(?,?,?,?,?) ON CONFLICT(object) DO UPDATE SET stamp=excluded.stamp,algorithm=excluded.algorithm,size=excluded.size,hash=excluded.hash",
            file.id, file.stamp, key.algorithmVersion, key.fileSize, key.quickHash)
        return true
    }
    @Synchronized fun details(id: Long, series: Boolean = false): LibraryDetails = CatalogDetailStore(db).read(id, series)
    private fun <T> transaction(drain: Boolean = true, block: () -> T): T {
        db.exec("BEGIN IMMEDIATE")
        try {
            val result = block(); val projected = drain && homeIndex.drain()
            val affected = homeIndex.takeAffected()
            val nextRevision = projectionRevision + if (projected || affected.isNotEmpty()) 1 else 0
            var updates = homeUpdates
            val sources = if (sourcesChanged || homeSources == null) homeIndex.sources().map { it.id }.toSet() else homeSources!!
            if (affected.isNotEmpty() || sourcesChanged) {
                val sections = homeUpdates.sections.toMutableMap()
                affected.forEach { sections[it] = nextRevision }
                sections.keys.removeAll { it.source != null && it.source !in sources }
                updates = HomeUpdates(homeUpdates.sources + if (sourcesChanged) 1 else 0, sections)
            }
            db.exec("COMMIT")
            transactionsTotal++
            projectionRevision = nextRevision; homeUpdates = updates; homeSources = sources; sourcesChanged = false
            return result
        }
        catch (error: Throwable) { homeIndex.takeAffected(); sourcesChanged = false; db.exec("ROLLBACK"); throw error }
    }
    private fun valid(claim: ScanClaim) {
        val live = db.rows("""SELECT 1 FROM scan_run WHERE id=? AND epoch=? AND state='running'
            AND NOT EXISTS(SELECT 1 FROM run_root rr LEFT JOIN roots r ON rr.root=r.id
            WHERE rr.run=? AND (r.id IS NULL OR r.active=0 OR r.revision!=rr.revision))""",
            claim.run, claim.epoch, claim.run) { true }.isNotEmpty()
        if (!live) throw ObsoleteScan()
    }
    @Synchronized fun checkClaim(claim: ScanClaim) = valid(claim)
    @Synchronized fun failScope(claim: ScanClaim, scope: String) {
        valid(claim)
        db.exec("DELETE FROM cloud_ready WHERE run=? AND scope=?", claim.run, scope)
        db.exec("UPDATE frontier SET phase='failed' WHERE run=? AND scope=?", claim.run, scope)
    }
    private val homeIndex by lazy { CatalogHome(db, this) }
    @Synchronized fun <T> home(block: CatalogHome.() -> T): T = homeIndex.block()
    @Synchronized fun projectHomeBatch(): Boolean = transaction { homeIndex.pending() }
    @Synchronized fun saveProgress(id: Long, position: Long, duration: Long, watched: Boolean) = transaction(drain = false) {
        PlaybackRecordStore(db).command(id, position, duration, watched, homeIndex)
    }
    @Synchronized fun playbackVersionKey(version: Long) = PlaybackRecordStore(db).key(version)
    @Synchronized fun playbackVersionLocator(version: Long): String? = db.rows("SELECT payload FROM home_version WHERE id=?", version) {
        catalogCard(it.getText(0)).uri
    }.firstOrNull()
    @Synchronized fun playbackManualStatus(key: PlaybackRecordKey, target: String, revision: Long, state: DeliveryState, error: String?,
        baseline: ServerBaseline? = null) {
        val store = PlaybackRecordStore(db)
        val record = store.read(key)?.takeIf { it.session.isEmpty() && it.revision == revision } ?: return
        // Manual commands also work before this version ever sent a playback session.
        db.exec("""INSERT INTO playback_delivery(key,target,session,seq,revision,state) VALUES(?,?,'',0,?,?)
            ON CONFLICT(key,target) DO NOTHING""", key.value, target, revision, state.name)
        store.acknowledge(key, target, PlaybackFact("", 0, ReportKind.FINISH, record.positionMs, record.durationMs,
            true, false, 1f, null, null, lastWatchedAt = record.lastWatchedAt), revision, state, error, baseline)
    }
    @Synchronized fun playbackServerCommand(target: String, item: String, position: Long, watched: Boolean) = transaction(drain = false) {
        PlaybackRecordStore(db).serverCommand(target, item, position, watched, homeIndex)
    }
    @Synchronized fun playbackServerBind(target: String, item: String, version: String, key: PlaybackRecordKey) {
        db.exec("INSERT INTO playback_server VALUES(?,?,?,?) ON CONFLICT(target,item,version) DO UPDATE SET key=excluded.key", target, item, version, key.value)
    }
    @Synchronized fun playbackServerRecord(target: String, item: String): Pair<PlaybackRecord,String>? = db.rows(
        """SELECT s.key,s.version FROM playback_server s JOIN playback_record r ON r.key=s.key
            WHERE s.target=? AND s.item=? ORDER BY r.last_watched DESC,r.revision DESC LIMIT 1""", target, item) {
        it.getText(0) to it.getText(1)
    }.firstOrNull()?.let { (key, version) -> PlaybackRecordStore(db).read(PlaybackRecordKey(key))?.let { it to version } }
    @Synchronized fun playbackRead(key: PlaybackRecordKey) = PlaybackRecordStore(db).read(key)
    @Synchronized fun playbackDelivery(key: PlaybackRecordKey, target: String) = PlaybackRecordStore(db).delivery(key, target)
    @Synchronized fun playbackRecover() = transaction(drain = false) { PlaybackRecordStore(db).recover() }
    @Synchronized fun playbackBind(version: Long, key: PlaybackRecordKey) = transaction(drain = false) {
        PlaybackRecordStore(db).apply { bind(version, key, replace = true); project(key, homeIndex) }
    }
    @Synchronized fun playbackCommit(key: PlaybackRecordKey, fact: PlaybackFact, target: String?, baseline: ServerBaseline?) = transaction(drain = false) {
        val store = PlaybackRecordStore(db)
        store.commit(key, fact, target, baseline)?.also { if (fact.persist) store.project(key, homeIndex) }
    }
    @Synchronized fun playbackAck(key: PlaybackRecordKey, target: String, fact: PlaybackFact, revision: Long,
        state: DeliveryState, error: String?, baseline: ServerBaseline? = null) = transaction(drain = false) {
        PlaybackRecordStore(db).acknowledge(key, target, fact, revision, state, error, baseline)
    }
    @Synchronized fun cloudReady(claim: ScanClaim, scope: String) {
        valid(claim); db.exec("INSERT OR IGNORE INTO cloud_ready VALUES(?,?)", claim.run, scope)
        db.exec("DELETE FROM cloud_partial WHERE run=? AND root IN (SELECT id FROM roots WHERE scope=?)", claim.run, scope)
    }
    @Synchronized fun cloudPartial(claim: ScanClaim, root: String) {
        valid(claim); db.exec("INSERT OR IGNORE INTO cloud_partial VALUES(?,?)", claim.run, root)
    }
    @Synchronized fun initialize() {
        // A new process never silently resumes a long foreground task.
        db.exec("UPDATE scan_run SET state='paused',epoch=epoch+1 WHERE state='running'")
        db.exec("UPDATE roots SET outcome='paused' WHERE outcome='running'")
    }
    @Synchronized fun syncRoots(roots: List<ScanRoot>) = transaction {
        val oldSources = homeIndex.sources()
        val old = db.rows("SELECT id,revision FROM roots WHERE active=1") { it.getText(0) to it.getLong(1) }.toMap()
        val next = roots.associate { it.id to it.revision }
        val changed = (old.keys + next.keys).filter { old[it] != next[it] }
        if (old != next) {
            db.exec("UPDATE scan_run SET state='cancelled',epoch=epoch+1 WHERE state IN ('running','paused')")
            db.exec("UPDATE roots SET outcome='cancelled' WHERE outcome IN ('running','paused')")
        }
        db.exec("UPDATE roots SET active=0 WHERE active=1")
        for (r in roots) db.exec("""INSERT INTO roots(id,scope,doc,uri,revision,label,active) VALUES(?,?,?,?,?,?,1)
            ON CONFLICT(id) DO UPDATE SET scope=excluded.scope,doc=excluded.doc,uri=excluded.uri,
            revision=excluded.revision,label=excluded.label,active=1""", r.id, r.scope, r.documentId, r.treeUri, r.revision, r.label)
        for (id in changed) {
            refreshVisibility(id)
            db.exec("INSERT OR IGNORE INTO home_dirty SELECT m.object FROM member m JOIN wall w ON w.object=m.object WHERE m.root=?", id)
        }
        sourcesChanged = oldSources != homeIndex.sources()
    }
    @Synchronized fun roots(): List<ScanRoot> = db.rows("SELECT id,scope,doc,uri,revision,label FROM roots WHERE active=1 ORDER BY id", map = ::root)
    private fun root(s: SQLiteStatement) = ScanRoot(s.getText(0), s.getText(1), s.getText(2), s.getText(3), s.getLong(4), s.getText(5))

    @Synchronized fun begin(resume: Boolean, force: Boolean = false): ScanClaim? = transaction {
        val latest = status()
        if (latest.running) return@transaction claim()
        if (resume && latest.resumable) {
            db.exec("UPDATE scan_run SET state='running',epoch=epoch+1 WHERE id=?", latest.run)
            db.exec("UPDATE roots SET outcome='running' WHERE id IN (SELECT root FROM run_root WHERE run=?)", latest.run)
            db.exec("UPDATE frontier SET phase='pending' WHERE run=? AND phase='listing'", latest.run)
            return@transaction claim()
        }
        val roots = roots()
        if (roots.isEmpty()) return@transaction null
        if (force) db.exec("UPDATE nfo SET parser=0")
        db.exec("UPDATE scan_run SET state='cancelled',epoch=epoch+1 WHERE state='paused'")
        // Only one live staging set; old summaries are retained, not all previous enumerations.
        db.exec("DELETE FROM seen"); db.exec("DELETE FROM frontier"); db.exec("DELETE FROM run_root"); db.exec("DELETE FROM cloud_ready")
        db.exec("DELETE FROM cloud_partial")
        db.exec("DELETE FROM scan_run WHERE id NOT IN (SELECT id FROM scan_run ORDER BY id DESC LIMIT 19)")
        db.exec("INSERT INTO scan_run(state,epoch,started) VALUES('running',1,?)", System.currentTimeMillis())
        val run = db.scalar("SELECT last_insert_rowid()")
        for (r in roots) {
            db.exec("INSERT INTO run_root VALUES(?,?,?)", run, r.id, r.revision)
            db.exec("INSERT OR IGNORE INTO objects(scope,doc,name,stem,kind) VALUES(?,?,?,'','DIRECTORY')", r.scope, r.documentId, r.label)
            db.exec("INSERT OR IGNORE INTO frontier VALUES(?,?,?,?, 'pending')", run, r.scope, r.documentId, r.id)
            db.exec("INSERT OR IGNORE INTO seen VALUES(?,?,?,?,?)", run, r.scope, r.documentId, null, "DIRECTORY")
            db.exec("UPDATE roots SET attempted=?,outcome='running' WHERE id=?", System.currentTimeMillis(), r.id)
        }
        ScanClaim(run, 1)
    }
    @Synchronized fun claim(): ScanClaim? = db.rows("SELECT id,epoch FROM scan_run WHERE state='running' ORDER BY id DESC LIMIT 1") {
        ScanClaim(it.getLong(0), it.getLong(1)) }.firstOrNull()
    @Synchronized fun stop(state: String) {
        require(state in setOf("paused", "cancelled", "failed"))
        db.exec("UPDATE scan_run SET state=?,epoch=epoch+1 WHERE state IN ('running','paused')", state)
        db.exec("UPDATE roots SET outcome=? WHERE outcome IN ('running','paused')", state)
    }
    @Synchronized fun history(): Map<String, RootScanHistory> = db.rows("SELECT id,attempted,completed,outcome FROM roots WHERE active=1") {
        it.getText(0) to RootScanHistory(it.longOrNull(1), it.longOrNull(2), it.textOrNull(3))
    }.toMap()
    @Synchronized fun status(): ScanStatus {
        val count = db.scalar("SELECT COUNT(*) FROM roots WHERE active=1").toInt()
        return db.rows("SELECT id,state,discovered,parsed,current FROM scan_run ORDER BY id DESC LIMIT 1") {
            ScanStatus(it.getLong(0), it.getText(1), it.getLong(2), it.getLong(3), it.getText(4), count)
        }.firstOrNull() ?: ScanStatus(roots = count)
    }
    @Synchronized fun next(claim: ScanClaim, excluded: List<DirectoryWork> = emptyList()): DirectoryWork? {
        valid(claim)
        val skip = excluded.joinToString("") { " AND NOT (f.scope=? AND f.doc=?)" }
        return db.rows("""SELECT f.scope,f.doc,f.phase,r.id,r.scope,r.doc,r.uri,r.revision,r.label FROM frontier f
            JOIN roots r ON r.id=f.root WHERE f.run=? AND f.phase IN ('pending','listing','metadata') $skip
            ORDER BY f.rowid LIMIT 1""", claim.run, *excluded.flatMap { listOf(it.scope, it.documentId) }.toTypedArray()) {
            DirectoryWork(it.getText(0), it.getText(1), ScanRoot(it.getText(3), it.getText(4), it.getText(5),
                it.getText(6), it.getLong(7), it.getText(8)), it.getText(2))
        }.firstOrNull()
    }
    @Synchronized fun startDirectory(claim: ScanClaim, work: DirectoryWork) = transaction {
        valid(claim)
        db.exec("UPDATE frontier SET phase='listing' WHERE run=? AND scope=? AND doc=?", claim.run, work.scope, work.documentId)
        // SAF does not promise a restartable cursor: re-list only the interrupted directory.
        db.exec("DELETE FROM seen WHERE run=? AND scope=? AND parent=?", claim.run, work.scope, work.documentId)
        db.exec("UPDATE scan_run SET queries=queries+1,current=? WHERE id=?", work.documentId, claim.run)
    }
    private data class Stored(val id: Long, val parent: String?, val name: String, val kind: String, val size: Long?, val modified: Long?, val revision: Long, val version: String?)
    @Synchronized fun commitBatch(claim: ScanClaim, work: DirectoryWork, entries: List<FileFact>): Int = transaction {
        valid(claim); require(entries.size <= 128)
        if (entries.isEmpty()) return@transaction 0
        val marks = entries.joinToString(",") { "?" }
        val old = db.rows("SELECT id,doc,parent,name,kind,size,modified,revision,version FROM objects WHERE scope=? AND doc IN ($marks)",
            work.scope, *entries.map { it.objectId }.toTypedArray()) {
            it.getText(1) to Stored(it.getLong(0), it.textOrNull(2), it.getText(3), it.getText(4), it.longOrNull(5), it.longOrNull(6), it.getLong(7), it.textOrNull(8))
        }.toMap()
        var writes = 0; var projections = 0
        // A live paginated directory can repeat an entry while it changes. Apply the last
        // observation in this bounded page without creating the same wall identity twice.
        for (e in entries.associateBy { it.objectId }.values) {
            val prior = old[e.objectId]
            // Keep the catalogue's last known facts, but never reuse a digest through an unversioned observation.
            if (prior != null && (e.size == null ||
                (if (cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(work.root.treeUri) || !prior.version.isNullOrBlank())
                    e.version.isNullOrBlank() else (e.modified ?: 0) <= 0))) {
                db.exec("DELETE FROM file_fingerprint WHERE object=?", prior.id)
            }
            // An unavailable attribute does not erase known values or force a content read.
            val size = e.size ?: prior?.size; val modified = e.modified ?: prior?.modified
            val version = e.version ?: prior?.version
            val changed = prior == null || prior.parent != work.documentId || prior.name != e.name ||
                prior.kind != e.kind.name || prior.size != size || prior.modified != modified || prior.version != version
            if (changed) {
                db.exec("""INSERT INTO objects(scope,doc,parent,name,stem,kind,size,modified,version) VALUES(?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(scope,doc) DO UPDATE SET parent=excluded.parent,name=excluded.name,stem=excluded.stem,
                    kind=excluded.kind,size=excluded.size,modified=excluded.modified,version=excluded.version,revision=objects.revision+
                    CASE WHEN objects.size IS NOT excluded.size OR objects.modified IS NOT excluded.modified OR objects.version IS NOT excluded.version THEN 1 ELSE 0 END""",
                    work.scope, e.objectId, work.documentId, e.name, e.stem, e.kind.name, size, modified, version)
                writes++
            }
            db.exec("INSERT INTO seen VALUES(?,?,?,?,?) ON CONFLICT(run,scope,doc) DO UPDATE SET parent=excluded.parent,kind=excluded.kind",
                claim.run, work.scope, e.objectId, work.documentId, e.kind.name)
            db.exec("""INSERT INTO member(root,object,misses) SELECT ?,id,0 FROM objects WHERE scope=? AND doc=?
                ON CONFLICT(root,object) DO UPDATE SET misses=0 WHERE misses!=0""", work.root.id, work.scope, e.objectId)
            if (e.kind == FileKind.DIRECTORY) db.exec("INSERT OR IGNORE INTO frontier VALUES(?,?,?,?,'pending')", claim.run, work.scope, e.objectId, work.root.id)
            if (e.kind == FileKind.DIRECTORY && changed) db.exec("UPDATE frontier SET phase='metadata' WHERE run=? AND scope=? AND doc=? AND phase='done'", claim.run, work.scope, e.objectId)
            if (e.kind == FileKind.VIDEO && prior != null) db.exec("UPDATE wall SET visible=1 WHERE object=? AND visible=0", prior.id)
            if (e.kind == FileKind.VIDEO && prior == null) {
                db.exec("INSERT INTO wall(object,title,kind,input,visible) SELECT id,name,'file',CAST(revision AS TEXT)||?||name||':',1 FROM objects WHERE scope=? AND doc=?",
                    ":${LocalNfo.VERSION}:", work.scope, e.objectId)
                projections++
            }
        }
        db.exec("UPDATE scan_run SET discovered=discovered+?,facts=facts+?,projections=projections+?,batches=batches+1 WHERE id=?",
            entries.size, writes, projections, claim.run)
        writes + projections
    }
    @Synchronized fun listed(claim: ScanClaim, work: DirectoryWork, complete: Boolean) = transaction {
        valid(claim)
        db.exec("UPDATE frontier SET phase=? WHERE run=? AND scope=? AND doc=?", if (complete) "metadata" else "failed", claim.run, work.scope, work.documentId)
    }
    @Synchronized fun pendingNfo(claim: ScanClaim, work: DirectoryWork, after: Long): List<NfoWork> {
        valid(claim)
        return db.rows("""SELECT o.id,o.doc,o.revision FROM objects o JOIN seen s ON s.scope=o.scope AND s.doc=o.doc AND s.run=?
            LEFT JOIN nfo n ON n.object=o.id WHERE o.scope=? AND o.parent=? AND o.kind='NFO' AND o.id>?
            AND (n.object IS NULL OR n.revision!=o.revision OR n.parser!=?) ORDER BY o.id LIMIT 128""",
            claim.run, work.scope, work.documentId, after, LocalNfo.VERSION) { NfoWork(it.getLong(0), it.getText(1), it.getLong(2)) }
    }
    @Synchronized fun parsed(claim: ScanClaim, item: NfoWork, data: NfoData?) = parsedBatch(claim, listOf(ParsedNfo(item, data)))
    @Synchronized fun parsedBatch(claim: ScanClaim, items: List<ParsedNfo>) = transaction {
        valid(claim)
        require(items.isNotEmpty() && items.size <= 128)
        val revisions = db.rows("SELECT id,revision FROM objects WHERE id IN (${items.joinToString(",") { "?" }})",
            *items.map { it.item.id }.toTypedArray()) { it.getLong(0) to it.getLong(1) }.toMap()
        if (items.any { revisions[it.item.id] != it.item.revision }) throw ObsoleteScan()
        for (parsed in items) {
        val item = parsed.item; val data = parsed.data
        db.exec("""INSERT INTO nfo(object,revision,parser,kind,title,year,plot,show,season,episode,ids,valid,details) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(object) DO UPDATE SET
            revision=excluded.revision,parser=excluded.parser,kind=excluded.kind,title=excluded.title,year=excluded.year,
            plot=excluded.plot,show=excluded.show,season=excluded.season,episode=excluded.episode,ids=excluded.ids,valid=excluded.valid,details=excluded.details""",
            item.id, item.revision, LocalNfo.VERSION, data?.kind, data?.title, data?.year, data?.plot, data?.show,
            data?.season, data?.episode, data?.providerIds, if (data == null) 0 else 1, parsed.details)
        if (data?.kind == "series" || data?.kind == "season") {
            db.exec("""WITH RECURSIVE descendants(scope,doc) AS (SELECT scope,parent FROM objects WHERE id=? UNION
                SELECT o.scope,o.doc FROM descendants d CROSS JOIN objects o WHERE o.scope=d.scope AND o.parent=d.doc AND o.kind='DIRECTORY')
                UPDATE frontier SET phase='metadata' WHERE run=? AND phase='done' AND (scope,doc) IN (SELECT scope,doc FROM descendants)""", item.id, claim.run)
        }
        }
        db.exec("UPDATE scan_run SET parsed=parsed+? WHERE id=?", items.size, claim.run)
    }
    @Synchronized fun metadataFailed(claim: ScanClaim, work: DirectoryWork) = transaction {
        valid(claim)
        // Enumeration completed. Retry metadata next scan without suppressing absence settlement.
        db.exec("UPDATE frontier SET phase='metadata_failed' WHERE run=? AND scope=? AND doc=?", claim.run, work.scope, work.documentId)
    }
    @Synchronized fun directoryDone(claim: ScanClaim, work: DirectoryWork) = transaction {
        valid(claim)
        db.exec("UPDATE frontier SET phase='done' WHERE run=? AND scope=? AND doc=?", claim.run, work.scope, work.documentId)
    }
    private data class Sidecar(val id: Long, val parent: String, val stem: String, val kind: String,
        val revision: Long, val data: NfoData?)
    private fun sidecars(scope: String, parent: String, stems: List<String>): List<Sidecar> {
        if (stems.isEmpty()) return emptyList()
        return db.rows("""SELECT o.id,o.parent,o.stem,o.kind,o.revision,n.valid,n.kind,n.title,n.year,n.plot,n.show,n.season,n.episode,n.ids
            FROM objects o LEFT JOIN nfo n ON n.object=o.id WHERE o.scope=? AND o.parent=?
            AND o.stem IN (${stems.joinToString(",") { "?" }}) AND o.kind IN ('NFO','IMAGE')
            AND EXISTS(SELECT 1 FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND m.misses<2 AND r.active=1)
            ORDER BY o.id LIMIT 1024""", scope, parent, *stems.toTypedArray()) {
            Sidecar(it.getLong(0), it.getText(1), it.getText(2), it.getText(3), it.getLong(4),
                if (it.isNull(5) || it.getLong(5) != 1L) null else NfoData(it.getText(6), it.textOrNull(7), it.longOrNull(8)?.toInt(),
                    it.textOrNull(9), it.textOrNull(10), it.longOrNull(11)?.toInt(), it.longOrNull(12)?.toInt(), it.textOrNull(13)))
        }
    }
    /** Resolve a bounded media window using one sidecar query and one shared ancestor walk. */
    @Synchronized fun project(claim: ScanClaim, work: DirectoryWork, after: Long): Long? {
        valid(claim)
        data class Video(val id: Long, val name: String, val stem: String, val revision: Long, val input: String?)
        val videos = db.rows("""SELECT o.id,o.name,o.stem,o.revision,w.input FROM objects o LEFT JOIN wall w ON w.object=o.id
            JOIN seen s ON s.run=? AND s.scope=o.scope AND s.doc=o.doc
            WHERE o.scope=? AND o.parent=? AND o.kind='VIDEO' AND o.id>? ORDER BY o.id LIMIT 128""",
            claim.run, work.scope, work.documentId, after) { Video(it.getLong(0), it.getText(1), it.getText(2), it.getLong(3), it.textOrNull(4)) }
        if (videos.isEmpty()) return null
        return transaction {
        val artNames = listOf("fanart", "backdrop", "clearlogo", "logo")
        val stems = (videos.flatMap { listOf(it.stem, "${it.stem}-poster") + artNames.map { role -> "${it.stem}-$role" } } + listOf("movie", "tvshow", "season", "poster", "folder") + artNames).distinct()
        val local = sidecars(work.scope, work.documentId, stems)
        val localByStem = local.groupBy { it.stem }
        // Only the single-video case matters for generic movie sidecars/artwork.
        val count = db.scalar("SELECT COUNT(*) FROM (SELECT 1 FROM seen WHERE run=? AND scope=? AND parent=? AND kind='VIDEO' LIMIT 2)", claim.run, work.scope, work.documentId)
        val ancestors = mutableListOf<Sidecar>()
        var parent: String? = work.documentId
        val visited = mutableSetOf<String>()
        while (parent != null && visited.add(parent)) {
            if (visited.size > 512) throw java.io.IOException("Metadata ancestry exceeds supported depth")
            val items = if (parent == work.documentId) local else sidecars(work.scope, parent, listOf("tvshow", "season", "poster", "folder") + artNames)
            ancestors.addAll(items.filter { it.stem in listOf("tvshow", "season", "poster", "folder") + artNames })
            // Keep only the closest show/season context, not a whole tree's metadata.
            if (items.any { it.data?.kind == "series" }) break
            parent = db.rows("SELECT parent FROM objects WHERE scope=? AND doc=?", work.scope, parent) { it.textOrNull(0) }.firstOrNull()
        }
        val series = ancestors.firstOrNull { it.stem == "tvshow" && it.data?.kind == "series" }
        val season = ancestors.firstOrNull { it.stem == "season" && it.data?.kind == "season" }
        val language = scanMetadataLocale(claim.run)
        var writes = 0
        for (v in videos) {
            val exact = localByStem[v.stem].orEmpty().filter { it.kind == "NFO" }
            val nfo = if (exact.size == 1) exact.single() else if (exact.isEmpty() && count == 1L)
                localByStem["movie"].orEmpty().filter { it.data?.kind == "movie" }.singleOrNull() else null
            val data = nfo?.data?.takeIf { it.kind == "movie" || it.kind == "episode" }
            val poster = listOf("${v.stem}-poster", v.stem).firstNotNullOfOrNull { name ->
                localByStem[name].orEmpty().singleOrNull { it.kind == "IMAGE" }
            } ?: (if (count == 1L || data?.kind == "episode") localByStem["poster"].orEmpty().firstOrNull { it.kind == "IMAGE" }
                ?: localByStem["folder"].orEmpty().firstOrNull { it.kind == "IMAGE" } else null)
                ?: (if (data?.kind == "episode") ancestors.firstOrNull { it.kind == "IMAGE" && it.parent == series?.parent && it.stem in setOf("poster", "folder") } else null)
            fun artwork(names: List<String>, seriesOnly: Boolean = false): Sidecar? =
                (if (seriesOnly) emptyList() else names.mapNotNull { name -> localByStem["${v.stem}-$name"].orEmpty().singleOrNull { it.kind == "IMAGE" } }).firstOrNull()
                    ?: (if (!seriesOnly && (count == 1L || data?.kind == "episode")) names.firstNotNullOfOrNull { name -> localByStem[name].orEmpty().singleOrNull { it.kind == "IMAGE" } } else null)
                    ?: (if (data?.kind == "episode") names.firstNotNullOfOrNull { name -> ancestors.singleOrNull { it.kind == "IMAGE" && it.parent == series?.parent && it.stem == name } } else null)
            val artwork = mapOf("backdrop" to artwork(listOf("fanart", "backdrop")), "logo" to artwork(listOf("clearlogo", "logo")),
                "series_backdrop" to artwork(listOf("fanart", "backdrop"), true), "series_logo" to artwork(listOf("clearlogo", "logo"), true))
            val dependencies = (listOfNotNull(nfo, poster, if (data?.kind == "episode") series else null, if (data?.kind == "episode") season else null) + artwork.values.filterNotNull()).distinctBy { it.id }
            val input = "${v.revision}:${LocalNfo.VERSION}:${v.name}:" + dependencies.joinToString(";") { "${it.id}/${it.revision}" }
            if (input == v.input) continue
            db.exec("""INSERT INTO wall(object,title,kind,year,plot,show,season,episode,poster,input,visible) VALUES(?,?,?,?,?,?,?,?,?,?,1)
                ON CONFLICT(object) DO UPDATE SET title=excluded.title,kind=excluded.kind,year=excluded.year,plot=excluded.plot,
                show=excluded.show,season=excluded.season,episode=excluded.episode,poster=excluded.poster,input=excluded.input""",
                v.id, data?.title ?: v.name, data?.kind ?: "file", data?.year, data?.plot,
                data?.show ?: if (data?.kind == "episode") series?.data?.title else null, data?.season, data?.episode, poster?.id, input)
            db.exec("DELETE FROM dependency WHERE owner=?", v.id)
            dependencies.forEach { db.exec("INSERT OR IGNORE INTO dependency VALUES(?,?)", v.id, it.id) }
            db.exec("DELETE FROM wall_artwork WHERE owner=?", v.id)
            artwork.forEach { (role, image) -> if (image != null) db.exec("INSERT INTO wall_artwork VALUES(?,?,?)", v.id, role, image.id) }
            writes++
        }
        if (language != null) metadataCandidates(videos.map { it.id }, language, System.currentTimeMillis()).forEach {
            enqueueScanMetadata(claim.run, it)
        }
        if (writes > 0) db.exec("UPDATE scan_run SET projections=projections+? WHERE id=?", writes, claim.run)
        videos.last().id
        }
    }

    /** Immutable row IDs are the keyset cursor; no OFFSET or full-library Kotlin list. */
    @Synchronized fun page(before: Long = Long.MAX_VALUE, limit: Int = 60): List<CatalogCard> {
        require(limit in 1..60)
        return cards("w.visible=1 AND w.object<?", arrayOf(before), "ORDER BY w.object DESC LIMIT $limit")
    }
    @Synchronized fun cardsById(ids: List<Long>): List<CatalogCard> {
        require(ids.size <= 60)
        if (ids.isEmpty()) return emptyList()
        return cards("w.visible=1 AND w.object IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray(), "ORDER BY w.object DESC")
    }
    private fun cards(where: String, args: Array<out Any?>, tail: String): List<CatalogCard> = enrich(db.rows("""SELECT w.object,w.title,o.name,o.doc,
        (SELECT r.uri FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND m.misses<2 AND r.active=1 ORDER BY r.id LIMIT 1),
        w.kind,w.year,w.plot,w.show,w.season,w.episode,p.doc,
        (SELECT r.uri FROM member m JOIN roots r ON r.id=m.root WHERE m.object=p.id AND m.misses<2 AND r.active=1 ORDER BY r.id LIMIT 1),p.revision,o.version
        FROM wall w JOIN objects o ON o.id=w.object LEFT JOIN objects p ON p.id=w.poster WHERE $where $tail""", *args) {
        fun uri(tree: String?, doc: String?, version: String? = null): String? = if (tree == null || doc == null) null
            else if (cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(tree)) cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.uri(
                Uri.parse(tree).authority!!, cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.ref(tree).copy(id = doc), version)
            else if (NasLocator.isNas(tree)) NasLocator.file(tree, doc) else DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), doc).toString()
        CatalogCard(it.getLong(0), it.getText(1), it.getText(2), uri(it.textOrNull(4), it.getText(3), it.textOrNull(14)).orEmpty(),
            it.getText(5), it.longOrNull(6)?.toInt(), it.textOrNull(7), it.textOrNull(8), it.longOrNull(9)?.toInt(),
            it.longOrNull(10)?.toInt(), uri(it.textOrNull(12), it.textOrNull(11)), it.longOrNull(13)?.toString())
    })

    private fun enrich(cards: List<CatalogCard>): List<CatalogCard> {
        if (cards.isEmpty()) return cards
        val layers = db.rows("SELECT object,origin,json_remove(payload,'$.details','$.seriesDetails') FROM metadata_binding WHERE object IN (${cards.joinToString(",") { "?" }}) ORDER BY priority",
            *cards.map { it.id }.toTypedArray()) { Triple(it.getLong(0), it.getText(1), MetadataRecord.parse(it.getText(2), includeDetails = false)) }.groupBy { it.first }
        return cards.map { original ->
            var card = original
            for ((_, origin, r) in layers[card.id].orEmpty()) {
                val replace = origin != "provider" || original.kind == "file"
                card = card.copy(title = if (replace && r.title.isNotBlank()) r.title else card.title,
                    kind = if (replace) r.kind else card.kind, year = if (replace) r.year ?: card.year else card.year ?: r.year,
                    plot = if (replace) r.plot ?: card.plot else card.plot ?: r.plot,
                    show = if (replace) r.show else card.show ?: r.show,
                    season = if (replace) r.season else card.season ?: r.season,
                    episode = if (replace) r.episodes.firstOrNull() else card.episode ?: r.episodes.firstOrNull(),
                    poster = original.poster ?: r.poster ?: card.poster, posterVersion = if (original.poster != null) original.posterVersion else r.version,
                    metadataSource = origin, locked = origin == "manual")
                if (replace && r.episodes.isNotEmpty()) card = card.copy(episodes = r.episodes)
            }
            card
        }
    }

    @Synchronized fun metadataInput(id: Long): MetadataInput? = metadataInputs(listOf(id)).firstOrNull()
    @Synchronized fun metadataInputs(ids: List<Long>): List<MetadataInput> {
        require(ids.size <= 128)
        if (ids.isEmpty()) return emptyList()
        return db.rows("""SELECT o.name,
        CAST(o.revision AS TEXT)||':'||o.name||':'||w.input||':'||COALESCE((SELECT group_concat(r.id||'/'||r.revision) FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND r.active=1 AND m.misses<2),'') ,
        COALESCE(e.revision,0),w.kind,w.title,w.year,w.plot,w.show,w.season,w.episode,
        (SELECT n.ids FROM dependency d JOIN nfo n ON n.object=d.object WHERE d.owner=o.id AND n.valid=1 AND n.kind=w.kind LIMIT 1),
        EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=o.id AND b.origin='manual'),
        (SELECT n.ids FROM dependency d JOIN nfo n ON n.object=d.object WHERE d.owner=o.id AND n.valid=1 AND n.kind='series' LIMIT 1),o.id
        FROM wall w JOIN objects o ON o.id=w.object LEFT JOIN metadata_edit e ON e.object=o.id
        WHERE w.visible=1 AND o.id IN (${ids.joinToString(",") { "?" }}) ORDER BY o.id""", *ids.toTypedArray()) {
        MetadataInput(it.getLong(13), it.getText(0), it.getText(1), it.getLong(2), if (it.getText(3) == "file") null else
            MetadataRecord("local:${it.getLong(13)}", it.getText(3), it.getText(4), it.longOrNull(5)?.toInt(), it.textOrNull(6),
                it.textOrNull(7), it.longOrNull(8)?.toInt(), it.longOrNull(9)?.let { n -> listOf(n.toInt()) }.orEmpty(), parseIds(it.textOrNull(10))), it.getLong(11) == 1L,
            parseIds(it.textOrNull(12)))
        }
    }

    /** One bounded input window, one attempt window and one alias lookup, including not-due files. */
    @Synchronized fun metadataCandidates(ids: List<Long>, locale: String, now: Long): List<MetadataCandidate> {
        val inputs = metadataInputs(ids)
        if (inputs.isEmpty()) return emptyList()
        val marks = ids.joinToString(",") { "?" }
        data class Attempt(val input: String, val edit: Long, val state: String, val retry: Long)
        val attempts = db.rows("SELECT object,input,edit,state,retry_at FROM metadata_attempt WHERE locale=? AND object IN ($marks)",
            locale, *ids.toTypedArray()) { it.getLong(0) to Attempt(it.getText(1), it.getLong(2), it.getText(3), it.getLong(4)) }.toMap()
        val redirected = db.rows("""SELECT b.object FROM metadata_binding b
            JOIN provider_query q ON q.key='stellar|alias|1|'||json_extract(b.payload,'$.ids."catalog-series"')
            WHERE b.origin='provider' AND q.expires>? AND b.object IN ($marks)""", now, *ids.toTypedArray()) { it.getLong(0) }.toSet()
        return inputs.map { input ->
            val attempt = attempts[input.id]?.takeIf { it.input == input.input && it.edit == input.editRevision }
            MetadataCandidate(input, !input.locked && (attempt == null || attempt.retry <= now || input.id in redirected),
                attempt?.takeIf { it.state in setOf("waiting", "partial") }?.let { it.state to it.retry })
        }
    }

    @Synchronized fun metadataIds(after: Long): List<Long> = db.rows("SELECT object FROM wall WHERE visible=1 AND object>? ORDER BY object LIMIT 128", after) { it.getLong(0) }
    private fun metadataDirectories(id: Long): List<Pair<String, String>> = db.rows("""SELECT p.doc,p.name,g.doc,g.name
        FROM objects o LEFT JOIN objects p ON p.scope=o.scope AND p.doc=o.parent
        AND EXISTS(SELECT 1 FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND m.misses<2 AND r.active=1
            AND (p.doc=r.doc OR EXISTS(SELECT 1 FROM member pm WHERE pm.object=p.id AND pm.root=r.id AND pm.misses<2)))
        LEFT JOIN objects g ON g.scope=p.scope AND g.doc=p.parent
        AND EXISTS(SELECT 1 FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND m.misses<2 AND r.active=1 AND p.doc!=r.doc
            AND (g.doc=r.doc OR EXISTS(SELECT 1 FROM member gm WHERE gm.object=g.id AND gm.root=r.id AND gm.misses<2)))
        WHERE o.id=?""", id) {
        listOfNotNull(it.textOrNull(0)?.let { doc -> doc to it.getText(1) }, it.textOrNull(2)?.let { doc -> doc to it.getText(3) })
    }.firstOrNull().orEmpty()
    @Synchronized fun parentQuery(id: Long): NameQuery? = metadataDirectories(id).let {
        MediaName.directoryQuery(it.getOrNull(0)?.second, it.getOrNull(1)?.second)
    }

    /** The input and edit generation are checked inside the same write transaction. */
    @Synchronized fun bindMetadata(input: MetadataInput, record: MetadataRecord, origin: String, work: ScanMetadataWork? = null): Boolean = transaction {
        require(origin in setOf("provider", "server", "import", "manual"))
        if (work != null && !scanMetadataCurrent(work)) return@transaction false
        val current = metadataInput(input.id) ?: return@transaction false
        if (current.input != input.input || current.editRevision != input.editRevision || (current.locked && origin != "manual")) return@transaction false
        val priority = when (origin) { "manual" -> 100; "import" -> 85; "server" -> 80; else -> 20 }
        record.publicParents.filter { it.kind in setOf("series", "season") && it.version != "identity" }.forEach { parent ->
            val previous = entityMetadata(parent.identity, parent.locale)
            val retained = if (parent.plot == null && previous != null) parent.copy(plot = previous.plot,
                details = parent.details.over(previous.details)) else parent.copy(details = parent.details.retainFailed(previous?.details ?: LibraryDetails()))
            db.exec("""INSERT INTO metadata_entity(identity,locale,payload) VALUES(?,?,?) ON CONFLICT(identity,locale)
                DO UPDATE SET payload=excluded.payload WHERE payload!=excluded.payload""", retained.identity, retained.locale, retained.copy(publicParents = emptyList()).json())
            // A parent overview can change without changing the file binding payload.
            if (db.scalar("SELECT changes()") > 0) db.exec("INSERT OR IGNORE INTO home_dirty VALUES(?)", input.id)
        }
        val payload = record.json()
        db.exec("""INSERT INTO metadata_entity(identity,locale,payload) VALUES(?,?,?) ON CONFLICT(identity,locale) DO UPDATE SET payload=excluded.payload WHERE payload!=excluded.payload""",
            record.identity, record.locale, payload)
        db.exec("""INSERT INTO metadata_binding(object,origin,identity,locale,priority,payload) VALUES(?,?,?,?,?,?)
            ON CONFLICT(object,origin) DO UPDATE SET identity=excluded.identity,locale=excluded.locale,priority=excluded.priority,payload=excluded.payload WHERE payload!=excluded.payload OR identity!=excluded.identity OR locale!=excluded.locale""",
            input.id, origin, record.identity, record.locale, priority, payload)
        if (origin == "manual" || origin == "import") bumpEdit(input.id)
        true
    }
    private fun bumpEdit(id: Long) = db.exec("INSERT INTO metadata_edit VALUES(?,1) ON CONFLICT(object) DO UPDATE SET revision=revision+1", id)
    @Synchronized fun entityMetadata(identity: String, locale: String): MetadataRecord? = db.rows(
        "SELECT payload FROM metadata_entity WHERE identity=? AND lower(locale)=lower(?) LIMIT 1", identity, locale
    ) { MetadataRecord.parse(it.getText(0)) }.firstOrNull()

    @Synchronized fun savedMetadata(id: Long, includeDetails: Boolean = true): MetadataRecord? = db.rows("SELECT ${if (includeDetails) "payload" else "json_remove(payload,'$.details','$.seriesDetails')"} FROM metadata_binding WHERE object=? ORDER BY priority DESC LIMIT 1", id) { MetadataRecord.parse(it.getText(0), includeDetails) }.firstOrNull()
    @Synchronized fun resetMetadata(id: Long) = transaction {
        val scope = metadataScope(id)
        db.exec("DELETE FROM provider_query WHERE instr(key,?)>0 OR instr(key,?)>0", "|$scope|", "\"scope\":\"$scope\"")
        db.exec("DELETE FROM metadata_binding WHERE object=?", id); db.exec("DELETE FROM metadata_attempt WHERE object=?", id); bumpEdit(id)
    }
    @Synchronized fun metadataAttempt(input: MetadataInput, locale: String, state: String, retryAt: Long, error: String? = null, work: ScanMetadataWork? = null): Boolean {
        if (work != null && !scanMetadataCurrent(work)) return false
        val current = metadataInput(input.id) ?: return false
        if (current.input != input.input || current.editRevision != input.editRevision || current.locked) return false
        db.exec("INSERT INTO metadata_attempt(object,input,edit,locale,state,retry_at,error) VALUES(?,?,?,?,?,?,?) ON CONFLICT(object) DO UPDATE SET input=excluded.input,edit=excluded.edit,locale=excluded.locale,state=excluded.state,retry_at=excluded.retry_at,error=excluded.error",
            input.id, input.input, input.editRevision, locale, state, retryAt, error)
        return true
    }
    @Synchronized fun metadataDue(input: MetadataInput, locale: String, now: Long): Boolean = !input.locked &&
        (db.scalar("SELECT COUNT(*) FROM metadata_attempt WHERE object=? AND input=? AND edit=? AND locale=? AND retry_at>?", input.id, input.input, input.editRevision, locale, now) == 0L ||
            redirectedBinding(now, input.id))
    /** Only a server-confirmed series alias can reopen an accepted provider binding. Keep its old display until revalidated. */
    private fun redirectedBinding(now: Long, id: Long? = null): Boolean = db.scalar("""SELECT EXISTS(
        SELECT 1 FROM metadata_binding b JOIN wall w ON w.object=b.object
        JOIN provider_query q ON q.key='stellar|alias|1|'||json_extract(b.payload,'$.ids."catalog-series"')
        WHERE b.origin='provider' AND w.visible=1 AND q.expires>? AND (? IS NULL OR b.object=?)
        AND NOT EXISTS(SELECT 1 FROM metadata_binding m WHERE m.object=b.object AND m.origin='manual'))""", now,id,id) != 0L
    @Synchronized fun previousInputMatches(input: MetadataInput): Boolean = db.scalar("SELECT COUNT(*) FROM metadata_attempt WHERE object=? AND input=? AND edit=? AND state IN ('matched','partial')", input.id,input.input,input.editRevision) == 1L
    @Synchronized fun queryCache(key: String, now: Long): String? = db.rows("SELECT payload FROM provider_query WHERE key=? AND expires>?", key, now) { it.getText(0) }.firstOrNull()
    @Synchronized fun cachedQuery(key: String): CachedMetadataQuery? = db.rows("SELECT expires,etag,payload FROM provider_query WHERE key=?", key) {
        CachedMetadataQuery(it.getLong(0), it.textOrNull(1), it.getText(2))
    }.firstOrNull()
    @Synchronized fun validateQuery(key: String, expires: Long, etag: String?) {
        db.exec("UPDATE provider_query SET expires=?,etag=? WHERE key=?", expires, etag, key)
    }
    @Synchronized fun cacheQuery(key: String, payload: String, expires: Long, etag: String? = null) = transaction {
        db.exec("INSERT INTO provider_query(key,payload,expires,bytes,etag) VALUES(?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload,expires=excluded.expires,bytes=excluded.bytes,etag=excluded.etag", key, payload, expires, payload.toByteArray().size, etag)
        // At most 2049 small index entries, without reading any cached response bodies.
        val (count, bytes) = db.rows("SELECT COUNT(*),coalesce(SUM(bytes),0) FROM provider_query") { it.getLong(0) to it.getLong(1) }.single()
        if (count > 2048) db.exec("DELETE FROM provider_query WHERE key IN (SELECT key FROM provider_query ORDER BY expires DESC,key LIMIT -1 OFFSET 2048)")
        if (bytes > 16777216) db.exec("DELETE FROM provider_query WHERE key IN (SELECT key FROM (SELECT key,SUM(bytes) OVER(ORDER BY expires DESC,key) AS retained FROM provider_query) WHERE retained>16777216)")
    }
    @Synchronized fun removeQuery(key: String) { db.exec("DELETE FROM provider_query WHERE key=?", key) }
    @Synchronized fun clearProviderQueries() { db.exec("DELETE FROM provider_query"); db.exec("DELETE FROM provider_retry"); db.exec("DELETE FROM metadata_attempt") }
    @Synchronized fun retryMetadata() = transaction {
        // Keep accepted identity/input history and validators. A retry does not bypass a global 429.
        db.exec("""UPDATE metadata_attempt SET retry_at=0 WHERE state!='local'
            AND NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=metadata_attempt.object
                AND b.origin IN ('manual','import','server'))""")
        db.exec("UPDATE provider_query SET expires=0 WHERE key NOT LIKE 'stellar|alias|%'")
        db.exec("DELETE FROM provider_retry WHERE key NOT IN (?,?)", MetadataProvider.RATE_KEY, LibraryScanner.RATE_KEY)
    }
    @Synchronized fun providerBound(id: Long): Boolean = db.rows("SELECT origin FROM metadata_binding WHERE object=? ORDER BY priority DESC LIMIT 1", id) {
        it.getText(0)
    }.firstOrNull() == "provider"
    @Synchronized fun dueMetadataLocale(now: Long): String? = db.rows("""SELECT a.locale FROM metadata_attempt a
        JOIN wall w ON w.object=a.object WHERE a.state IN ('matched','partial') AND a.retry_at<=? AND w.visible=1
        AND EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=a.object AND b.origin='provider')
        AND NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=a.object AND b.origin IN ('manual','import','server'))
        LIMIT 1""", now) { it.getText(0) }.firstOrNull()
    @Synchronized fun providerRetry(key: String): Long = db.scalar("SELECT retry_at FROM provider_retry WHERE key=?", key)
    @Synchronized fun providerRetry(key: String, at: Long) {
        db.exec("INSERT INTO provider_retry VALUES(?,?) ON CONFLICT(key) DO UPDATE SET retry_at=max(retry_at,excluded.retry_at)", key, at)
        db.exec("DELETE FROM provider_retry WHERE key IN (SELECT key FROM provider_retry ORDER BY retry_at DESC LIMIT -1 OFFSET 2048)")
    }
    @Synchronized fun clearProviderRetry(key: String) { db.exec("DELETE FROM provider_retry WHERE key=?", key) }
    @Synchronized fun invalidateMetadataIdentity(id: String) {
        db.exec("DELETE FROM provider_query WHERE instr(key,?)>0 OR instr(payload,?)>0", id, id)
    }
    @Synchronized fun metadataScope(id: Long): String = db.rows("""SELECT o.scope,o.parent,
        (SELECT group_concat(r.id||':'||r.revision) FROM member m JOIN roots r ON r.id=m.root WHERE m.object=o.id AND r.active=1 AND m.misses<2),
        (SELECT group_concat(n.object||':'||n.revision) FROM dependency d JOIN nfo n ON n.object=d.object
            WHERE d.owner=o.id AND n.valid=1 AND n.kind IN ('series','season'))
        FROM objects o WHERE o.id=?""", id) {
        val directories = metadataDirectories(id)
        val parent = if (directories.firstOrNull()?.second?.let(MediaName::seasonDirectory) == true)
            directories.getOrNull(1)?.first ?: it.textOrNull(1) else it.textOrNull(1)
        val raw = listOf(it.getText(0),parent,it.textOrNull(2),it.textOrNull(3)).joinToString("|")
        java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }.firstOrNull().orEmpty()
    @Synchronized fun metadataState(id: Long, locale: String): String? = db.rows("SELECT state FROM metadata_attempt WHERE object=? AND locale=?", id, locale) { it.getText(0) }.firstOrNull()
    @Synchronized fun metadataCounts(locale: String): Map<String,Int> = db.rows("""SELECT a.state,COUNT(*) FROM metadata_attempt a JOIN wall w ON w.object=a.object
        WHERE w.visible=1 AND a.locale=? AND NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=a.object AND b.origin='manual') GROUP BY a.state""", locale) { it.getText(0) to it.getInt(1) }.toMap()
    @Synchronized fun nextMetadataRetry(locale: String, now: Long = System.currentTimeMillis()): Long? = if (redirectedBinding(now)) 0L else db.rows("""SELECT MIN(a.retry_at) FROM metadata_attempt a JOIN wall w ON w.object=a.object
        WHERE w.visible=1 AND a.locale=? AND a.state IN ('waiting','partial')
        AND NOT EXISTS(SELECT 1 FROM metadata_binding b WHERE b.object=a.object AND b.origin='manual')""", locale) { it.longOrNull(0) }.firstOrNull()
    @Synchronized fun metadataJob(): MetadataJob? = db.rows("SELECT locale,state,round,next_at,scan_run FROM metadata_job WHERE id=1") {
        MetadataJob(it.getText(0), it.getText(1), it.getInt(2), it.getLong(3), it.longOrNull(4))
    }.firstOrNull()
    @Synchronized fun metadataJob(job: MetadataJob) {
        db.exec("INSERT INTO metadata_job(id,locale,state,round,next_at,scan_run) VALUES(1,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET locale=excluded.locale,state=excluded.state,round=excluded.round,next_at=excluded.next_at,scan_run=excluded.scan_run",
            job.locale, job.state, job.round, job.nextAt, job.scanRun)
    }

    /** The scanner produces durable work in the same transaction as its local projection. */
    @Synchronized fun prepareScanMetadata(claim: ScanClaim, locale: String): String = transaction {
        valid(claim)
        db.exec("DELETE FROM scan_metadata_queue WHERE run!=?", claim.run)
        db.exec("DELETE FROM scan_metadata WHERE run!=?", claim.run)
        db.exec("INSERT OR IGNORE INTO scan_metadata VALUES(?,?)", claim.run, locale)
        // A resumed scan keeps its original language and unfinished input versions.
        db.exec("UPDATE scan_metadata_queue SET attempts=0 WHERE run=? AND attempts>=3", claim.run)
        scanMetadataLocale(claim.run)!!
    }
    @Synchronized fun scanMetadataLocale(run: Long): String? = db.rows("SELECT locale FROM scan_metadata WHERE run=?", run) { it.getText(0) }.firstOrNull()

    private fun enqueueScanMetadata(run: Long, locale: String, id: Long) {
        metadataCandidates(listOf(id), locale, System.currentTimeMillis()).firstOrNull()?.let { enqueueScanMetadata(run, it) }
    }
    private fun enqueueScanMetadata(run: Long, candidate: MetadataCandidate) {
        val (input, due, pending) = candidate
        if (input.locked || (!due && pending == null)) return
        db.exec("""INSERT INTO scan_metadata_queue(object,run,input,edit,retry_at) VALUES(?,?,?,?,?)
            ON CONFLICT(object) DO UPDATE SET run=excluded.run,input=excluded.input,edit=excluded.edit,
            generation=scan_metadata_queue.generation+1,retry_at=excluded.retry_at,attempts=0
            WHERE run!=excluded.run OR input!=excluded.input OR edit!=excluded.edit""", input.id, run, input.input, input.editRevision,
            if (due) 0 else pending!!.second)
    }

    /** A visible placeholder is not evidence that its directory or parent NFO is complete. */
    private fun scanMetadataReady(id: Long, run: Long): Boolean {
        val location = db.rows("""SELECT o.scope,o.parent,r.doc FROM objects o
            JOIN frontier f ON f.run=? AND f.scope=o.scope AND f.doc=o.parent
            JOIN roots r ON r.id=f.root AND r.active=1
            JOIN run_root rr ON rr.run=f.run AND rr.root=r.id AND rr.revision=r.revision
            JOIN scan_run s ON s.id=f.run
            WHERE o.id=? AND s.state!='cancelled' AND s.id=(SELECT MAX(id) FROM scan_run)""", run, id) {
            Triple(it.getText(0), it.getText(1), it.getText(2))
        }.firstOrNull() ?: return false
        var directory: String? = location.second
        val visited = HashSet<String>()
        while (directory != null && visited.add(directory) && visited.size <= 512) {
            if (db.rows("SELECT phase FROM frontier WHERE run=? AND scope=? AND doc=?", run, location.first, directory) { it.getText(0) }.firstOrNull() != "done") return false
            if (directory == location.third) return true
            directory = db.rows("SELECT parent FROM objects WHERE scope=? AND doc=?", location.first, directory) { it.textOrNull(0) }.firstOrNull()
        }
        return false
    }

    @Synchronized fun scanMetadataBatch(run: Long, after: Long, now: Long): List<ScanMetadataWork> = db.rows("""SELECT object,generation,attempts
        FROM scan_metadata_queue INDEXED BY scan_metadata_work WHERE run=? AND object>? AND retry_at<=? AND attempts<3 ORDER BY object LIMIT 128""", run, after, now) {
        ScanMetadataWork(it.getLong(0), run, it.getLong(1), it.getInt(2))
    }
    @Synchronized fun scanMetadataInput(work: ScanMetadataWork): MetadataInput? =
        if (scanMetadataCurrent(work)) metadataInput(work.id) else null

    private fun scanMetadataCurrent(work: ScanMetadataWork): Boolean =
        db.scalar("SELECT COUNT(*) FROM scan_metadata_queue WHERE object=? AND run=? AND generation=?", work.id, work.run, work.generation) == 1L &&
            scanMetadataReady(work.id, work.run)

    @Synchronized fun scanMetadataPending(input: MetadataInput, locale: String): Pair<String, Long>? = db.rows("""SELECT state,retry_at FROM metadata_attempt
        WHERE object=? AND input=? AND edit=? AND locale=? AND state IN ('waiting','partial')""", input.id, input.input, input.editRevision, locale) {
        it.getText(0) to it.getLong(1)
    }.firstOrNull()
    /** A stale completion never removes a replacement task, including a changed low-ID file. */
    @Synchronized fun finishScanMetadata(work: ScanMetadataWork, input: MetadataInput, locale: String, state: String?, retryAt: Long, attempted: Boolean = true) {
        if (!scanMetadataCurrent(work)) return
        val current = metadataInput(work.id)
        if (current != null && !current.locked && (current.input != input.input || current.editRevision != input.editRevision)) {
            enqueueScanMetadata(work.run, locale, work.id)
            return
        }
        if (current != null && !current.locked && state in setOf("waiting", "partial")) {
            db.exec("UPDATE scan_metadata_queue SET attempts=attempts+?,retry_at=? WHERE object=? AND run=? AND generation=?",
                if (attempted) 1 else 0, retryAt, work.id, work.run, work.generation)
        } else db.exec("DELETE FROM scan_metadata_queue WHERE object=? AND run=? AND generation=?", work.id, work.run, work.generation)
    }
    @Synchronized fun nextScanMetadataRetry(run: Long, now: Long): Long? = db.rows("""SELECT MIN(retry_at) FROM scan_metadata_queue
        WHERE run=? AND attempts<3 AND retry_at>?""", run, now) { it.longOrNull(0) }.firstOrNull()
    @Synchronized fun scanMetadataRemaining(run: Long): Long = db.scalar("SELECT COUNT(*) FROM scan_metadata_queue WHERE run=?", run)
    @Synchronized fun pruneScanMetadata(run: Long) {
        db.exec("""DELETE FROM scan_metadata_queue WHERE run=? AND NOT EXISTS(
            SELECT 1 FROM wall WHERE wall.object=scan_metadata_queue.object AND visible=1)""", run)
    }

    @Synchronized fun importStart(scope: String, label: String = ""): Long = transaction {
        val oldSources = homeIndex.sources()
        val reactivated = db.scalar("SELECT COUNT(*) FROM catalog_import WHERE scope=? AND state='obsolete'", scope) != 0L
        db.exec("INSERT INTO catalog_import(scope,generation,state,label) VALUES(?,1,'running',?) ON CONFLICT(scope) DO UPDATE SET generation=generation+1,state='running',label=excluded.label", scope, label)
        // Visibility changed even if the next server payload is byte-identical, including expanded media sources.
        if (reactivated) db.exec("INSERT OR IGNORE INTO home_dirty SELECT -id FROM imported_record WHERE scope=? AND misses<2", scope)
        sourcesChanged = oldSources != homeIndex.sources()
        db.scalar("SELECT generation FROM catalog_import WHERE scope=?", scope)
    }
    @Synchronized fun importPage(scope: String, generation: Long, records: List<MetadataRecord>): Boolean = transaction {
        require(records.size <= 128)
        if (db.scalar("SELECT COUNT(*) FROM catalog_import WHERE scope=? AND generation=? AND state='running'", scope, generation) != 1L) return@transaction false
        var duplicate = false
        for (r in records) {
            if (db.scalar("SELECT COUNT(*) FROM imported_record WHERE scope=? AND identity=? AND seen=?", scope, r.identity, generation) > 0) duplicate = true
            db.exec("INSERT INTO imported_record(scope,identity,payload,seen,misses) VALUES(?,?,?,?,0) ON CONFLICT(scope,identity) DO UPDATE SET payload=excluded.payload,seen=excluded.seen,misses=0", scope, r.identity, r.json(), generation)
            db.exec("DELETE FROM imported_id WHERE scope=? AND identity=?", scope, r.identity)
            r.ids.forEach { (provider, id) -> db.exec("INSERT OR IGNORE INTO imported_id VALUES(?,?,?,?,?)", scope, r.identity, r.kind, provider, id) }
            val parent = db.scalar("SELECT id FROM imported_record WHERE scope=? AND identity=?",scope,r.identity)
            db.exec("UPDATE imported_record SET seen=?,misses=0 WHERE id IN (SELECT record FROM home_server_media WHERE parent=?)",generation,parent)
            val children=db.rows("SELECT m.record,m.source,c.payload FROM home_server_media m JOIN imported_record c ON c.id=m.record WHERE m.parent=?",parent) {
                Triple(it.getLong(0),it.getText(1),MetadataRecord.parse(it.getText(2)))
            }
            for ((childId,source,child) in children) {
                val fresh=r.copy(identity=child.identity,groupIdentity=r.identity,playback=child.playback,version="${r.version}:$source",
                    durationMs=child.durationMs,resumeMs=0,watched=false,lastPlayed=0,versionName=child.versionName).json()
                db.exec("UPDATE imported_record SET payload=? WHERE id=? AND payload!=?",fresh,childId,fresh)
            }
            if (r.kind == "series") db.exec("INSERT OR IGNORE INTO home_dirty SELECT -id FROM imported_record WHERE scope=? AND json_extract(payload,'$.seriesIdentity')=?", scope, r.identity)
        }
        !duplicate
    }
    @Synchronized fun importFinish(scope: String, generation: Long, complete: Boolean) = transaction {
        if (db.scalar("SELECT COUNT(*) FROM catalog_import WHERE scope=? AND generation=? AND state='running'", scope, generation) != 1L) return@transaction
        if (complete) db.exec("UPDATE imported_record SET misses=MIN(2,misses+1) WHERE scope=? AND seen!=?", scope, generation)
        db.exec("UPDATE catalog_import SET state=? WHERE scope=?", if (complete) "complete" else "partial", scope)
    }
    @Synchronized fun importedPage(scope: String, before: Long = Long.MAX_VALUE): List<Pair<Long, MetadataRecord>> = db.rows(
        "SELECT id,payload FROM imported_record WHERE scope=? AND virtual=0 AND misses<2 AND id<? ORDER BY id DESC LIMIT 60", scope, before) { it.getLong(0) to MetadataRecord.parse(it.getText(1)) }
    @Synchronized fun serverRecord(id: Long): String? = db.rows("SELECT payload FROM imported_record WHERE id=? AND virtual=0 AND misses<2",-id) { it.getText(0) }.firstOrNull()
    @Synchronized fun serverVersions(id: Long, input: String, versions: List<cn.coldlake.stellar.opensource.medialibrary.emby.EmbyVersion>): List<Long> = transaction {
        require(versions.size<=128)
        val parent=db.rows("SELECT scope,seen,payload FROM imported_record WHERE id=? AND virtual=0 AND misses<2",-id) { Triple(it.getText(0),it.getLong(1),it.getText(2)) }.firstOrNull() ?: return@transaction emptyList()
        if(parent.third!=input || versions.none { it.streamable }) return@transaction emptyList()
        val r=MetadataRecord.parse(input)
        val baseLocator=r.playback ?: CatalogServerLocator.fromImport(parent.first,r.identity) ?: return@transaction emptyList()
        val result=mutableListOf<Long>()
        val singleSource=versions.count { it.streamable }==1
        for(v in versions.filter { it.streamable }) {
            val locator=Uri.parse(baseLocator).buildUpon().appendQueryParameter("mediaSource",v.id).build().toString()
            val identity="${r.identity}:media-source:${v.id}"
            val child=r.copy(identity=identity,groupIdentity=r.identity,playback=locator,version="${r.version}:${v.id}",
                durationMs=v.durationMs ?: r.durationMs,resumeMs=if(singleSource) r.resumeMs else 0,
                watched=singleSource && r.watched,lastPlayed=if(singleSource) r.lastPlayed else 0,versionName=v.name)
            db.exec("""INSERT INTO imported_record(scope,identity,payload,seen,misses,virtual) VALUES(?,?,?,?,0,1)
                ON CONFLICT(scope,identity) DO UPDATE SET payload=excluded.payload,seen=excluded.seen,misses=0""",parent.first,identity,child.json(),parent.second)
            val childId=db.scalar("SELECT id FROM imported_record WHERE scope=? AND identity=?",parent.first,identity)
            db.exec("INSERT OR REPLACE INTO home_server_media VALUES(?,?,?)",-id,v.id,childId)
            result.add(-childId)
        }
        db.exec("UPDATE imported_record SET misses=2 WHERE id IN (SELECT record FROM home_server_media WHERE parent=?) AND id NOT IN (${result.joinToString(",") { "?" }})",-id,*result.map { -it }.toTypedArray())
        db.exec("INSERT OR IGNORE INTO home_dirty VALUES(?)",id)
        result
    }
    @Synchronized fun importedScopes(): List<String> = db.rows("SELECT scope FROM catalog_import WHERE state!='obsolete' ORDER BY scope") { it.getText(0) }
    @Synchronized fun obsoleteImport(scope: String) = transaction {
        val oldSources = homeIndex.sources()
        db.exec("UPDATE catalog_import SET state='obsolete',generation=generation+1 WHERE scope=?", scope)
        db.exec("INSERT OR IGNORE INTO home_dirty SELECT -id FROM imported_record WHERE scope=?", scope)
        sourcesChanged = oldSources != homeIndex.sources()
    }
    @Synchronized fun importState(scope: String): String? = db.rows("SELECT state FROM catalog_import WHERE scope=?", scope) { it.getText(0) }.firstOrNull()
    @Synchronized fun importedMatch(local: MetadataRecord): MetadataRecord? {
        val accepted = linkedMapOf<String, MetadataRecord>()
        for ((provider, id) in local.ids) {
            val matches = db.rows("""SELECT r.payload FROM imported_id i JOIN imported_record r ON r.scope=i.scope AND r.identity=i.identity
                JOIN catalog_import c ON c.scope=r.scope WHERE i.kind=? AND i.provider=? AND i.external_id=? AND r.misses<2 AND c.state!='obsolete' LIMIT 3""", local.kind, provider, id) { MetadataRecord.parse(it.getText(0)) }
            for (record in matches) {
                if (local.ids.any { (p, value) -> record.ids[p]?.let { it != value } == true }) return null
                accepted[record.identity] = record
            }
        }
        return accepted.values.singleOrNull()
    }
    @Synchronized fun finish(claim: ScanClaim): String = transaction {
        valid(claim)
        check(db.scalar("SELECT COUNT(*) FROM frontier WHERE run=? AND phase NOT IN ('done','failed','metadata_failed')", claim.run) == 0L)
        if (db.scalar("SELECT settled FROM scan_run WHERE id=?", claim.run) == 0L) {
            for (r in roots()) {
                // Exact observed parent graph, not path prefixes; UNION terminates even for a provider cycle.
                // Keep the small recursive frontier outside the indexed child lookup. The reverse
                // loop order rescans the whole scope for every leaf in a wide directory.
                val reach = """WITH RECURSIVE reach(doc) AS (SELECT ? UNION SELECT s.doc FROM reach p CROSS JOIN seen s
                    WHERE s.parent=p.doc AND s.run=? AND s.scope=?) """
                if (scanMetadataLocale(claim.run) != null) {
                    // Overlapping roots can gain membership only during settlement. Revisit just
                    // those directories so completed recognitions cannot miss the new input scope.
                    db.exec(reach + """UPDATE frontier SET phase='metadata' WHERE run=? AND scope=? AND phase='done'
                        AND doc IN (SELECT o.parent FROM reach JOIN objects o ON o.doc=reach.doc
                        WHERE o.scope=? AND o.kind='VIDEO' AND NOT EXISTS(
                            SELECT 1 FROM member m WHERE m.root=? AND m.object=o.id AND m.misses=0))""",
                        r.documentId, claim.run, r.scope, claim.run, r.scope, r.scope, r.id)
                }
                db.exec(reach + """INSERT INTO member(root,object,misses) SELECT ?,o.id,0 FROM reach JOIN objects o ON o.doc=reach.doc
                    WHERE o.scope=? ON CONFLICT(root,object) DO UPDATE SET misses=0 WHERE misses!=0""",
                    r.documentId, claim.run, r.scope, r.id, r.scope)
                val failures = db.rows(reach + """SELECT COALESCE(MAX(f.phase='failed'),0),COALESCE(MAX(f.phase='metadata_failed'),0)
                    FROM reach p CROSS JOIN frontier f WHERE f.run=? AND f.scope=? AND f.doc=p.doc
                    AND f.phase IN ('failed','metadata_failed')""",
                    r.documentId, claim.run, r.scope, claim.run, r.scope) { (it.getLong(0) > 0) to (it.getLong(1) > 0) }.single()
                val incomplete = failures.first ||
                    db.scalar("SELECT COUNT(*) FROM cloud_partial WHERE run=? AND root=?", claim.run, r.id) > 0
                val cloudReady = db.scalar("SELECT COUNT(*) FROM cloud_ready WHERE run=? AND scope=?", claim.run, r.scope) > 0
                if (incomplete && !cloudReady) {
                    val empty = db.scalar(reach + "SELECT COUNT(*) FROM reach", r.documentId, claim.run, r.scope) <= 1
                    db.exec("UPDATE roots SET outcome=? WHERE id=?", if (empty) "failed" else "partial", r.id)
                    continue
                }
                db.exec(reach + """UPDATE member SET misses=MIN(misses+${if (cn.coldlake.stellar.opensource.medialibrary.cloud.CloudLocator.isCloud(r.treeUri)) 2 else 1},2) WHERE root=? AND misses<2
                    AND object NOT IN (SELECT o.id FROM reach JOIN objects o ON o.doc=reach.doc WHERE o.scope=?)""",
                    r.documentId, claim.run, r.scope, r.id, r.scope)
                if (incomplete || failures.second) db.exec("UPDATE roots SET outcome='partial' WHERE id=?", r.id)
                else db.exec("UPDATE roots SET completed=?,outcome='complete' WHERE id=?", System.currentTimeMillis(), r.id)
            }
            refreshVisibility()
            // Confirmed sidecar removals invalidate just their dependent directories.
            db.exec("""UPDATE frontier SET phase='metadata' WHERE run=? AND phase='done' AND EXISTS(SELECT 1 FROM objects o
                JOIN dependency d ON d.owner=o.id WHERE o.scope=frontier.scope AND o.parent=frontier.doc
                AND NOT EXISTS(SELECT 1 FROM member m JOIN roots r ON r.id=m.root
                    WHERE m.object=d.object AND m.misses<2 AND r.active=1))""", claim.run)
            db.exec("UPDATE scan_run SET settled=1 WHERE id=?", claim.run)
            if (db.scalar("SELECT COUNT(*) FROM frontier WHERE run=? AND phase='metadata'", claim.run) > 0) return@transaction "running"
        }
        val complete = db.scalar("SELECT COUNT(*) FROM roots WHERE active=1 AND outcome!='complete'") == 0L
        val allFailed = db.scalar("SELECT COUNT(*) FROM roots WHERE active=1 AND outcome!='failed'") == 0L
        val outcome = if (complete) "complete" else if (allFailed) "failed" else "partial"
        db.exec("UPDATE scan_run SET state=?,current='' WHERE id=?", outcome, claim.run)
        outcome
    }
    private fun refreshVisibility(root: String? = null) {
        val visible = """((SELECT kind FROM objects WHERE id=wall.object)='VIDEO' AND EXISTS(
            SELECT 1 FROM member m JOIN roots r ON r.id=m.root WHERE m.object=wall.object AND m.misses<2 AND r.active=1))"""
        if (root == null) db.exec("UPDATE wall SET visible=$visible WHERE visible!=$visible")
        else db.exec("UPDATE wall SET visible=$visible WHERE object IN (SELECT object FROM member WHERE root=?) AND visible!=$visible", root)
    }
    @Synchronized fun metrics(): Map<String, Long> = db.rows("SELECT queries,batches,discovered,parsed,facts,projections FROM scan_run ORDER BY id DESC LIMIT 1") {
        listOf("queries", "batches", "discovered", "parsed", "facts", "projections").mapIndexed { index, key -> key to it.getLong(index) }.toMap()
    }.firstOrNull().orEmpty() + ("transactionsTotal" to transactionsTotal)
    @Synchronized fun count() = db.scalar("SELECT COUNT(*) FROM wall WHERE visible=1")
    @Synchronized fun explain(): List<String> = db.rows("EXPLAIN QUERY PLAN SELECT object FROM wall WHERE visible=1 AND object<? ORDER BY object DESC LIMIT 60", Long.MAX_VALUE) { it.getText(3) }
    @Synchronized override fun close() { db.close() }

    companion object {
        @Volatile private var shared: CatalogDatabase? = null
        fun shared(context: Context): CatalogDatabase = shared ?: synchronized(this) {
            shared ?: CatalogDatabase(context.applicationContext).also { shared = it }
        }
        private val METADATA_SCHEMA = listOf(
            "CREATE TABLE metadata_entity(identity TEXT,locale TEXT,payload TEXT NOT NULL,PRIMARY KEY(identity,locale))",
            "CREATE TABLE metadata_binding(object INTEGER,origin TEXT,identity TEXT,locale TEXT,priority INTEGER,payload TEXT NOT NULL,PRIMARY KEY(object,origin))",
            "CREATE TABLE metadata_edit(object INTEGER PRIMARY KEY,revision INTEGER NOT NULL)",
            "CREATE TABLE metadata_attempt(object INTEGER PRIMARY KEY,input TEXT,edit INTEGER,locale TEXT,state TEXT,retry_at INTEGER)",
            "CREATE TABLE provider_query(key TEXT PRIMARY KEY,payload TEXT NOT NULL,expires INTEGER NOT NULL)",
            "CREATE TABLE provider_retry(key TEXT PRIMARY KEY,retry_at INTEGER NOT NULL)",
            "CREATE TABLE catalog_import(scope TEXT PRIMARY KEY,generation INTEGER NOT NULL,state TEXT NOT NULL)",
            "CREATE TABLE imported_record(id INTEGER PRIMARY KEY,scope TEXT,identity TEXT,payload TEXT NOT NULL,seen INTEGER,misses INTEGER NOT NULL DEFAULT 0,UNIQUE(scope,identity))",
            "CREATE INDEX imported_page ON imported_record(scope,id DESC)"
            ,"CREATE TABLE imported_id(scope TEXT,identity TEXT,kind TEXT,provider TEXT,external_id TEXT,PRIMARY KEY(scope,identity,provider))"
            ,"CREATE INDEX imported_lookup ON imported_id(kind,provider,external_id)"
        )
        private val SCHEMA = listOf(
            "CREATE TABLE roots(id TEXT PRIMARY KEY,scope TEXT NOT NULL,doc TEXT NOT NULL,uri TEXT NOT NULL,revision INTEGER NOT NULL,label TEXT NOT NULL,active INTEGER NOT NULL,attempted INTEGER,completed INTEGER,outcome TEXT)",
            "CREATE TABLE objects(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,doc TEXT NOT NULL,parent TEXT,name TEXT NOT NULL,stem TEXT NOT NULL,kind TEXT NOT NULL,size INTEGER,modified INTEGER,revision INTEGER NOT NULL DEFAULT 1,version TEXT,UNIQUE(scope,doc))",
            "CREATE INDEX object_parent ON objects(scope,parent,kind,id)",
            "CREATE INDEX object_name ON objects(scope,parent,stem,kind)",
            "CREATE TABLE member(root TEXT NOT NULL,object INTEGER NOT NULL,misses INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(root,object))",
            "CREATE INDEX member_object ON member(object,misses,root)",
            "CREATE TABLE scan_run(id INTEGER PRIMARY KEY,state TEXT NOT NULL,epoch INTEGER NOT NULL,started INTEGER NOT NULL,current TEXT NOT NULL DEFAULT '',queries INTEGER NOT NULL DEFAULT 0,batches INTEGER NOT NULL DEFAULT 0,discovered INTEGER NOT NULL DEFAULT 0,parsed INTEGER NOT NULL DEFAULT 0,facts INTEGER NOT NULL DEFAULT 0,projections INTEGER NOT NULL DEFAULT 0,settled INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE run_root(run INTEGER NOT NULL,root TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(run,root))",
            "CREATE TABLE frontier(run INTEGER NOT NULL,scope TEXT NOT NULL,doc TEXT NOT NULL,root TEXT NOT NULL,phase TEXT NOT NULL,UNIQUE(run,scope,doc))",
            "CREATE INDEX frontier_work ON frontier(run,phase)",
            "CREATE TABLE seen(run INTEGER NOT NULL,scope TEXT NOT NULL,doc TEXT NOT NULL,parent TEXT,kind TEXT NOT NULL,PRIMARY KEY(run,scope,doc))",
            "CREATE INDEX seen_parent ON seen(run,scope,parent)",
            "CREATE TABLE nfo(object INTEGER PRIMARY KEY,revision INTEGER NOT NULL,parser INTEGER NOT NULL,kind TEXT,title TEXT,year INTEGER,plot TEXT,show TEXT,season INTEGER,episode INTEGER,ids TEXT,valid INTEGER NOT NULL)",
            "CREATE TABLE wall(object INTEGER PRIMARY KEY,title TEXT NOT NULL,kind TEXT NOT NULL,year INTEGER,plot TEXT,show TEXT,season INTEGER,episode INTEGER,poster INTEGER,input TEXT NOT NULL,visible INTEGER NOT NULL)",
            "CREATE INDEX wall_page ON wall(visible,object DESC)",
            "CREATE TABLE dependency(owner INTEGER NOT NULL,object INTEGER NOT NULL,PRIMARY KEY(owner,object))",
            "CREATE INDEX dependency_object ON dependency(object,owner)"
        )
    }
}

internal fun SQLiteStatement.bind(args: Array<out Any?>) { args.forEachIndexed { i, value -> when (value) {
    null -> bindNull(i + 1); is Number -> bindLong(i + 1, value.toLong()); else -> bindText(i + 1, value.toString())
} } }
internal fun SQLiteConnection.exec(sql: String, vararg args: Any?) = prepare(sql).use { it.bind(args); while (it.step()) { /* PRAGMA rows */ } }
internal fun <T> SQLiteConnection.rows(sql: String, vararg args: Any?, map: (SQLiteStatement) -> T): List<T> = prepare(sql).use {
    it.bind(args); buildList { while (it.step()) add(map(it)) }
}
internal fun SQLiteConnection.scalar(sql: String, vararg args: Any?): Long = prepare(sql).use { it.bind(args); if (it.step()) it.getLong(0) else 0 }
internal fun SQLiteStatement.textOrNull(index: Int) = if (isNull(index)) null else getText(index)
internal fun SQLiteStatement.longOrNull(index: Int) = if (isNull(index)) null else getLong(index)
