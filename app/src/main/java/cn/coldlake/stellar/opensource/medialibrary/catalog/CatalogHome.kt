package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.sqlite.SQLiteConnection
import org.json.JSONObject

internal enum class HomeSection { CONTINUE, RECENT, MOVIES, SERIES, UNRECOGNIZED }
internal data class HomeCursor(val order: Long = Long.MAX_VALUE, val id: Long = Long.MAX_VALUE)
internal data class HomeCard(val id: Long, val order: Long, val card: CatalogCard, val versions: Int,
    val episodes: Int, val resume: Long = 0, val duration: Long = 0)
internal data class HomeSource(val id: String, val label: String)
internal data class HomeVersion(val id: Long, val card: CatalogCard, val source: String,
    val uncertain: Boolean, val position: Long, val duration: Long, val watched: Boolean, val updated: Long, val resumeEligible: Boolean = position > 0 && !watched)

internal fun CatalogCard.json(): String = JSONObject().put("id", id).put("title", title).put("fileName", fileName)
    .put("uri", uri).put("kind", kind).put("year", year).put("plot", plot).put("show", show)
    .put("season", season).put("episode", episode).put("poster", poster).put("posterVersion", posterVersion)
    .put("metadataSource", metadataSource).put("locked", locked)
    .put("episodes", org.json.JSONArray(episodes.ifEmpty { listOfNotNull(episode) })).toString()
