package app.cloudies.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest

class Catalog(private val context: Context) : SQLiteOpenHelper(context, "cloudies.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (connection TEXT NOT NULL,id TEXT NOT NULL,parent TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,directory INTEGER NOT NULL,modified INTEGER NOT NULL,etag TEXT NOT NULL,PRIMARY KEY(connection,id))")
        db.execSQL("CREATE INDEX parent_index ON entries(connection,parent)")
        db.execSQL("CREATE TABLE offline (connection TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(connection,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun put(connection: String, item: CloudFile) {
        writableDatabase.insertWithOnConflict("entries", null, ContentValues().apply {
            put("connection", connection); put("id", item.id); put("parent", item.parentId); put("name", item.name); put("mime", item.mime)
            put("size", item.size); put("directory", if (item.directory) 1 else 0); put("modified", item.modified); put("etag", item.etag)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun replaceChildren(connection: String, parent: String, children: List<CloudFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", "connection=? AND parent=? AND id<>? AND id NOT IN (SELECT id FROM offline WHERE connection=?)", arrayOf(connection, parent, parent, connection))
            children.forEach { put(connection, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun get(connection: String, id: String): CloudFile? = readableDatabase.query("entries", null, "connection=? AND id=?", arrayOf(connection, id), null, null, null).use { if (it.moveToFirst()) item(it) else null }
    fun children(connection: String, parent: String): List<CloudFile> = readableDatabase.query("entries", null, "connection=? AND parent=? AND id<>?", arrayOf(connection, parent, parent), null, null, "directory DESC,name COLLATE NOCASE ASC").use { cursor -> buildList { while (cursor.moveToNext()) add(item(cursor)) } }
    fun offline(): List<Pair<String, CloudFile>> = readableDatabase.rawQuery("SELECT e.* FROM entries e JOIN offline o ON o.connection=e.connection AND o.id=e.id", null).use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("connection")) to item(cursor)) } }.filter { offlineFile(it.first, it.second.id).isFile }
    fun markOffline(connection: String, id: String) { writableDatabase.insertWithOnConflict("offline", null, ContentValues().apply { put("connection", connection); put("id", id) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun offlineFile(connection: String, id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("$connection\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "offline/$hash")
    }
    fun removeOffline(connection: String, id: String) { offlineFile(connection, id).delete(); writableDatabase.delete("offline", "connection=? AND id=?", arrayOf(connection, id)) }
    fun remove(connection: String, id: String) {
        children(connection, id).filter { it.id != id }.forEach { remove(connection, it.id) }
        removeOffline(connection, id)
        val file = offlineFile(connection, id)
        File(file.path + ".part").delete(); File(file.path + ".version").delete()
        writableDatabase.delete("entries", "connection=? AND id=?", arrayOf(connection, id))
    }
    fun removeMetadata(connection: String, id: String) {
        writableDatabase.delete("entries", "connection=? AND id=?", arrayOf(connection, id))
    }
    fun forget(connection: String) {
        readableDatabase.query("entries", arrayOf("id"), "connection=?", arrayOf(connection), null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val file = offlineFile(connection, cursor.getString(0))
                file.delete(); File(file.path + ".part").delete(); File(file.path + ".version").delete()
            }
        }
        writableDatabase.delete("entries", "connection=?", arrayOf(connection))
        writableDatabase.delete("offline", "connection=?", arrayOf(connection))
    }
    private fun item(cursor: Cursor) = CloudFile(cursor.getString(cursor.getColumnIndexOrThrow("id")), cursor.getString(cursor.getColumnIndexOrThrow("parent")), cursor.getString(cursor.getColumnIndexOrThrow("name")), cursor.getString(cursor.getColumnIndexOrThrow("mime")), cursor.getLong(cursor.getColumnIndexOrThrow("size")), cursor.getInt(cursor.getColumnIndexOrThrow("directory")) == 1, cursor.getLong(cursor.getColumnIndexOrThrow("modified")), cursor.getString(cursor.getColumnIndexOrThrow("etag")))
}
