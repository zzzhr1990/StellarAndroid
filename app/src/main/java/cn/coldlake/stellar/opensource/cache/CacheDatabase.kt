package cn.coldlake.stellar.opensource.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** These databases contain disposable cache data only; never account or library records. */
internal class CacheDatabase(context: Context, val directory: File, private val images: Boolean) :
    SQLiteOpenHelper(context, File(directory, "index.db").absolutePath, null, 4) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.rawQuery("PRAGMA journal_size_limit=1048576",null).use { it.moveToFirst() }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clear_intent(namespace TEXT PRIMARY KEY)")
        if (images) {
            db.execSQL("CREATE TABLE images(key TEXT PRIMARY KEY, namespace TEXT NOT NULL, stamp INTEGER NOT NULL, touched INTEGER NOT NULL, ready INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX image_namespace ON images(namespace,touched)")
            db.execSQL("CREATE INDEX image_recency ON images(touched DESC,key DESC)")
        } else {
            createResponses(db)
            db.execSQL("CREATE TABLE members(key TEXT NOT NULL, namespace TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(key,id))")
            db.execSQL("CREATE INDEX member_item ON members(namespace,id)")
            db.execSQL("CREATE TABLE user_state(namespace TEXT NOT NULL, id TEXT NOT NULL, stamp INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(namespace,id))")
            db.execSQL("CREATE INDEX user_state_stamp ON user_state(stamp)")
        }
    }
    private fun createResponses(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE responses(key TEXT PRIMARY KEY, namespace TEXT NOT NULL, stamp INTEGER NOT NULL, touched INTEGER NOT NULL, kind TEXT NOT NULL, bytes INTEGER NOT NULL, group_key TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX response_namespace ON responses(namespace,touched)")
        db.execSQL("CREATE INDEX response_recency ON responses(touched,key,bytes)")
        db.execSQL("CREATE INDEX response_group ON responses(group_key,key)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (images && oldVersion < 3) db.execSQL("CREATE INDEX image_recency ON images(touched DESC,key DESC)")
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS epochs")
            if (!images) {
                // Old shared items cannot recover query/language-specific snapshots reliably.
                // Invalidate only disposable metadata, retaining pending cleanup intents.
                db.execSQL("DROP TABLE responses")
                createResponses(db)
                for (table in listOf("members", "user_state")) db.delete(table, null, null)
                db.execSQL("DROP TABLE IF EXISTS items")
                db.execSQL("CREATE INDEX IF NOT EXISTS user_state_stamp ON user_state(stamp)")
            }
        }
    }
    fun mark(namespace: String?) {
        val db = writableDatabase; val ns = namespace ?: "*"
        db.execSQL("INSERT OR REPLACE INTO clear_intent VALUES(?)", arrayOf(ns))
    }
    fun pending(): List<String> = readableDatabase.rawQuery("SELECT namespace FROM clear_intent", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun complete(namespace: String?) { writableDatabase.execSQL("DELETE FROM clear_intent WHERE namespace=?", arrayOf(namespace ?: "*")) }
    fun imageKeys(namespace: String?): List<String> = readableDatabase.rawQuery("SELECT key FROM images" + if (namespace == null) "" else " WHERE namespace=?",
        namespace?.let { arrayOf(it) }).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun delete(namespace: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in if (images) listOf("images") else listOf("responses", "members", "user_state"))
                db.delete(table, namespace?.let { "namespace=?" }, namespace?.let { arrayOf(it) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun actualBytes() = directory.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile }.sumOf { it.length() }
    fun indexBytes() = directory.listFiles()?.filter { it.isFile && it.name.startsWith("index.db") }?.sumOf { it.length() }
        ?: error("Cache directory unavailable")
    fun checkpoint() { writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
        check(c.moveToFirst() && c.getInt(0) == 0) { "Cache database is busy" }
    } }
    fun liveBytes(): Long {
        val db=readableDatabase
        fun pragma(name: String)=db.rawQuery("PRAGMA $name",null).use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count")-pragma("freelist_count"))*pragma("page_size")
    }
    fun compact(target: Long, force: Boolean = false) {
        val db = writableDatabase
        checkpoint()
        val pages = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
        val free = db.rawQuery("PRAGMA freelist_count", null).use { it.moveToFirst(); it.getLong(0) }
        if (force || indexBytes() > target || free > pages / 4) {
            val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
            check(indexBytes() + (pages-free)*pageSize <= if(images) 8L*1024*1024 else 64L*1024*1024) { "Cache compaction budget exceeded" }
            db.execSQL("VACUUM"); checkpoint()
        }
        check(indexBytes() <= target) { "Cache physical budget exceeded" }
    }
}
