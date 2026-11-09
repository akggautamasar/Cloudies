package app.cloudies.android.storage

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).followRedirects(false).build()
        client = WebDavClient(Connection("a", "Test", "dav", server.url("/dav/").toString(), "user", "app-password"), http)
    }
    @After fun teardown() { server.shutdown() }
    @Test fun `folder listing decodes names and rejects external and failed entries`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/photo%20one.jpg</d:href><d:propstat><d:prop><d:displayname>Photo one.jpg</d:displayname><d:getcontenttype>image/jpeg</d:getcontenttype><d:getcontentlength>123</d:getcontentlength><d:getetag>v1</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>https://other.example/file</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/missing</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()))
        val files = client.list("")
        assertEquals(1, files.size)
        assertEquals("photo one.jpg", files.single().id)
        assertEquals(123L, files.single().size)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertNotNull(request.getHeader("Authorization"))
    }
    @Test fun `an existing destination cannot be overwritten by an upload`() {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(204))
        try {
            client.upload("", "existing.txt", "text/plain", 3, { "new".byteInputStream() }) {}
            fail("Expected the no-overwrite move to fail")
        } catch (_: IOException) { }
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertTrue(put.path!!.startsWith("/dav/.cloudies-upload-"))
        assertEquals("new", put.body.readUtf8())
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals(server.url("/dav/existing.txt").toString(), move.getHeader("Destination"))
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertEquals(put.path, cleanup.path)
    }
}
