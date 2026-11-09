package app.cloudies.android.myfiles

import app.cloudies.android.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class CloudBridgeFileSystemTest {
    private class Cloud : CloudClient {
        val bytes = ByteArray(100) { it.toByte() }
        var duplicate = false
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun list(parentId: String): List<CloudFile> = if (parentId == "drive-root-id") buildList {
            add(CloudFile("opaque-id", parentId, "notes.txt", size = bytes.size.toLong(), etag = "v1"))
            add(CloudFile("bad-id", parentId, "bad\nname"))
            if (duplicate) add(CloudFile("different-id", parentId, "notes.txt", size = bytes.size.toLong()))
        } else emptyList()
        override fun stat(id: String) = CloudFile(id, "drive-root-id", "notes.txt", size = bytes.size.toLong(), etag = "v1")
        override fun read(id: String, offset: Long, length: Int): ByteArray {
            assertEquals("opaque-id", id)
            reads.add(offset to length)
            return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
        override fun upload(parentId: String, name: String, mime: String, length: Long, input: () -> InputStream, progress: (Long) -> Unit): CloudFile = error("No write access")
        override fun mkdir(parentId: String, name: String): CloudFile = error("No write access")
        override fun rename(file: CloudFile, newName: String): CloudFile = error("No write access")
        override fun delete(file: CloudFile) { error("No write access") }
    }

    private val account = Connection("account-one", "My cloud", "drive", rootId = "drive-root-id")

    @Test fun `accounts with equal labels have stable distinct virtual folders`() {
        val other = account.copy(id = "account-two")
        val files = CloudBridgeFileSystem({ listOf(account, other) }, { Cloud() }, RangeCache())
        val roots = files.list("/")
        assertEquals(2, roots.map { it.name }.distinct().size)
        assertEquals(BridgePaths.accountName(account), roots.first().name)
        assertTrue(roots.all { it.directory && BridgePaths.visibleName(it.name) })
        assertTrue(BridgePaths.visibleName(BridgePaths.accountName(account.copy(name = "../../x\n"))))
    }

    @Test fun `names resolve to opaque IDs and reads remain bounded and cached`() {
        val cloud = Cloud()
        val files = CloudBridgeFileSystem({ listOf(account) }, { cloud }, RangeCache(32, 8))
        val root = "/${BridgePaths.accountName(account)}"
        assertEquals(listOf("notes.txt"), files.list(root).map { it.name })
        val reader = files.open("$root/notes.txt")
        assertTrue(cloud.reads.isEmpty())
        assertArrayEquals(byteArrayOf(6, 7, 8, 9, 10), reader.read(6, 5))
        assertEquals(listOf(0L to 8, 8L to 8), cloud.reads)
        reader.read(6, 5)
        assertEquals(2, cloud.reads.size)
        assertTrue(runCatching { files.open("$root/opaque-id") }.isFailure)
    }

    @Test fun `ambiguous cloud names and missing accounts are rejected`() {
        val cloud = Cloud().apply { duplicate = true }
        val files = CloudBridgeFileSystem({ listOf(account) }, { cloud }, RangeCache())
        assertTrue(runCatching { files.open("/${BridgePaths.accountName(account)}/notes.txt") }.isFailure)
        assertTrue(runCatching { files.list("/another account") }.isFailure)
    }

    @Test fun `disconnect revokes an already opened reader even when its bytes are cached`() {
        var accounts = listOf(account)
        val files = CloudBridgeFileSystem({ accounts }, { Cloud() }, RangeCache())
        val reader = files.open("/${BridgePaths.accountName(account)}/notes.txt")
        reader.read(0, 5)
        accounts = emptyList()
        assertTrue(runCatching { reader.read(0, 5) }.isFailure)
        assertTrue(files.list("/").isEmpty())
    }
}
