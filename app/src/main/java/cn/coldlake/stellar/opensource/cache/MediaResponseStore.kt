package cn.coldlake.stellar.opensource.cache

import org.json.JSONObject

internal data class DataQuery(val namespace: String, val key: String, val group: String, val kind: String)
internal data class DataEntry(val query: DataQuery, val value: MediaPayload, val states: Map<String, UserStamp>,
    val time: CacheTime, override val bytes: Int, val revision: Long) : DataMemory.Value

/** Called under the cache's IO mutex. Responses own their static fields; members is only a reverse index. */
internal class MediaResponseStore(private val index: CacheDatabase, private val clock: CacheClock,
    private val metrics: CacheMetrics) {
    private val db get() = index.writableDatabase
    fun read(query: DataQuery): DataEntry? {
        metrics.add("diskReads")
        db.rawQuery("SELECT stamp,payload,bytes,group_key,kind FROM responses WHERE key=? AND namespace=?", arrayOf(query.key, query.namespace)).use { c ->
            if (!c.moveToFirst() || c.getString(4) != query.kind) return null
            val time = CacheTime.restore(c.getLong(0), clock) ?: return null
            val value = MediaCacheCodec.response(c.getString(1))
            val states = mutableMapOf<String, UserStamp>()
            for (ids in value.items.map { it.id }.distinct().chunked(256)) {
                db.rawQuery("SELECT id,stamp,payload FROM user_state WHERE namespace=? AND id IN (${slots(ids)})",
                    (listOf(query.namespace) + ids).toTypedArray()).use { rows ->
                    while (rows.moveToNext()) {
                        val stamp = CacheTime.restore(rows.getLong(1), clock) ?: continue
                        val json = JSONObject(rows.getString(2))
                        states[rows.getString(0)] = UserStamp(json.getBoolean("favorite"), json.getBoolean("played"),
                            json.getLong("resume"), json.getInt("unplayed"), stamp, 0)
                    }
                }
            }
            return DataEntry(query.copy(group=c.getString(3)), value, states, time, c.getInt(2), 0)
        }
    }
    fun persist(e: DataEntry, payload: String, localState: (String) -> UserStamp?) {
        if (index.indexBytes() > 64L * 1024 * 1024) { metrics.add("skippedWrites"); return }
        transaction {
            val q = e.query
            db.execSQL("INSERT OR REPLACE INTO responses(key,namespace,stamp,touched,kind,payload,bytes,group_key) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any>(q.key, q.namespace, e.time.wall, clock.wall(), q.kind, payload, e.bytes, q.group))
            db.delete("members", "key=?", arrayOf(q.key))
            db.compileStatement("INSERT OR IGNORE INTO members VALUES(?,?,?)").use { s ->
                for (item in e.value.items) { s.bindString(1,q.key); s.bindString(2,q.namespace); s.bindString(3,item.id); s.executeInsert() }
            }
            for ((id, original) in e.states) {
                val state = localState(id)?.takeIf { it.revision > original.revision } ?: original
                // API 26 SQLite supports INSERT SELECT; do not overwrite a newer user state.
                db.execSQL("INSERT OR REPLACE INTO user_state SELECT ?,?,?,? WHERE NOT EXISTS(" +
                    "SELECT 1 FROM user_state WHERE namespace=? AND id=? AND stamp>?)",
                    arrayOf<Any>(q.namespace,id,state.time.wall,state.encode(),q.namespace,id,state.time.wall))
            }
            pruneStates()
            remove(keys("SELECT key FROM responses ORDER BY touched DESC LIMIT -1 OFFSET 1000"))
            var bytes = db.rawQuery("SELECT coalesce(sum(bytes),0) FROM responses",null).use { it.moveToFirst(); it.getLong(0) }
            while (bytes > 24L * 1024 * 1024) {
                val oldest = db.rawQuery("SELECT key,bytes FROM responses ORDER BY touched LIMIT 16",null)
                    .use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
                remove(oldest.map { it.first }); bytes -= oldest.sumOf { it.second }
            }
        }
        metrics.add("writeTransactions")
    }
    fun keys(sql: String, args: Array<String>? = null): List<String> = db.rawQuery(sql,args)
        .use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun remove(keys: List<String>) {
        for (batch in keys.chunked(256)) for (table in listOf("responses", "members"))
            db.delete(table,"key IN (${slots(batch)})",batch.toTypedArray())
    }
    fun invalidate(where: String, args: Array<String>) = transaction {
        remove(keys("SELECT key FROM responses WHERE $where",args))
    }
    fun clearStates(namespace: String) { db.delete("user_state","namespace=?",arrayOf(namespace)) }
    fun touch(batch: Map<String,Long>) = transaction {
        db.compileStatement("UPDATE responses SET touched=? WHERE key=?").use { s ->
            for ((key,time) in batch) { s.bindLong(1,time); s.bindString(2,key); s.executeUpdateDelete() }
        }
        metrics.add("touchTransactions")
    }
    fun pruneStates() {
        db.execSQL("DELETE FROM user_state WHERE stamp<? OR stamp>?",arrayOf(clock.wall()-120_000,clock.wall()))
        // Same order of magnitude as the 1000-response / 60-item page budget, independent of references.
        db.execSQL("DELETE FROM user_state WHERE rowid IN (SELECT rowid FROM user_state ORDER BY stamp DESC LIMIT -1 OFFSET 60000)")
    }
    fun transaction(action: () -> Unit) {
        db.beginTransaction()
        try { action(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun slots(values: List<String>) = values.joinToString(",") { "?" }
}
