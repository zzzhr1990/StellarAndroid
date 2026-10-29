package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.sqlite.SQLiteConnection
import cn.coldlake.stellar.opensource.playback.reporting.*
import org.json.JSONObject

/** Shares library_catalog.db's writer and transaction with its disposable home projection. */
internal class PlaybackRecordStore(private val db: SQLiteConnection) {
    companion object {
        val schema = listOf(
            """CREATE TABLE IF NOT EXISTS playback_record(key TEXT PRIMARY KEY,position INTEGER NOT NULL,duration INTEGER,
                watched INTEGER NOT NULL,resume_eligible INTEGER NOT NULL,last_watched INTEGER NOT NULL,
                revision INTEGER NOT NULL,session TEXT NOT NULL,seq INTEGER NOT NULL,ended INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS playback_delivery(key TEXT NOT NULL,target TEXT NOT NULL,session TEXT NOT NULL,
                seq INTEGER NOT NULL,revision INTEGER NOT NULL,ack_revision INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,error TEXT,baseline TEXT,PRIMARY KEY(key,target))""",
            "CREATE TABLE IF NOT EXISTS playback_version(version INTEGER PRIMARY KEY,key TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS playback_version_key ON playback_version(key)",
            "CREATE TABLE IF NOT EXISTS playback_server(target TEXT,item TEXT,version TEXT,key TEXT,PRIMARY KEY(target,item,version))",
            "ALTER TABLE home_progress ADD COLUMN resume_eligible INTEGER NOT NULL DEFAULT 0",
            "UPDATE home_progress SET resume_eligible=CASE WHEN position>0 AND watched=0 THEN 1 ELSE 0 END",
            "DROP INDEX IF EXISTS home_progress_continue",
            "CREATE INDEX home_progress_continue ON home_progress(updated DESC,version DESC) WHERE position>0 AND resume_eligible=1"
        )
    }
    fun migrate() {
        db.rows("SELECT p.version,v.payload FROM home_progress p LEFT JOIN home_version v ON p.version=v.id") {
            it.getLong(0) to it.textOrNull(1)?.let { raw -> catalogCard(raw).uri }
        }.forEach { (id, uri) -> bind(id, uri?.let { PlaybackIdentity.locator(it) } ?: PlaybackIdentity.key("legacy-catalog", id.toString())) }
    }
    fun read(key: PlaybackRecordKey): PlaybackRecord? = db.rows("SELECT * FROM playback_record WHERE key=?", key.value) {
        PlaybackRecord(key, it.getLong(1), it.longOrNull(2), it.getLong(3) != 0L, it.getLong(4) != 0L,
            it.getLong(5), it.getLong(6), it.getText(7), it.getLong(8), it.getLong(9) != 0L)
    }.firstOrNull()
    fun key(version: Long): PlaybackRecordKey? = db.rows("SELECT key FROM playback_version WHERE version=?", version) {
        PlaybackRecordKey(it.getText(0))
    }.firstOrNull()
    fun bind(version: Long, key: PlaybackRecordKey, replace: Boolean = false): PlaybackRecordKey {
        val previous = key(version)
        if (!replace) previous?.takeIf { it == key || it.value.startsWith("disc:") }?.let { return it }
        db.exec("INSERT INTO playback_version VALUES(?,?) ON CONFLICT(version) DO UPDATE SET key=excluded.key", version, key.value)
        val record = read(key)
        if (record == null && previous != null && previous != key) db.exec("DELETE FROM home_progress WHERE version=?", version)
        if (record == null && previous == null) {
            db.exec("""INSERT OR IGNORE INTO playback_record
                SELECT ?,position,NULLIF(duration,0),watched,resume_eligible,updated,1,'',0,1 FROM home_progress WHERE version=?""", key.value, version)
        }
        return key
    }
    fun project(key: PlaybackRecordKey, home: CatalogHome) {
        val r = read(key) ?: return
        db.rows("SELECT version FROM playback_version WHERE key=?", key.value) { it.getLong(0) }.forEach {
            home.progress(it, r.positionMs, r.durationMs ?: 0, r.watched, r.lastWatchedAt, r.resumeEligible)
        }
    }
    fun commit(key: PlaybackRecordKey, fact: PlaybackFact, target: String?, baseline: ServerBaseline?): PlaybackRecord? {
        val old = read(key)
        if (!fact.opensSession && (old?.session != fact.session || old.ended || fact.sequence <= old.sequence)) return null
        if (fact.opensSession && old?.session == fact.session && fact.sequence <= old.sequence) return null
        val watched = old?.watched == true || baseline?.played == true || fact.finish == FinishReason.ENDED
        val revision = (old?.revision ?: 0) + if (fact.persist) 1 else 0
        val record = if (fact.persist) PlaybackRecord(key, fact.positionMs, fact.durationMs ?: old?.durationMs, watched,
            fact.finish != FinishReason.ENDED && fact.positionMs > 0, fact.lastWatchedAt, revision,
            fact.session, fact.sequence, fact.kind == ReportKind.FINISH) else old ?: return null
        if (fact.persist) put(record)
        if (target != null && fact.report) db.exec("""INSERT INTO playback_delivery(key,target,session,seq,revision,state,baseline)
            VALUES(?,?,?,?,?,'Pending',?) ON CONFLICT(key,target) DO UPDATE SET session=excluded.session,seq=excluded.seq,
            revision=excluded.revision,state='Pending',error=NULL,baseline=CASE WHEN playback_delivery.session!=excluded.session THEN excluded.baseline ELSE COALESCE(playback_delivery.baseline,excluded.baseline) END""",
            key.value, target, fact.session, fact.sequence, revision, baseline?.encode())
        return record
    }
    private fun put(r: PlaybackRecord) = db.exec("""INSERT INTO playback_record VALUES(?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(key) DO UPDATE SET position=excluded.position,duration=excluded.duration,watched=excluded.watched,
        resume_eligible=excluded.resume_eligible,last_watched=excluded.last_watched,revision=excluded.revision,
        session=excluded.session,seq=excluded.seq,ended=excluded.ended""", r.key.value, r.positionMs, r.durationMs,
        if (r.watched) 1 else 0, if (r.resumeEligible) 1 else 0, r.lastWatchedAt, r.revision, r.session, r.sequence, if (r.ended) 1 else 0)

