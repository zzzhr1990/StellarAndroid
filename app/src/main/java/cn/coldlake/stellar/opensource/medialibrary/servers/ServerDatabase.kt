package cn.coldlake.stellar.opensource.medialibrary.servers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal interface ServerRepository {
    fun list(): List<ServerProfile>
    fun save(profile: ServerProfile)
    fun delete(id: String)
}

internal class ServerDatabase(context: Context, name: String = "remote_servers.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 3), ServerRepository {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE remote_servers (
            id TEXT PRIMARY KEY NOT NULL, protocol TEXT NOT NULL,
            name TEXT NOT NULL, address TEXT NOT NULL, port TEXT NOT NULL,
            share TEXT NOT NULL, path TEXT NOT NULL, username TEXT NOT NULL,
            password TEXT NOT NULL, domain TEXT NOT NULL, nfs_version INTEGER NOT NULL,
            uid TEXT NOT NULL, gid TEXT NOT NULL, account_sync INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            emby_user_id TEXT NOT NULL DEFAULT '', emby_token TEXT NOT NULL DEFAULT '',
            emby_server_id TEXT NOT NULL DEFAULT '', cloud_account_id TEXT NOT NULL DEFAULT '')""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) db.execSQL("ALTER TABLE remote_servers ADD COLUMN cloud_account_id TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 2) for (column in listOf("emby_user_id", "emby_token", "emby_server_id"))
            db.execSQL("ALTER TABLE remote_servers ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
    }

    override fun list(): List<ServerProfile> = readableDatabase.query("remote_servers", null, null, null,
        null, null, "created_at DESC, id").use { cursor ->
        buildList {
            fun text(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
            fun number(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
            while (cursor.moveToNext()) add(ServerProfile(id = text("id"), kind = ServerKind.valueOf(text("protocol")),
                name = text("name"), address = text("address"), port = text("port"), share = text("share"),
                path = text("path"), username = text("username"), password = text("password"), domain = text("domain"),
                nfsVersion = number("nfs_version").toInt(), uid = text("uid"), gid = text("gid"),
                accountSync = number("account_sync") != 0L, createdAt = number("created_at"), updatedAt = number("updated_at"),
                embyUserId = text("emby_user_id"), embyToken = text("emby_token"), embyServerId = text("emby_server_id"), cloudAccountId = text("cloud_account_id")))
        }
    }
    override fun save(profile: ServerProfile) {
        require(profile.kind.supported && profile.normalized() != null)
        require(!profile.kind.mediaServer ||
            profile.embyToken.isNotBlank() && profile.embyUserId.isNotBlank() && profile.embyServerId.isNotBlank())
        val values = ContentValues().apply {
            put("cloud_account_id", profile.cloudAccountId)
            put("id", profile.id); put("protocol", profile.kind.name); put("name", profile.name)
            put("address", profile.address); put("port", profile.port); put("share", profile.share); put("path", profile.path)
            put("username", profile.username); put("password", profile.password); put("domain", profile.domain)
            put("nfs_version", profile.nfsVersion); put("uid", profile.uid); put("gid", profile.gid)
            put("account_sync", if (profile.accountSync) 1 else 0)
            put("created_at", profile.createdAt); put("updated_at", profile.updatedAt)
            put("emby_user_id", profile.embyUserId); put("emby_token", profile.embyToken); put("emby_server_id", profile.embyServerId)
        }
        writableDatabase.insertWithOnConflict("remote_servers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) }
    }

    override fun delete(id: String) {
        writableDatabase.delete("remote_servers", "id = ?", arrayOf(id))
    }
}
