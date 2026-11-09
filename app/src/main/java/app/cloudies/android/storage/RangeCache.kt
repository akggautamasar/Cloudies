package app.cloudies.android.storage

import java.io.IOException
import kotlin.math.min

/** Globally bounded RAM cache; streamed content is never written to disk. */
class RangeCache(private val budget: Int = 16 * 1024 * 1024, private val chunkSize: Int = 256 * 1024) {
    private data class CacheKey(val source: String, val etag: String, val size: Long, val modified: Long, val block: Long)
    private val cache = LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)
    private var used = 0
    @Synchronized fun clear() { cache.clear(); used = 0 }
    @Synchronized fun size() = used
    fun read(key: String, file: CloudFile, client: CloudClient, offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0)
        if (count == 0 || offset >= file.size) return byteArrayOf()
        val wanted = min(count.toLong(), file.size - offset).toInt()
        val output = ByteArray(wanted)
        var copied = 0
        while (copied < wanted) {
            val position = offset + copied
            val block = position / chunkSize
            val blockKey = CacheKey(key, file.etag, file.size, file.modified, block)
            val bytes = synchronized(this) { cache[blockKey] } ?: client.read(file.id, block * chunkSize, min(chunkSize.toLong(), file.size - block * chunkSize).toInt()).also { fetched ->
                if (fetched.isEmpty()) throw IOException("The remote file ended unexpectedly. Refresh and try again.")
                synchronized(this) {
                    cache.put(blockKey, fetched)?.let { used -= it.size }
                    used += fetched.size
                    while (used > budget && cache.isNotEmpty()) {
                        val entry = cache.entries.iterator()
                        used -= entry.next().value.size
                        entry.remove()
                    }
                }
            }
            val within = (position % chunkSize).toInt()
            val take = min(wanted - copied, bytes.size - within)
            if (take <= 0) throw IOException("Incomplete cloud read. Check your connection.")
            bytes.copyInto(output, copied, within, within + take)
            copied += take
        }
        return output
    }
}
