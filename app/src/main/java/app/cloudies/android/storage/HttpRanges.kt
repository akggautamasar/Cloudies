package app.cloudies.android.storage

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Never silently download a complete file when a server ignores a seek. */
object HttpRanges {
    fun read(client: OkHttpClient, request: Request.Builder, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length in 1..(4 * 1024 * 1024))
        val end = Math.addExact(offset, length.toLong() - 1)
        client.newCall(request.header("Range", "bytes=$offset-$end")
            .header("Accept-Encoding", "identity").get().build()).execute().use { response ->
            if (response.code == 416) return byteArrayOf()
            if (!response.isSuccessful) throw IOException("Cloud read failed (HTTP ${response.code}). Reconnect the account if necessary.")
            if (response.code == 206) {
                val match = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)")
                    .matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("The server returned an invalid byte range.")
                if (match.groupValues[1].toLong() != offset || match.groupValues[2].toLong() < offset)
                    throw IOException("The server returned the wrong part of the file.")
            } else if (offset != 0L) {
                throw IOException("This server does not support seeking. Save the file offline to open it.")
            }
            val input = response.body?.byteStream() ?: throw IOException("Empty cloud response.")
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bytes, read, length - read)
                if (count < 0) break
                read += count
            }
            return bytes.copyOf(read)
        }
    }
}
