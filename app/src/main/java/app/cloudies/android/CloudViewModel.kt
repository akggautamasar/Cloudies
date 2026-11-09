package app.cloudies.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import app.cloudies.android.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class BrowserState(
    val connections: List<Connection> = emptyList(), val active: Connection? = null,
    val path: List<CloudFile> = emptyList(), val files: List<CloudFile> = emptyList(),
    val busy: Boolean = false, val message: String? = null,
    val offline: List<Pair<String, CloudFile>> = emptyList(), val showOffline: Boolean = false
)

class CloudViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CloudiesApp
    private val mutable = MutableStateFlow(BrowserState())
    val state = mutable.asStateFlow()
    val transfers = WorkManager.getInstance(app).getWorkInfosByTagFlow("cloudies-transfer")
    init { reloadAccounts() }
    private fun reloadAccounts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val accounts = app.connections.all()
                mutable.value = mutable.value.copy(connections = accounts, offline = app.catalog.offline())
                if (mutable.value.active == null && accounts.isNotEmpty()) select(accounts.first())
            } catch (_: Exception) { message("The saved account key is unavailable. Clear Cloudies app data to set up accounts again; cloud files are unaffected.") }
        }
    }
    fun select(connection: Connection) {
        mutable.value = mutable.value.copy(active = connection, path = emptyList(), files = emptyList(), showOffline = false)
        refresh()
    }
    fun enter(file: CloudFile) {
        if (!file.directory) return
        mutable.value = mutable.value.copy(path = mutable.value.path + file, files = emptyList(), showOffline = false)
        refresh()
    }
    fun up() {
        if (mutable.value.path.isEmpty()) return
        mutable.value = mutable.value.copy(path = mutable.value.path.dropLast(1), files = emptyList())
        refresh()
    }
    fun root() { mutable.value = mutable.value.copy(path = emptyList(), files = emptyList(), showOffline = false); refresh() }
    fun parentId() = mutable.value.path.lastOrNull()?.id ?: mutable.value.active?.rootId.orEmpty()
    fun refresh() {
        val account = mutable.value.active ?: return
        val parent = parentId()
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true)
            try {
                val files = withContext(Dispatchers.IO) { app.list(account, parent) }
                if (mutable.value.active?.id == account.id && parentId() == parent)
                    mutable.value = mutable.value.copy(files = files, busy = false, offline = withContext(Dispatchers.IO) { app.catalog.offline() })
            } catch (error: Exception) {
                if (mutable.value.active?.id == account.id && parentId() == parent) {
                    val cached = withContext(Dispatchers.IO) { app.catalog.children(account.id, parent) }
                    mutable.value = mutable.value.copy(files = cached, busy = false, message = (error.message ?: "Could not load files.") + if (cached.isNotEmpty()) " Showing saved file names." else "")
                }
            }
        }
    }
    fun connectWebDav(name: String, endpoint: String, username: String, password: String, done: () -> Unit) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true)
            try {
                val connection = Connection(UUID.randomUUID().toString(), name.ifBlank { "My cloud" }, "dav", endpoint.trim(), username.trim(), password)
                withContext(Dispatchers.IO) {
                    WebDavClient.canonicalEndpoint(endpoint)
                    val files = app.client(connection).list("")
                    app.connections.put(connection)
                    app.catalog.replaceChildren(connection.id, "", files)
                }
                mutable.value = mutable.value.copy(connections = withContext(Dispatchers.IO) { app.connections.all() }, busy = false)
                select(connection)
                notifyRoots()
                done()
                message("${connection.name} connected. Your files are ready.")
            } catch (error: Exception) { mutable.value = mutable.value.copy(busy = false, message = error.message ?: "Could not connect.") }
        }
    }
    fun connectDrive(done: () -> Unit) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true)
            try {
                val connection = withContext(Dispatchers.IO) {
                    val root = DriveClient.ensureRoot(app)
                    val existing = app.connections.all().firstOrNull { it.kind == "drive" }
                    Connection(existing?.id ?: UUID.randomUUID().toString(), "Google Drive", "drive", rootId = root).also { app.connections.put(it) }
                }
                mutable.value = mutable.value.copy(connections = withContext(Dispatchers.IO) { app.connections.all() }, busy = false)
                select(connection); notifyRoots(); done(); message("Google Drive connected. Cloudies uses its own folder.")
            } catch (error: Exception) { mutable.value = mutable.value.copy(busy = false); message(error.message ?: "Google Drive setup did not finish.") }
        }
    }
    fun disconnect(connection: Connection) = launchAction {
        WorkManager.getInstance(app).cancelAllWorkByTag("account:${connection.id}").result.get()
        app.catalog.forget(connection.id)
        app.connections.remove(connection.id)
        app.ranges.clear()
        val accounts = app.connections.all()
        mutable.value = BrowserState(connections = accounts, offline = app.catalog.offline(), message = "Account disconnected. Files in the cloud are unchanged.")
        if (accounts.isNotEmpty()) select(accounts.first())
        notifyRoots()
    }
    fun folder(name: String) {
        val connection = mutable.value.active ?: return
        val parent = parentId()
        launchAction { app.client(connection).mkdir(parent, safeFileName(name)); refresh() }
    }
    fun rename(file: CloudFile, name: String) {
        val connection = mutable.value.active ?: return
        launchAction {
            val updated = app.client(connection).rename(file, safeFileName(name))
            app.catalog.put(connection.id, updated)
            if (file.id != updated.id) {
                val oldOffline = app.catalog.offlineFile(connection.id, file.id)
                if (oldOffline.isFile && oldOffline.renameTo(app.catalog.offlineFile(connection.id, updated.id))) app.catalog.markOffline(connection.id, updated.id)
                if (file.directory) app.catalog.removeMetadata(connection.id, file.id)
                else app.catalog.remove(connection.id, file.id)
            }
            app.ranges.clear(); refresh()
        }
    }
    fun delete(file: CloudFile) {
        val connection = mutable.value.active ?: return
        launchAction { app.client(connection).delete(file); app.catalog.remove(connection.id, file.id); app.ranges.clear(); refresh() }
    }
    fun showOffline() {
        viewModelScope.launch(Dispatchers.IO) { mutable.value = mutable.value.copy(offline = app.catalog.offline(), showOffline = true) }
    }
    fun removeOffline(account: String, file: CloudFile) = launchAction { app.catalog.removeOffline(account, file.id); showOffline() }
    fun clearBuffer() { app.ranges.clear(); message("Streaming buffer cleared. Offline files are kept.") }
    fun upload(uris: List<Uri>) {
        val account = mutable.value.active ?: run { message("Connect a cloud account first."); return }
        val parent = parentId()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uris.forEach { uri ->
                    runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "File"
                    var size = -1L
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if(cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if(n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                            if(s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                        }
                    }
                    if(size < 0) throw IllegalArgumentException("Cannot determine this file's size. Save it to Downloads and select it again.")
                    val data = workDataOf("account" to account.id, "parent" to parent, "direction" to "upload", "name" to safeFileName(name), "size" to size,
                        "uri" to uri.toString(), "mime" to (app.contentResolver.getType(uri) ?: "application/octet-stream"))
                    enqueue(data, "upload:${UUID.randomUUID()}")
                }
                message("${uris.size} upload${if (uris.size == 1) "" else "s"} added to Transfers. Original files stay on your phone.")
            } catch(error: Exception) { message(error.message ?: "Could not queue the selected files.") }
        }
    }
    fun keepOffline(account: String, file: CloudFile) {
        enqueue(workDataOf("account" to account, "direction" to "download", "remote" to file.id, "name" to file.name), "offline:$account:${file.id}")
        message("Saving ${file.name} offline. View progress in Transfers.")
    }
    private fun enqueue(data: Data, key: String) {
        val preferences = app.getSharedPreferences("settings", 0)
        val constraints = Constraints.Builder().setRequiredNetworkType(if (preferences.getBoolean("wifi", true)) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(data).setConstraints(constraints)
            .addTag("cloudies-transfer").addTag("account:${data.getString("account")}").addTag("name:${data.getString("name") ?: "File"}").build()
        WorkManager.getInstance(app).enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request)
    }
    fun retry(work: WorkInfo) {
        if (work.outputData.getString("account") == null) { message("Select the file again to restart this transfer."); return }
        enqueue(work.outputData, "retry:${work.id}")
    }
    fun cancel(work: WorkInfo) { WorkManager.getInstance(app).cancelWorkById(work.id) }
    fun clearFinished() { WorkManager.getInstance(app).pruneWork() }
    fun message(text: String?) { mutable.value = mutable.value.copy(message = text) }
    private fun notifyRoots() { app.contentResolver.notifyChange(DocumentsContract.buildRootsUri(CloudDocumentsProvider.AUTHORITY), null) }
    private fun launchAction(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch (error: Exception) { message(error.message ?: "The action could not be completed.") } } }
}
