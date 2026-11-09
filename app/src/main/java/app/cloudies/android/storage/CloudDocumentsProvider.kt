package app.cloudies.android.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import app.cloudies.android.R
import app.cloudies.android.cloudies
import java.io.FileNotFoundException

/** Cloudies appears in Android's Open/Save picker without broad storage access. */
class CloudDocumentsProvider : DocumentsProvider() {
    private val app get() = requireNotNull(context).cloudies
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        app.connections.all().forEach { connection ->
            val flags = Root.FLAG_SUPPORTS_IS_CHILD
            cursor.newRow().add(Root.COLUMN_ROOT_ID, connection.id)
                .add(Root.COLUMN_DOCUMENT_ID, documentId(connection.id, connection.rootId))
                .add(Root.COLUMN_TITLE, "Cloudies · ${connection.name}")
                .add(Root.COLUMN_SUMMARY, "Files on demand")
                .add(Root.COLUMN_FLAGS, flags)
                .add(Root.COLUMN_ICON, R.drawable.ic_cloudies)
                .add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val (account, remote) = split(documentId)
        val connection = app.connection(account)
        val file = if (remote == connection.rootId) CloudFile(remote, remote, connection.name, directory = true)
            else app.catalog.get(account, remote) ?: app.client(connection).stat(remote).also { app.catalog.put(account, it) }
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { row(this, account, file) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val (account, remote) = split(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        try {
            app.list(app.connection(account), remote).forEach { row(cursor, account, it) }
        } catch (error: Exception) {
            app.catalog.children(account, remote).forEach { row(cursor, account, it) }
            cursor.setExtras(android.os.Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, error.message ?: "Cloud connection unavailable.") })
        }
        return cursor
    }
    private fun row(cursor: MatrixCursor, account: String, file: CloudFile) {
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, documentId(account, file.id))
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.directory) Document.MIME_TYPE_DIR else file.mime)
            .add(Document.COLUMN_SIZE, if (file.directory) null else file.size)
            .add(Document.COLUMN_LAST_MODIFIED, file.modified)
            .add(Document.COLUMN_FLAGS, if (file.directory) Document.FLAG_DIR_PREFERS_LAST_MODIFIED else 0)
    }
    override fun getDocumentType(documentId: String): String {
        val (account, remote) = split(documentId)
        if (remote == app.connection(account).rootId) return Document.MIME_TYPE_DIR
        return app.catalog.get(account, remote)?.let { if (it.directory) Document.MIME_TYPE_DIR else it.mime } ?: "application/octet-stream"
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (account, parent) = split(parentDocumentId)
        val (childAccount, child) = split(documentId)
        if (account != childAccount || parent == child) return false
        var current = app.catalog.get(account, child)
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            if (current.parentId == parent) return true
            current = app.catalog.get(account, current.parentId)
        }
        return false
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Cloudies currently provides read access. Upload changed files through the app.")
        val (account, remote) = split(documentId)
        val connection = app.connection(account)
        val offline = app.catalog.offlineFile(account, remote)
        if (offline.isFile) return ParcelFileDescriptor.open(offline, ParcelFileDescriptor.MODE_READ_ONLY)
        val client = app.client(connection)
        // Refresh metadata for every new handle; old cached chunks cannot identify a new version.
        val file = client.stat(remote).also { app.catalog.put(account, it) }
        val cacheIdentity = "$account:$remote" + if (file.etag.isBlank() && file.modified == 0L) ":${java.util.UUID.randomUUID()}" else ""
        if (file.directory) throw FileNotFoundException("Cannot open a folder as a file.")
        val thread = HandlerThread("cloudies-range").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = file.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (signal?.isCanceled == true) throw ErrnoException("read", OsConstants.ECANCELED)
                return try {
                    val bytes = app.ranges.read(cacheIdentity, file, client, offset, size)
                    bytes.copyInto(data)
                    bytes.size
                } catch (_: Exception) { throw ErrnoException("read", OsConstants.EIO) }
            }
            override fun onRelease() { thread.quitSafely() }
        }
        return try {
            requireNotNull(context).getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        } catch (error: Exception) { thread.quitSafely(); throw FileNotFoundException(error.message) }
    }
    companion object {
        const val AUTHORITY = "app.cloudies.android.documents"
        val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES)
        val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        fun documentId(account: String, remote: String) = account + ":" + Base64.encodeToString(remote.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun split(id: String): Pair<String, String> {
            val separator = id.indexOf(':')
            require(separator > 0) { "Invalid Cloudies document." }
            return id.substring(0, separator) to String(Base64.decode(id.substring(separator + 1), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }
    }
}