    fun command(version: Long, position: Long, duration: Long, watched: Boolean, home: CatalogHome) {
        val key = key(version) ?: db.rows("SELECT payload FROM home_version WHERE id=?", version) {
            PlaybackIdentity.locator(catalogCard(it.getText(0)).uri)
        }.firstOrNull() ?: PlaybackIdentity.key("legacy-catalog", version.toString())
        bind(version, key)
        val old = read(key)
        put(PlaybackRecord(key, position.coerceAtLeast(0), duration.takeIf { it > 0 }, watched, !watched && position > 0,
            System.currentTimeMillis(), (old?.revision ?: 0) + 1, "", 0, true))
        db.exec("UPDATE playback_delivery SET state='Unconfirmed',revision=?,session='',seq=0,error='ManualChange' WHERE key=?",
            (old?.revision ?: 0) + 1, key.value)
        project(key, home)
    }
    fun serverCommand(target: String, item: String, position: Long, watched: Boolean, home: CatalogHome): List<PlaybackRecord> =
        db.rows("SELECT key FROM playback_server WHERE target=? AND item=?", target, item) { PlaybackRecordKey(it.getText(0)) }.mapNotNull { key ->
            val old = read(key) ?: return@mapNotNull null
            val updated = old.copy(positionMs = position, watched = watched, resumeEligible = false, revision = old.revision + 1,
                lastWatchedAt = System.currentTimeMillis(), session = "", sequence = 0, ended = true)
            put(updated)
            db.exec("""INSERT INTO playback_delivery(key,target,session,seq,revision,state) VALUES(?,?,'',0,?,'Pending')
                ON CONFLICT(key,target) DO UPDATE SET session='',seq=0,revision=excluded.revision,state='Pending',error=NULL""",
                key.value, target, updated.revision)
            project(key, home)
            updated
        }
    fun delivery(key: PlaybackRecordKey, target: String): PlaybackDelivery? = db.rows(
        "SELECT revision,ack_revision,state,baseline,error FROM playback_delivery WHERE key=? AND target=?", key.value, target) {
        PlaybackDelivery(it.getLong(0), it.getLong(1), DeliveryState.valueOf(it.getText(2)), it.textOrNull(3)?.let(::decodeBaseline), it.textOrNull(4) == "ReadbackPending")
    }.firstOrNull()
    fun acknowledge(key: PlaybackRecordKey, target: String, fact: PlaybackFact, revision: Long,
        state: DeliveryState, error: String?, baseline: ServerBaseline? = null) {
        // An old ACK may record receipt, but cannot clear a newer pending revision/session/sequence.
        db.exec("""UPDATE playback_delivery SET ack_revision=CASE WHEN ?='Acknowledged' THEN MAX(ack_revision,?) ELSE ack_revision END,
            state=CASE WHEN seq=? AND revision=? THEN ? ELSE state END,
            error=CASE WHEN seq=? AND revision=? THEN ? ELSE error END,
            baseline=CASE WHEN seq=? AND revision=? AND ? IS NOT NULL THEN ? ELSE baseline END
            WHERE key=? AND target=? AND session=?""", state.name, revision, fact.sequence, revision, state.name,
            fact.sequence, revision, error, fact.sequence, revision, baseline?.encode(), baseline?.encode(), key.value, target, fact.session)
    }
    fun recover() {
        db.exec("UPDATE playback_record SET ended=1 WHERE ended=0")
        db.exec("UPDATE playback_delivery SET state='Unconfirmed',error='ProcessEnded' WHERE state IN ('Pending','Retryable')")
    }
}
private fun ServerBaseline.encode() = JSONObject().put("position", positionMs).put("played", played)
    .put("lastPlayed", lastPlayed).put("version", version).toString()
private fun decodeBaseline(raw: String): ServerBaseline = JSONObject(raw).let {
    ServerBaseline(it.getLong("position"), it.getBoolean("played"), it.optString("lastPlayed"),
        if (it.isNull("version")) null else it.getString("version"))
}
