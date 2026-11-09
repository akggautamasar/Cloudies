package app.cloudies.android.storage

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HttpRangesTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    @Test fun `seeks request only the requested bytes`() {
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 5-8/100").setBody("test"))
        val bytes = HttpRanges.read(client, Request.Builder().url(server.url("/film.mp4")), 5, 4)
        assertEquals("test", String(bytes))
        val request = server.takeRequest()
        assertEquals("bytes=5-8", request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
    }
    @Test(expected = IOException::class) fun `ignored range cannot turn a seek into a full download`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("the complete file"))
        HttpRanges.read(client, Request.Builder().url(server.url("/film")), 8, 4)
    }
    @Test(expected = IOException::class) fun `mismatched response offset is rejected`() {
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/100").setBody("test"))
        HttpRanges.read(client, Request.Builder().url(server.url("/film")), 5, 4)
    }
    @Test fun `initial response is bounded even if server ignores range`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("abcdefghijk"))
        assertEquals("abcd", String(HttpRanges.read(client, Request.Builder().url(server.url("/film")), 0, 4)))
    }
    @Test fun `end of file returns an empty read`() {
        server.enqueue(MockResponse().setResponseCode(416))
        assertTrue(HttpRanges.read(client, Request.Builder().url(server.url("/film")), 100, 4).isEmpty())
    }
}
