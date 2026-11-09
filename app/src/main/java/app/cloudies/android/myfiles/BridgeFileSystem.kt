package app.cloudies.android.myfiles

import app.cloudies.android.storage.CloudClient
import app.cloudies.android.storage.CloudFile
import app.cloudies.android.storage.Connection
import app.cloudies.android.storage.RangeCache
import java.io.IOException

data class BridgeEntry(val name: String, val directory: Boolean, val size: Long = 0, val modified: Long = 0)

interface BridgeReader {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

/** Intentionally has no mutation methods: the My Files connection is read-only. */
interface BridgeFileSystem {
    fun stat(path: String): BridgeEntry
    fun list(path: String): List<BridgeEntry>
    fun open(path: String): BridgeReader
}

object BridgePaths {
    fun normalize(current: String, argument: String): String {
        require(argument.length <= 2048 && argument.none { it.code < 32 || it == '\\' || it.code == 127 }) {
            "Invalid path."
        }
        val parts = if (argument.startsWith('/')) mutableListOf() else current.split('/').filter { it.isNotEmpty() }.toMutableList()
        argument.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun visibleName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    // A stable account suffix avoids collisions when two connected providers have the same label.
    fun accountName(connection: Connection): String {
        val label = connection.name.map { if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it }
            .joinToString("").trim().take(100).ifBlank { "Cloud" }
        val suffix = java.security.MessageDigest.getInstance("SHA-256").digest(connection.id.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        return "$label ($suffix)"
    }
}

/** Resolves names inside an account root; remote IDs never come from the FTP client. */
class CloudBridgeFileSystem(
    private val accounts: () -> List<Connection>,
    private val client: (Connection) -> CloudClient,
    private val cache: RangeCache
) : BridgeFileSystem {
    private data class Node(val account: Connection, val client: CloudClient, val file: CloudFile)

    private fun resolve(path: String): Node {
        val parts = BridgePaths.normalize("/", path).split('/').filter { it.isNotEmpty() }
        val account = accounts().singleOrNull { BridgePaths.accountName(it) == parts.firstOrNull() }
            ?: throw IOException("Account unavailable.")
        val remote = client(account)
        var file = CloudFile(account.rootId, account.rootId, BridgePaths.accountName(account), directory = true)
        for (part in parts.drop(1)) {
            if (!file.directory) throw IOException("Not a folder.")
            val matches = remote.list(file.id).filter { it.name == part && BridgePaths.visibleName(it.name) }
            // Drive permits duplicate names. Do not choose the wrong document silently.
            file = matches.singleOrNull() ?: throw IOException("File missing or name ambiguous. Rename it in Cloudies.")
        }
        return Node(account, remote, file)
    }

    override fun stat(path: String): BridgeEntry {
        if (path == "/") return BridgeEntry("Cloudies", true)
        val node = resolve(path)
        return entry(node.file)
    }

    override fun list(path: String): List<BridgeEntry> {
        if (path == "/") return accounts().map { BridgeEntry(BridgePaths.accountName(it), true) }
        val node = resolve(path)
        if (!node.file.directory) throw IOException("Not a folder.")
        return node.client.list(node.file.id).filter { BridgePaths.visibleName(it.name) }.map(::entry)
    }

    override fun open(path: String): BridgeReader {
        val node = resolve(path)
        if (node.file.directory) throw IOException("Not a file.")
        // Refresh the version before a new transfer, and prime WebDAV's If-Match check.
        val file = node.client.stat(node.file.id)
        val identity = "${node.account.id}:${file.id}" +
            if (file.etag.isBlank() && file.modified == 0L) ":${java.util.UUID.randomUUID()}" else ""
        return object : BridgeReader {
            override val size = file.size
            override fun read(offset: Long, length: Int): ByteArray {
                if (accounts().none { it.id == node.account.id }) throw IOException("Account disconnected.")
                return cache.read(identity, file, node.client, offset, length)
            }
        }
    }

    private fun entry(file: CloudFile) = BridgeEntry(file.name, file.directory, file.size, file.modified)
}
