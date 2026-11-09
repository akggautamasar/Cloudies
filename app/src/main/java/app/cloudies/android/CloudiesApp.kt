package app.cloudies.android

import android.app.Application
import android.content.Context
import app.cloudies.android.storage.*

class CloudiesApp : Application() {
    val connections by lazy { ConnectionStore(this) }
    val catalog by lazy { Catalog(this) }
    val ranges = RangeCache()
    fun connection(id: String) = connections.all().firstOrNull { it.id == id }
        ?: throw IllegalStateException("This account was disconnected. Connect it again in Cloudies.")
    fun client(connection: Connection): CloudClient = if (connection.kind == "drive") DriveClient(this, connection) else WebDavClient(connection)
    fun root(connection: Connection) = connection.rootId
    fun list(connection: Connection, parent: String): List<CloudFile> {
        val files = client(connection).list(parent)
            .sortedWith(compareByDescending<CloudFile> { it.directory }.thenBy { it.name.lowercase() })
        catalog.replaceChildren(connection.id, parent, files)
        return files
    }
}

val Context.cloudies: CloudiesApp get() = applicationContext as CloudiesApp
