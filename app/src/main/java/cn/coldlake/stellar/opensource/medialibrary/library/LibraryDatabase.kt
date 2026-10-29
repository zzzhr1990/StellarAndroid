package cn.coldlake.stellar.opensource.medialibrary.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.util.UUID

internal class LibraryDatabase(private val context: Context, name: String = "media_library.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1), LibraryRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE library_paths (
            id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, source_id TEXT NOT NULL,
            source_scope TEXT NOT NULL, path TEXT NOT NULL, path_key TEXT NOT NULL,
            display_path TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            deleted_at INTEGER, revision INTEGER NOT NULL, sync_dirty INTEGER NOT NULL DEFAULT 1,
            UNIQUE(type, source_id, source_scope, path_key))""")
        db.execSQL("CREATE INDEX library_paths_active ON library_paths(deleted_at, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Migration required")
    override fun list() = read(writableDatabase, activeOnly = true)
    private fun read(db: SQLiteDatabase, activeOnly: Boolean): List<LibraryPath> = db.query("library_paths", null,
        if (activeOnly) "deleted_at IS NULL" else null, null, null, null, "created_at ASC, id").use { c ->
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        buildList {
            while (c.moveToNext()) add(LibraryPath(id = text("id"), type = LibraryPathType.valueOf(text("type")),
                sourceId = text("source_id"), scope = text("source_scope"), path = text("path"), pathKey = text("path_key"),
                displayPath = text("display_path"), createdAt = number("created_at"), updatedAt = number("updated_at"),
                deletedAt = if (c.isNull(c.getColumnIndexOrThrow("deleted_at"))) null else number("deleted_at"), revision = number("revision")))
        }
    }
    override fun add(candidate: LibraryPath): LibraryAddResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val all = read(db, false)
            val covered = all.firstOrNull { it.deletedAt == null && covers(it, candidate) }
            if (covered != null) return LibraryAddResult.Covered(covered, covered.sameLocation(candidate))
            val old = all.firstOrNull { it.sameLocation(candidate) }
            val now = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1)
            val saved = candidate.copy(id = old?.id ?: candidate.id, createdAt = old?.createdAt ?: now,
                updatedAt = now, deletedAt = null, revision = (old?.revision ?: 0) + 1)
            val values = ContentValues().apply {
                put("id", saved.id); put("type", saved.type.name); put("source_id", saved.sourceId)
                put("source_scope", saved.scope); put("path", saved.path); put("path_key", saved.pathKey)
                put("display_path", saved.displayPath); put("created_at", saved.createdAt); put("updated_at", saved.updatedAt)
                putNull("deleted_at"); put("revision", saved.revision); put("sync_dirty", 1)
            }
            check(db.insertWithOnConflict("library_paths", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            db.setTransactionSuccessful()
            return LibraryAddResult.Added(saved)
        } finally { db.endTransaction() }
    }
    private fun covers(parent: LibraryPath, child: LibraryPath): Boolean {
        if (parent.covers(child)) return true
        if (parent.type != LibraryPathType.LOCAL || child.type != LibraryPathType.LOCAL ||
            parent.sourceId != child.sourceId || parent.scope != child.scope) return false
        if (parent.scope.startsWith("com.android.externalstorage.documents|")) return false
        // Other document providers have opaque IDs. Ask the provider; never infer hierarchy from their text.
        val parentTree = Uri.parse(parent.path)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(parentTree, DocumentsContract.getTreeDocumentId(parentTree))
        val childDoc = DocumentsContract.buildDocumentUriUsingTree(parentTree, DocumentsContract.getTreeDocumentId(Uri.parse(child.path)))
        // External storage above works on every supported Android version. Opaque providers
        // require the public hierarchy API; fail closed when it is unavailable.
        if (Build.VERSION.SDK_INT < 29) error("Provider hierarchy unavailable")
        return DocumentsContract.isChildDocument(context.contentResolver, parentDoc, childDoc)
    }
    override fun delete(id: String) {
        // Tombstones allow a later sync implementation to propagate removals without reusing IDs.
        writableDatabase.execSQL("""UPDATE library_paths SET deleted_at = MAX(?, updated_at + 1),
            updated_at = MAX(?, updated_at + 1), revision = revision + 1, sync_dirty = 1
            WHERE id = ? AND deleted_at IS NULL""", arrayOf<Any>(System.currentTimeMillis(), System.currentTimeMillis(), id))
    }
}

internal fun localLibraryPath(context: Context, tree: Uri): LibraryPath {
    require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
    val authority = requireNotNull(tree.authority)
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    val external = authority == "com.android.externalstorage.documents" && ':' in documentId
    val scope = if (external) "$authority|${documentId.substringBefore(':')}" else authority
    val components = if (external) documentId.substringAfter(':').trim('/').split('/').filter(String::isNotEmpty)
        else listOf(documentId)
    val preferences = context.getSharedPreferences("library_identity_v1", Context.MODE_PRIVATE)
    val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("device_id", it).commit())
    }
    return LibraryPath(type = LibraryPathType.LOCAL, sourceId = deviceId, scope = scope, path = tree.toString(),
        pathKey = libraryPathKey(components), displayPath = if (external) documentId else tree.toString())
}