internal fun catalogCard(raw: String): CatalogCard {
    val j = JSONObject(raw)
    fun text(k: String) = if (j.isNull(k)) null else j.getString(k)
    fun number(k: String) = if (j.isNull(k)) null else j.getInt(k)
    return CatalogCard(j.getLong("id"), j.getString("title"), j.getString("fileName"), j.getString("uri"),
        j.getString("kind"), number("year"), text("plot"), text("show"), number("season"), number("episode"),
        text("poster"), text("posterVersion"), text("metadataSource"), j.optBoolean("locked"),
        j.optJSONArray("episodes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty())
}

/** Durable, incrementally maintained projection. Called only under CatalogDatabase's monitor, off main. */
internal class CatalogHome(private val db: SQLiteConnection, private val catalog: CatalogDatabase) {
    private val affectedQueries = mutableSetOf<HomeQuery>()

    fun takeAffected(): Set<HomeQuery> = affectedQueries.toSet().also { affectedQueries.clear() }

    private fun invalidate(entity: Long) {
        db.rows("SELECT source,kind FROM home_entity_source WHERE entity=? UNION ALL SELECT NULL,kind FROM home_entity WHERE id=?", entity, entity) {
            it.textOrNull(0) to it.getText(1)
        }.forEach { (source, kind) ->
            affectedQueries += HomeQuery(HomeSection.RECENT, source)
            affectedQueries += HomeQuery(HomeSection.CONTINUE, source)
            val section = when (kind) { "movie" -> HomeSection.MOVIES; "series" -> HomeSection.SERIES; else -> HomeSection.UNRECOGNIZED }
            affectedQueries += HomeQuery(section, source)
        }
    }
    companion object {
        // Actor portraits are resolved beside the NFO, but are not wall_artwork dependencies.
        // Their changes must invalidate only the films/series that can reference that directory.
        private fun artworkDirty(scope: String, directory: String) = """
            INSERT INTO home_dirty SELECT candidate.id FROM (
            SELECT id FROM objects WHERE scope=$scope AND kind='VIDEO' AND parent IN (
                SELECT $directory UNION SELECT parent FROM objects WHERE scope=$scope AND doc=$directory AND name='.actors')
            UNION SELECT d.owner FROM dependency d JOIN objects n ON n.id=d.object
                WHERE n.scope=$scope AND n.kind='NFO' AND n.parent IN (
                    SELECT $directory UNION SELECT parent FROM objects WHERE scope=$scope AND doc=$directory AND name='.actors')
            ) candidate WHERE NOT EXISTS(SELECT 1 FROM home_dirty WHERE id=candidate.id);
        """
        val artworkTriggers = listOf(
            "CREATE TRIGGER IF NOT EXISTS home_art_insert AFTER INSERT ON objects WHEN new.kind='IMAGE' OR (new.kind='DIRECTORY' AND new.name='.actors') BEGIN ${artworkDirty("new.scope", "new.parent")} END",
            "CREATE TRIGGER IF NOT EXISTS home_art_update AFTER UPDATE ON objects WHEN old.kind='IMAGE' OR new.kind='IMAGE' OR old.name='.actors' OR new.name='.actors' BEGIN ${artworkDirty("old.scope", "old.parent")} ${artworkDirty("new.scope", "new.parent")} END",
            """CREATE TRIGGER IF NOT EXISTS home_art_member AFTER UPDATE ON member WHEN new.misses!=old.misses
                AND EXISTS(SELECT 1 FROM objects WHERE id=new.object AND kind='IMAGE') BEGIN
                ${artworkDirty("(SELECT scope FROM objects WHERE id=new.object)", "(SELECT parent FROM objects WHERE id=new.object)")} END"""
        )
        val schema = listOf(
            "CREATE TABLE home_dirty(id INTEGER PRIMARY KEY)",
            "ALTER TABLE catalog_import ADD COLUMN label TEXT NOT NULL DEFAULT ''",
            "CREATE INDEX imported_series ON imported_record(scope,json_extract(payload,'$.seriesIdentity'))",
            "CREATE TABLE home_entity(id INTEGER PRIMARY KEY,identity TEXT UNIQUE,kind TEXT,payload TEXT,added INTEGER NOT NULL DEFAULT 0,versions INTEGER NOT NULL DEFAULT 0,episodes INTEGER NOT NULL DEFAULT 0,played INTEGER NOT NULL DEFAULT 0,resume INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX home_page ON home_entity(added DESC,id DESC) WHERE versions>0",
            "CREATE INDEX home_kind ON home_entity(kind,added DESC,id DESC) WHERE versions>0",
            "CREATE INDEX home_continue ON home_entity(played DESC,id DESC) WHERE versions>0 AND resume!=0",
            "CREATE TABLE home_external(kind TEXT,provider TEXT,value TEXT,entity INTEGER,PRIMARY KEY(kind,provider,value,entity))",
            "CREATE INDEX home_external_entity ON home_external(entity)",
            "CREATE TABLE home_version(seq INTEGER PRIMARY KEY,id INTEGER UNIQUE,entity INTEGER,leaf TEXT,payload TEXT,season INTEGER,episode INTEGER,visible INTEGER)",
            "CREATE INDEX home_versions ON home_version(entity,visible,season,episode,id)",
            "CREATE TABLE home_source(version INTEGER,source TEXT,PRIMARY KEY(version,source))",
            "CREATE TABLE home_entity_source(entity INTEGER,source TEXT,kind TEXT,added INTEGER,PRIMARY KEY(entity,source))",
            "CREATE INDEX home_source_page ON home_entity_source(source,added DESC,entity DESC)",
            "CREATE INDEX home_source_kind ON home_entity_source(source,kind,added DESC,entity DESC)",
            "CREATE TABLE home_progress(version INTEGER PRIMARY KEY,position INTEGER,duration INTEGER,watched INTEGER,updated INTEGER)",
            "INSERT INTO home_dirty SELECT object FROM wall",
            "INSERT INTO home_dirty SELECT -id FROM imported_record",
            "CREATE TRIGGER home_wall_insert AFTER INSERT ON wall BEGIN INSERT OR IGNORE INTO home_dirty VALUES(new.object); END",
            "CREATE TRIGGER home_wall_update AFTER UPDATE ON wall BEGIN INSERT OR IGNORE INTO home_dirty VALUES(new.object); END",
            "CREATE TRIGGER home_binding_insert AFTER INSERT ON metadata_binding BEGIN INSERT OR IGNORE INTO home_dirty VALUES(new.object); END",
            "CREATE TRIGGER home_binding_update AFTER UPDATE ON metadata_binding BEGIN INSERT OR IGNORE INTO home_dirty VALUES(new.object); END",
            "CREATE TRIGGER home_binding_delete AFTER DELETE ON metadata_binding BEGIN INSERT OR IGNORE INTO home_dirty VALUES(old.object); END",
            "CREATE TRIGGER home_import_insert AFTER INSERT ON imported_record BEGIN INSERT OR IGNORE INTO home_dirty VALUES(-new.id); END",
            "CREATE TRIGGER home_import_update AFTER UPDATE ON imported_record WHEN new.payload!=old.payload OR new.misses!=old.misses BEGIN INSERT OR IGNORE INTO home_dirty VALUES(-new.id); END",
            "CREATE TRIGGER home_member_insert AFTER INSERT ON member BEGIN INSERT OR IGNORE INTO home_dirty SELECT new.object WHERE EXISTS(SELECT 1 FROM wall WHERE object=new.object); END",
            "CREATE TRIGGER home_member_update AFTER UPDATE ON member WHEN new.misses!=old.misses BEGIN INSERT OR IGNORE INTO home_dirty SELECT new.object WHERE EXISTS(SELECT 1 FROM wall WHERE object=new.object); END"
        )
    }

    fun pending() = db.scalar("SELECT EXISTS(SELECT 1 FROM home_dirty)") != 0L

    /** At most sixty payloads are materialized, regardless of catalog size. */
    fun drain(): Boolean {
        val ids = db.rows("SELECT id FROM home_dirty ORDER BY id LIMIT 60") { it.getLong(0) }
        if (ids.isEmpty()) return false
        val cards = catalog.cardsById(ids.filter { it > 0 }).associateBy { it.id }
        val affected = mutableSetOf<Long>()
        for (id in ids) {
            val previousEntity = db.rows("SELECT entity FROM home_version WHERE id=?", id) { it.getLong(0) }.firstOrNull()
            previousEntity?.let(affected::add)
            // Capture the old kind/source membership before a metadata reclassification or removal.
            previousEntity?.let(::invalidate)
            var record: MetadataRecord? = null
            var importScope: String? = null
            val card = if (id > 0) cards[id] else {
                db.rows("""SELECT json_remove(r.payload,'$.details','$.seriesDetails'),r.scope FROM imported_record r JOIN catalog_import c ON c.scope=r.scope
                    WHERE r.id=? AND r.misses<2 AND c.state!='obsolete'
                    AND NOT EXISTS(SELECT 1 FROM home_server_media WHERE parent=r.id)""", -id) {
                    MetadataRecord.parse(it.getText(0)) to it.getText(1)
                }.firstOrNull()?.let { (r, scope) ->
                    record = r; importScope = scope
                    // The old import scope already contains exact server/user/revision and item identity.
                    (r.playback ?: CatalogServerLocator.fromImport(scope,r.identity))?.takeIf { r.kind in setOf("movie", "episode") }?.let { uri ->
                        CatalogCard(id, r.title, r.versionName ?: r.title, uri, r.kind, r.year, r.plot, r.show, r.season,
                            r.episodes.firstOrNull(), r.poster, r.version, "server", episodes = r.episodes)
                    }
                }
            }
            if (card == null) {
                db.exec("UPDATE home_version SET visible=0 WHERE id=? AND visible!=0", id)
                db.exec("DELETE FROM home_source WHERE version=?", id)
            } else {
                if (id > 0) {
                    val local = catalog.metadataInput(id)?.local
                    val saved = catalog.savedMetadata(id, includeDetails = false)
                    record = if (card.metadataSource == "provider" && local != null) local else saved ?: local
                }
                val r = record
                val kind = if (card.kind == "episode") "series" else card.kind
                val external = if (kind == "series") seriesIds(id, r, importScope) else r?.ids.orEmpty().filterKeys { !it.endsWith("-series") }
                val fallback = if (kind == "series") seriesFallback(id, r) else r?.groupIdentity ?: r?.identity ?: "file:$id"
                val entity = entity(kind, external, fallback, previousEntity)
                val leaf = if (kind == "series") "${card.season}:${r?.episodes?.joinToString(",") ?: card.episode}:$fallback" else fallback
                val payload = card.json()
                db.exec("""INSERT INTO home_version(id,entity,leaf,payload,season,episode,visible) VALUES(?,?,?,?,?,?,1)
                    ON CONFLICT(id) DO UPDATE SET entity=excluded.entity,leaf=excluded.leaf,payload=excluded.payload,
                    season=excluded.season,episode=excluded.episode,visible=1
                    WHERE entity!=excluded.entity OR leaf!=excluded.leaf OR payload!=excluded.payload OR visible!=1""",
                    id, entity, leaf, payload, card.season ?: -1, card.episode ?: -1)
                db.exec("DELETE FROM home_source WHERE version=?", id)
                if (id > 0) db.exec("""INSERT INTO home_source SELECT ?,'root:'||m.root FROM member m JOIN roots r ON r.id=m.root
                    WHERE m.object=? AND m.misses<2 AND r.active=1""", id, id)
                else db.exec("INSERT INTO home_source VALUES(?,?)", id, "server:$importScope")
                if (id < 0 && r != null && db.scalar("SELECT COUNT(*) FROM home_progress WHERE version=?", id) == 0L && (r.resumeMs > 0 || r.watched))
                    db.exec("INSERT INTO home_progress(version,position,duration,watched,updated,resume_eligible) VALUES(?,?,?,?,?,?)", id, r.resumeMs, r.durationMs, if (r.watched) 1 else 0, r.lastPlayed, if (r.resumeMs > 0 && !r.watched) 1 else 0)
                cn.coldlake.stellar.opensource.playback.reporting.PlaybackIdentity.locator(card.uri)?.let { key ->
                    PlaybackRecordStore(db).apply { project(bind(id, key), this@CatalogHome) }
                }
                affected.add(entity)
            }
            db.exec("DELETE FROM home_dirty WHERE id=?", id)
        }
        affected.forEach {
            summarize(it); invalidate(it)
            db.exec("UPDATE home_entity SET content_revision=content_revision+1 WHERE id=?", it)
        }
        return true
    }

    private fun seriesIds(id: Long, record: MetadataRecord?, scope: String?): Map<String, String> {
        record?.ids?.filterKeys { it.endsWith("-series") }?.mapKeys { it.key.removeSuffix("-series") }?.takeIf { it.isNotEmpty() }?.let { return it }
        if (scope != null && record?.seriesIdentity != null) {
            return db.rows("SELECT payload FROM imported_record WHERE scope=? AND identity=? AND misses<2", scope, record.seriesIdentity) {
                MetadataRecord.parse(it.getText(0)).ids
            }.firstOrNull().orEmpty()
        }
        return if (id > 0) db.rows("""SELECT n.ids FROM dependency d JOIN nfo n ON n.object=d.object
            WHERE d.owner=? AND n.kind='series' AND n.valid=1 LIMIT 1""", id) { parseIds(it.textOrNull(0)) }.firstOrNull().orEmpty() else emptyMap()
    }
    private fun seriesFallback(id: Long, record: MetadataRecord?): String = record?.seriesIdentity
        ?: if (id > 0) db.rows("""SELECT n.object FROM dependency d JOIN nfo n ON n.object=d.object
            WHERE d.owner=? AND n.kind='series' AND n.valid=1 LIMIT 1""", id) { "local-series:${it.getLong(0)}" }.firstOrNull() ?: "file:$id"
        else "file:$id"

    private fun entity(kind: String, ids: Map<String, String>, fallback: String, previous: Long?): Long {
        val candidates = ids.flatMap { (p, v) -> db.rows("SELECT entity FROM home_external WHERE kind=? AND provider=? AND value=? LIMIT 2", kind, p, v) { it.getLong(0) } }.distinct()
        val existing = candidates.singleOrNull()?.takeIf { e ->
            db.rows("SELECT provider,value FROM home_external WHERE entity=?", e) { it.getText(0) to it.getText(1) }
                .none { (p, v) -> ids[p]?.let { it != v } == true }
        }
        val identity = "$kind:$fallback"
        val known = db.rows("SELECT id FROM home_entity WHERE identity=?", identity) { it.getLong(0) }.firstOrNull()
        val reusable = previous?.takeIf { known == null && db.scalar("SELECT COUNT(*) FROM home_version WHERE entity=? AND visible=1", it) <= 1L }
        val e = existing ?: known ?: reusable?.also {
            db.exec("DELETE FROM home_external WHERE entity=?", it)
            db.exec("UPDATE home_entity SET identity=?,kind=? WHERE id=?", identity, kind, it)
        } ?: run {
            db.exec("INSERT OR IGNORE INTO home_entity(identity,kind) VALUES(?,?)", identity, kind)
            db.scalar("SELECT id FROM home_entity WHERE identity=?", identity)
        }
        // A conflict stays isolated; it must not become a bridge that merges two different films.
        if (candidates.isEmpty() || existing != null) ids.forEach { (p, v) -> db.exec("INSERT OR IGNORE INTO home_external VALUES(?,?,?,?)", kind, p, v, e) }
        return e
    }

    private fun summarize(entity: Long) {
        val representative = db.rows("SELECT payload FROM home_version WHERE entity=? AND visible=1 ORDER BY id DESC LIMIT 1", entity) { catalogCard(it.getText(0)) }.firstOrNull()
        if (representative == null) {
            db.exec("UPDATE home_entity SET versions=0,resume=0 WHERE id=?", entity)
            db.exec("DELETE FROM home_entity_source WHERE entity=?", entity)
            return
        }
        val kind = db.rows("SELECT kind FROM home_entity WHERE id=?", entity) { it.getText(0) }.single()
        val card = if (kind == "series") {
            val record = catalog.savedMetadata(representative.id, includeDetails = false)
            val parent = record?.ids?.get("catalog-series")?.let { catalog.entityMetadata("catalog:series:$it", record.locale) }
                ?: if (representative.id < 0) db.rows("""SELECT p.payload FROM imported_record c
                    JOIN imported_record p ON p.scope=c.scope AND p.identity=json_extract(c.payload,'$.seriesIdentity')
                    JOIN catalog_import s ON s.scope=c.scope WHERE c.id=? AND c.misses<2 AND p.misses<2 AND s.state!='obsolete' LIMIT 1""",
                    -representative.id) { MetadataRecord.parse(it.getText(0)) }.firstOrNull() else null
            val local = db.rows("""SELECT n.title,n.plot,n.year FROM dependency d JOIN nfo n ON n.object=d.object
                WHERE d.owner=? AND n.kind='series' AND n.valid=1 ORDER BY n.object LIMIT 1""", representative.id) {
                Triple(it.textOrNull(0), it.textOrNull(1), it.longOrNull(2)?.toInt())
            }.firstOrNull()
            representative.copy(title = local?.first ?: parent?.title ?: representative.show ?: representative.title,
                plot = local?.second ?: parent?.plot, year = local?.third ?: parent?.year,
                poster = representative.poster ?: parent?.poster, kind = "series", season = null, episode = null, episodes = emptyList())
        } else representative
        db.exec("""UPDATE home_entity SET payload=?,added=(SELECT MAX(seq) FROM home_version WHERE entity=?),
            versions=(SELECT COUNT(*) FROM home_version WHERE entity=? AND visible=1),
            episodes=(SELECT COUNT(DISTINCT CAST(v.season AS TEXT)||':'||CAST(j.value AS TEXT)) FROM home_version v,json_each(v.payload,'$.episodes') j WHERE v.entity=? AND v.visible=1)
            WHERE id=?""", card.json(), entity, entity, entity, entity)
        db.exec("DELETE FROM home_entity_source WHERE entity=?", entity)
        db.exec("""INSERT INTO home_entity_source SELECT DISTINCT ?,s.source,e.kind,e.added FROM home_version v
            JOIN home_source s ON s.version=v.id JOIN home_entity e ON e.id=v.entity WHERE v.entity=? AND v.visible=1""", entity, entity)
        summarizeProgress(entity)
    }
    private fun summarizeProgress(entity: Long) {
        val latest = db.rows("""SELECT p.version,p.updated FROM home_progress p JOIN home_version v ON v.id=p.version
            WHERE v.entity=? AND v.visible=1 AND p.resume_eligible=1 AND p.position>0 ORDER BY p.updated DESC,p.version DESC LIMIT 1""", entity) { it.getLong(0) to it.getLong(1) }.firstOrNull()
        db.exec("UPDATE home_entity SET resume=?,played=? WHERE id=?", latest?.first ?: 0, latest?.second ?: 0, entity)
    }

    fun progress(id: Long, position: Long, duration: Long, watched: Boolean, time: Long = System.currentTimeMillis(), resumeEligible: Boolean = position > 0 && !watched) {
        if (db.scalar("SELECT COUNT(*) FROM home_version WHERE id=?", id) == 0L) return
        db.exec("""INSERT INTO home_progress(version,position,duration,watched,updated,resume_eligible) VALUES(?,?,?,?,?,?) ON CONFLICT(version) DO UPDATE SET
            position=excluded.position,duration=excluded.duration,watched=excluded.watched,updated=excluded.updated,resume_eligible=excluded.resume_eligible
            WHERE position!=excluded.position OR duration!=excluded.duration OR watched!=excluded.watched OR updated!=excluded.updated OR resume_eligible!=excluded.resume_eligible""",
            id, position.coerceAtLeast(0), duration.coerceAtLeast(0), if (watched) 1 else 0, time, if (resumeEligible) 1 else 0)
        if (db.scalar("SELECT changes()") == 0L) return
        db.rows("SELECT entity FROM home_version WHERE id=?", id) { it.getLong(0) }.firstOrNull()?.let {
            summarizeProgress(it); invalidate(it)
            db.exec("UPDATE home_entity SET progress_revision=progress_revision+1 WHERE id=?", it)
        }
    }

    fun sources(): List<HomeSource> = db.rows("SELECT 'root:'||id,label FROM roots WHERE active=1 ORDER BY label") { HomeSource(it.getText(0), it.getText(1)) } +
        db.rows("SELECT 'server:'||scope,label FROM catalog_import WHERE state!='obsolete' ORDER BY scope") {
            HomeSource(it.getText(0), it.getText(1))
        }

    fun details(entity: Long, source: String?): LibraryDetails {
        val row = detailVersion(entity, source)
        return row?.let { catalog.details(it.first, it.second) } ?: LibraryDetails()
    }
    fun detailRevisions(entity: Long): Pair<Long, Long>? = db.rows(
        "SELECT content_revision,progress_revision FROM home_entity WHERE id=?", entity
    ) { it.getLong(0) to it.getLong(1) }.firstOrNull()
    fun detailVersion(entity: Long, source: String?): Pair<Long, Boolean>? = db.rows("""SELECT v.id,e.kind FROM home_version v JOIN home_entity e ON e.id=v.entity
            WHERE v.entity=? AND v.visible=1 AND (? IS NULL OR EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?))
            ORDER BY v.id DESC LIMIT 1""", entity, source, source) { it.getLong(0) to (it.getText(1) == "series") }.firstOrNull()

    private fun query(section: HomeSection, source: String?, cursor: HomeCursor, limit: Int): Pair<String, Array<Any?>> {
        require(limit in 1..60)
        if (section == HomeSection.CONTINUE && source != null) {
            // Walk recent progress, not every entity in the source. The inner lookup
            // selects one latest version per entity, retaining source-specific resume.
            return """SELECT e.id,p.updated,e.payload,e.versions,e.episodes,p.position,p.duration
                FROM home_progress p INDEXED BY home_progress_continue
                CROSS JOIN home_version v ON v.id=p.version CROSS JOIN home_entity e ON e.id=v.entity
                WHERE p.position>0 AND p.resume_eligible=1 AND v.visible=1 AND e.versions>0
                AND EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?)
                AND p.version=(SELECT v2.id FROM home_version v2 JOIN home_progress hp ON hp.version=v2.id
                    JOIN home_source hs ON hs.version=v2.id WHERE v2.entity=e.id AND v2.visible=1 AND hs.source=?
                    AND hp.position>0 AND hp.resume_eligible=1 ORDER BY hp.updated DESC,v2.id DESC LIMIT 1)
                AND (p.updated,e.id)<(?,?) ORDER BY p.updated DESC,e.id DESC LIMIT $limit""" to
                arrayOf(source,source,cursor.order,cursor.id)
        }
        val order = if (section == HomeSection.CONTINUE) (if (source == null) "e.played" else "p.updated") else if (source == null) "e.added" else "s.added"
        val kind = when (section) { HomeSection.MOVIES -> "movie"; HomeSection.SERIES -> "series"; HomeSection.UNRECOGNIZED -> "file"; else -> null }
        val args = mutableListOf<Any?>()
        val from = if (source == null) "home_entity e" else "home_entity_source s JOIN home_entity e ON e.id=s.entity"
        val where = buildString {
            append("e.versions>0")
            if (source != null) { append(" AND s.source=?"); args.add(source) }
            if (kind != null) { append(if (source == null) " AND e.kind=?" else " AND s.kind=?"); args.add(kind) }
            if (section == HomeSection.CONTINUE) append(if (source == null) " AND e.resume!=0" else " AND p.version IS NOT NULL")
            append(" AND ($order,e.id)<(?,?)"); args.add(cursor.order); args.add(cursor.id)
        }
        val progress = if (source == null) "e.resume" else """(SELECT v.id FROM home_version v JOIN home_progress hp ON hp.version=v.id
            JOIN home_source hs ON hs.version=v.id WHERE v.entity=e.id AND v.visible=1 AND hs.source=s.source AND hp.position>0 AND hp.resume_eligible=1
            ORDER BY hp.updated DESC,v.id DESC LIMIT 1)"""
        return """SELECT e.id,$order,e.payload,e.versions,e.episodes,COALESCE(p.position,0),COALESCE(p.duration,0)
            FROM $from LEFT JOIN home_progress p ON p.version=$progress WHERE $where ORDER BY $order DESC,e.id DESC LIMIT $limit""" to args.toTypedArray()
    }
    fun page(section: HomeSection, source: String? = null, cursor: HomeCursor = HomeCursor(), limit: Int = 20): List<HomeCard> {
        val (sql, args) = query(section, source, cursor, limit)
        return db.rows(sql, *args) { HomeCard(it.getLong(0), it.getLong(1), catalogCard(it.getText(2)), it.getLong(3).toInt(), it.getLong(4).toInt(), it.getLong(5), it.getLong(6)) }
    }
    fun explain(section: HomeSection, source: String? = null): List<String> {
        val (sql, args) = query(section, source, HomeCursor(), 20)
        return db.rows("EXPLAIN QUERY PLAN $sql", *args) { it.getText(3) }
    }
    fun byIds(ids: List<Long>, source: String? = null): List<HomeCard> {
        require(ids.size <= 60)
        if (ids.isEmpty()) return emptyList()
        val progress=if(source==null) "e.resume" else """(SELECT v.id FROM home_version v JOIN home_progress hp ON hp.version=v.id
            JOIN home_source hs ON hs.version=v.id WHERE v.entity=e.id AND v.visible=1 AND hs.source=? AND hp.position>0 AND hp.resume_eligible=1
            ORDER BY hp.updated DESC,v.id DESC LIMIT 1)"""
        val found = db.rows("""SELECT e.id,e.added,e.payload,e.versions,e.episodes,COALESCE(p.position,0),COALESCE(p.duration,0)
            FROM home_entity e LEFT JOIN home_progress p ON p.version=$progress WHERE e.versions>0 AND e.id IN (${ids.joinToString(",") { "?" }})
            ${if (source == null) "" else "AND EXISTS(SELECT 1 FROM home_entity_source hs WHERE hs.entity=e.id AND hs.source=?)"}""",
            *(listOfNotNull(source) + ids.map { it as Any } + listOfNotNull(source)).toTypedArray()) {
            HomeCard(it.getLong(0), it.getLong(1), catalogCard(it.getText(2)), it.getLong(3).toInt(), it.getLong(4).toInt(), it.getLong(5), it.getLong(6))
        }.associateBy { it.id }
        return ids.mapNotNull(found::get)
    }
    fun versions(entity: Long, source: String? = null, after: Long = Long.MIN_VALUE, season: Int? = null, exact: Long? = null): List<HomeVersion> {
        // The tuple preserves episode order even when scan/import order differs from broadcast order.
        val args = mutableListOf<Any?>(entity)
        val filter = buildString {
            if (exact != null) { append(" AND v.id=?"); args.add(exact) }
            else if (after != Long.MIN_VALUE) {
                append(" AND (v.season,v.episode,v.id)>(SELECT season,episode,id FROM home_version WHERE id=?)"); args.add(after)
            }
            if (source != null) { append(" AND EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?)"); args.add(source) }
            if (season != null) { append(" AND v.season=?"); args.add(season) }
        }
        return db.rows("""SELECT v.id,v.payload,COALESCE(p.position,0),COALESCE(p.duration,0),COALESCE(p.watched,0),COALESCE(p.updated,0),
            (SELECT group_concat(COALESCE(r.label,c.label,''), ' · ') FROM home_source s LEFT JOIN roots r ON s.source='root:'||r.id LEFT JOIN catalog_import c ON s.source='server:'||c.scope WHERE s.version=v.id),
            EXISTS(SELECT 1 FROM home_source s JOIN roots r ON s.source='root:'||r.id WHERE s.version=v.id AND r.outcome IN ('failed','partial')),COALESCE(p.resume_eligible,0)
            FROM home_version v LEFT JOIN home_progress p ON p.version=v.id WHERE v.entity=? AND v.visible=1 $filter ORDER BY v.season,v.episode,v.id LIMIT 60""", *args.toTypedArray()) {
            HomeVersion(it.getLong(0), catalogCard(it.getText(1)), it.textOrNull(6).orEmpty(), it.getLong(7)==1L, it.getLong(2), it.getLong(3), it.getLong(4)==1L, it.getLong(5), it.getLong(8)==1L)
        }
    }
    fun seasons(entity: Long, source: String? = null): List<Int> = db.rows("""SELECT DISTINCT season FROM home_version v WHERE entity=? AND visible=1 AND season>=0
        AND (? IS NULL OR EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?)) ORDER BY season LIMIT 200""", entity, source, source) { it.getLong(0).toInt() }
    fun versionCount(entity: Long, source: String?, season: Int? = null): Int = db.scalar("""SELECT COUNT(*) FROM home_version v
        WHERE entity=? AND visible=1 AND (? IS NULL OR v.season=?)
        AND (? IS NULL OR EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?))""", entity, season, season, source, source).toInt()
    fun preferred(entity: Long, source: String?, season: Int? = null): Long? = db.rows("""SELECT v.id FROM home_version v LEFT JOIN home_progress p ON p.version=v.id WHERE v.entity=? AND v.visible=1
        AND (? IS NULL OR v.season=?)
        ${if (source == null) "" else "AND EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?)"}
        ORDER BY (COALESCE(p.position,0)>0 AND COALESCE(p.resume_eligible,0)=1) DESC,COALESCE(p.updated,0) DESC,v.season,v.episode,v.id LIMIT 1""",
        *(listOf<Any?>(entity, season, season) + listOfNotNull(source)).toTypedArray()) { it.getLong(0) }.firstOrNull()

    /** Open-page metadata lookup only; never walk the library to warm public relations. */
    fun publicRecord(entity: Long, source: String?, season: Int? = null): MetadataRecord? {
        val version = db.rows("""SELECT v.id,e.kind FROM home_version v JOIN home_entity e ON e.id=v.entity
            WHERE v.entity=? AND v.visible=1 AND (? IS NULL OR v.season=?)
            AND (? IS NULL OR EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?))
            ORDER BY v.id DESC LIMIT 1""", entity, season, season, source, source) { it.getLong(0) to it.getText(1) }.firstOrNull() ?: return null
        val record = if (version.first > 0) catalog.savedMetadata(version.first) else db.rows(
            "SELECT payload FROM imported_record WHERE id=? AND misses<2", -version.first) { MetadataRecord.parse(it.getText(0)) }.firstOrNull()
        record ?: return null
        if (version.second != "series") return record.takeIf { it.identity.startsWith("catalog:movie:") }
        val kind = if (season == null) "series" else "season"
        val id = record.ids["catalog-$kind"] ?: return null
        return catalog.entityMetadata("catalog:$kind:$id", record.locale)
            ?: record.publicParents.firstOrNull { it.identity == "catalog:$kind:$id" }
    }

    /** Selected file only. The catalog has no reliable historical added timestamp. */
    fun fileSize(id: Long): Long? = if (id <= 0) null else db.rows(
        "SELECT size FROM objects WHERE id=?", id) { if (it.isNull(0)) null else it.getLong(0) }.firstOrNull()

    /** Only the current entity's verified IDs, constrained to the selected source. */
    fun ratingsTarget(entity: Long, source: String?): RatingsTarget? {
        val rows = db.rows("""SELECT e.kind,x.provider,x.value FROM home_entity e JOIN home_external x ON x.entity=e.id
            WHERE e.id=? AND e.versions>0 AND x.kind=e.kind AND x.provider IN ('catalog','imdb')
            AND (? IS NULL OR EXISTS(SELECT 1 FROM home_entity_source s WHERE s.entity=e.id AND s.source=?)) LIMIT 4""",
            entity, source, source) { Triple(it.getText(0), it.getText(1), it.getText(2)) }
        val imdb = rows.filter { it.second == "imdb" }.singleOrNull()
        if (imdb != null) RatingsTarget.imdb(imdb.first, imdb.third)?.let { return it }
        val owned = rows.filter { it.second == "catalog" }.singleOrNull()
        return owned?.let { RatingsTarget.entity(it.first, it.third) }
    }

    /** Exact public identity, source membership and ambiguity checks use the existing index. */
    fun ownedPublic(records: List<MetadataRecord>, source: String?): Map<String, Long> {
        require(records.size <= 50)
        return records.mapNotNull { record ->
            val id = record.ids["catalog"] ?: return@mapNotNull null
            val candidates = db.rows("""SELECT x.entity FROM home_external x JOIN home_entity e ON e.id=x.entity
                WHERE x.kind=? AND x.provider='catalog' AND x.value=? AND e.versions>0
                AND (? IS NULL OR EXISTS(SELECT 1 FROM home_entity_source s WHERE s.entity=e.id AND s.source=?)) LIMIT 2""",
                record.kind, id, source, source) { it.getLong(0) }
            candidates.singleOrNull()?.let { id to it }
        }.toMap()
    }

    /** Coordinates are considered only inside this verified public series, never by title. */
    fun ownedEpisodes(entity: Long, source: String?, seriesId: String, season: Int, episodes: List<Int>): Set<Int> {
        require(episodes.size <= 50)
        if (episodes.isEmpty() || publicRecord(entity, source)?.ids?.get("catalog") != seriesId) return emptySet()
        return db.rows("""SELECT DISTINCT CAST(j.value AS INTEGER) FROM home_version v,json_each(v.payload,'$.episodes') j
            WHERE v.entity=? AND v.visible=1 AND v.season=? AND CAST(j.value AS INTEGER) IN (${episodes.joinToString(",") { "?" }})
            AND (? IS NULL OR EXISTS(SELECT 1 FROM home_source s WHERE s.version=v.id AND s.source=?))""",
            *(listOf<Any?>(entity, season) + episodes + listOf(source, source)).toTypedArray()) { it.getLong(0).toInt() }.toSet()
    }
}
