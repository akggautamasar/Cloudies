package app.cloudies.android.storage

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebDavClient(private val connection: Connection, private val http: OkHttpClient = defaultHttp()) : CloudClient {
    private val root = canonicalEndpoint(connection.endpoint)
    private val authorization = Credentials.basic(connection.username, connection.password, Charsets.UTF_8)
    private val versions = java.util.concurrent.ConcurrentHashMap<String, CloudFile>()
    private fun url(id: String): HttpUrl {
        val builder = root.newBuilder()
        id.trim('/').takeIf { it.isNotEmpty() }?.split('/')?.forEach { builder.addPathSegment(it) }
        return builder.build()
    }
    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization)
    private fun checked(request: Request) = http.newCall(request).execute().also {
        if (!it.isSuccessful) {
            val code = it.code
            it.close()
            throw IOException(when (code) {
                401, 403 -> "Access denied. Check the username and app password."
                409 -> "The destination folder is missing. Refresh and try again."
                507 -> "Your cloud storage is full."
                else -> "The cloud server returned HTTP $code."
            })
        }
    }

    private fun propfind(id: String, depth: String): List<CloudFile> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/><d:getetag/><d:resourcetype/></d:prop></d:propfind>"""
        val address = if (depth == "1" && id.isNotEmpty()) url(id).newBuilder().addPathSegment("").build() else url(id)
        checked(request(address).header("Depth", depth).method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use { response ->
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(response.body?.charStream() ?: throw IOException("Empty folder response."))
            val files = mutableListOf<CloudFile>()
            var href = ""; var name = ""; var mime = ""; var size = 0L; var modified = 0L; var etag = ""; var directory = false; var successful = false
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.DOCDECL) throw IOException("DTD content is not allowed.")
                if (type == XmlPullParser.START_TAG) when (parser.name) {
                    "response" -> { href = ""; name = ""; mime = ""; size = 0; modified = 0; etag = ""; directory = false; successful = false }
                    "href" -> href = parser.nextText()
                    "displayname" -> name = parser.nextText()
                    "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0
                    "getcontenttype" -> mime = parser.nextText()
                    "getetag" -> etag = parser.nextText()
                    "getlastmodified" -> modified = runCatching {
                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(parser.nextText())?.time ?: 0
                    }.getOrDefault(0)
                    "collection" -> directory = true
                    "status" -> if (" 200 " in parser.nextText()) successful = true
                }
                if (type == XmlPullParser.END_TAG && parser.name == "response") {
                    val resolved = address.resolve(href)
                    if (successful && resolved != null && resolved.host == root.host && resolved.port == root.port && resolved.scheme == root.scheme) {
                        val base = root.pathSegments.filter { it.isNotEmpty() }
                        val segments = resolved.pathSegments.filter { it.isNotEmpty() }
                        if (segments.take(base.size) == base && segments.none { '/' in it || it == ".." }) {
                            val remote = segments.drop(base.size).joinToString("/")
                            files += CloudFile(remote, remote.substringBeforeLast('/', ""), name.ifBlank { segments.lastOrNull() ?: connection.name }, mime.ifBlank { "application/octet-stream" }, size.coerceAtLeast(0), directory, modified, etag)
                        }
                    }
                }
                type = parser.next()
            }
            return files.distinctBy { it.id }
        }
    }

    override fun list(parentId: String) = propfind(parentId, "1").filter { it.id != parentId.trim('/') }
    override fun stat(id: String): CloudFile = (propfind(id, "0").firstOrNull { it.id == id.trim('/') }
        ?: throw IOException("This file is no longer available. Refresh the folder.")).also { versions[id] = it }
    override fun read(id: String, offset: Long, length: Int): ByteArray {
        val builder = request(url(id))
        val version = versions[id]
        if (version != null && version.etag.isNotBlank() && !version.etag.startsWith("W/")) builder.header("If-Match", version.etag)
        return HttpRanges.read(http, builder, offset, length)
    }

    override fun upload(parentId: String, name: String, mime: String, length: Long, input: () -> InputStream, progress: (Long) -> Unit): CloudFile {
        require(length >= 0)
        val cleanName = safeFileName(name)
        val id = listOf(parentId.trim('/'), cleanName).filter { it.isNotEmpty() }.joinToString("/")
        // Upload to a private staging name. An interrupted upload never replaces a good file.
        val temporary = listOf(parentId.trim('/'), ".cloudies-upload-${UUID.randomUUID()}").filter { it.isNotEmpty() }.joinToString("/")
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                input().use { source ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Upload interrupted.")
                        val count = source.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > length) throw IOException("The source file changed during upload.")
                        sink.write(buffer, 0, count)
                        progress(total)
                    }
                    if (total != length) throw IOException("The source file size changed. Please select it again.")
                }
            }
        }
        try {
            checked(request(url(temporary)).put(body).build()).close()
            checked(request(url(temporary)).header("Destination", url(id).toString()).header("Overwrite", "F").method("MOVE", null).build()).close()
            return stat(id)
        } catch (error: Exception) {
            runCatching { http.newCall(request(url(temporary)).delete().build()).execute().close() }
            throw error
        }
    }
    override fun mkdir(parentId: String, name: String): CloudFile {
        val id = listOf(parentId.trim('/'), safeFileName(name)).filter { it.isNotEmpty() }.joinToString("/")
        checked(request(url(id)).method("MKCOL", null).build()).close()
        return stat(id)
    }
    override fun rename(file: CloudFile, newName: String): CloudFile {
        val target = listOf(file.parentId.trim('/'), safeFileName(newName)).filter { it.isNotEmpty() }.joinToString("/")
        if (file.id == target) return file
        checked(request(url(file.id)).header("Destination", url(target).toString()).header("Overwrite", "F").method("MOVE", null).build()).close()
        return stat(target)
    }
    override fun delete(file: CloudFile) { checked(request(url(file.id)).delete().build()).close() }

    companion object {
        fun defaultHttp() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun canonicalEndpoint(value: String): HttpUrl {
            val url = (value.trim().trimEnd('/') + "/").toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Use an HTTPS WebDAV address without embedded passwords, queries, or fragments."
            }
            return url
        }
    }
}
