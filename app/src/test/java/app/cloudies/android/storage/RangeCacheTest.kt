package app.cloudies.android.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class RangeCacheTest {
    private class FakeClient(private val content: ByteArray) : CloudClient {
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun read(id: String, offset: Long, length: Int): ByteArray {
            reads += offset to length
            return content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + length))
        }
        override fun list(parentId: String) = emptyList<CloudFile>()
        override fun stat(id: String) = CloudFile(id, "", "test", size = content.size.toLong())
        override fun upload(parentId: String, name: String, mime: String, length: Long, input: () -> InputStream, progress: (Long) -> Unit): CloudFile = error("unused")
        override fun mkdir(parentId: String, name: String): CloudFile = error("unused")
        override fun rename(file: CloudFile, newName: String): CloudFile = error("unused")
        override fun delete(file: CloudFile) = Unit
    }
    @Test fun `reads span chunk boundaries without downloading other chunks`() {
        val client = FakeClient(ByteArray(100) { it.toByte() })
        val cache = RangeCache(32, 8)
        val bytes = cache.read("a", client.stat("1"), client, 6, 6)
        assertArrayEquals(byteArrayOf(6,7,8,9,10,11), bytes)
        assertEquals(listOf(0L to 8, 8L to 8), client.reads)
        cache.read("a", client.stat("1"), client, 6, 6)
        assertEquals(2, client.reads.size)
    }
    @Test fun `cache is bounded and changed versions cannot reuse old chunks`() {
        val client = FakeClient(ByteArray(100) { it.toByte() })
        val cache = RangeCache(16, 8)
        val file = client.stat("1")
        cache.read("a", file, client, 0, 25)
        assertTrue(cache.size() <= 16)
        cache.read("a", file.copy(etag = "new"), client, 24, 1)
        assertEquals(5, client.reads.size)
    }
    @Test fun `reads at eof are empty and tail requests stop at file size`() {
        val client = FakeClient(ByteArray(10) { it.toByte() })
        val cache = RangeCache(32, 8)
        assertArrayEquals(byteArrayOf(8,9), cache.read("a", client.stat("1"), client, 8, 99))
        assertTrue(cache.read("a", client.stat("1"), client, 10, 3).isEmpty())
        assertEquals(listOf(8L to 2), client.reads)
    }
    @Test fun `unsafe names and cleartext endpoints are rejected`() {
        listOf("../photo", "..", "a\\b", "x\u0000y").forEach { assertTrue(runCatching { safeFileName(it) }.isFailure) }
        listOf("http://example.com/dav", "https://u:p@example.com/dav", "https://example.com/dav?token=secret").forEach { assertTrue(runCatching { WebDavClient.canonicalEndpoint(it) }.isFailure) }
        assertEquals("https://example.com/dav/", WebDavClient.canonicalEndpoint("https://example.com/dav").toString())
    }
}
