package app.cloudies.android.storage

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Uses the narrow drive.file scope. No client secret or hosted OAuth server. */
class DriveClient(private val context: Context, private val connection: Connection) : CloudClient {
    private val http = WebDavClient.defaultHttp()
    @Volatile private var cachedToken: Pair<Long, String>? = null
    private fun token(): String {
        cachedToken?.takeIf { System.currentTimeMillis() - it.first < 45L * 60 * 1000 }?.let { return it.second }
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val result = try { Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 30, TimeUnit.SECONDS) }
        catch (_: Exception) { throw IOException("Google Drive needs authorization. Open Settings in Cloudies and connect Google Drive again.") }
        if (result.hasResolution()) throw IOException("Google Drive needs authorization. Reconnect it in Cloudies Settings.")
        val value = result.accessToken ?: throw IOException("Google Drive authorization expired. Reconnect the account.")
        cachedToken = System.currentTimeMillis() to value
        return value
    }
    private fun builder(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${token()}")
    private fun json(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Google Drive returned HTTP ${response.code}. Check authorization and storage space.")
        JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
    }
    private fun item(value: JSONObject, parent: String = ""): CloudFile {
        val folder = value.optString("mimeType") == FOLDER
        return CloudFile(value.getString("id"), value.optJSONArray("parents")?.optString(0) ?: parent, value.optString("name"), value.optString("mimeType", "application/octet-stream"), value.optString("size").toLongOrNull() ?: 0, folder,
            runCatching { java.time.Instant.parse(value.optString("modifiedTime")).toEpochMilli() }.getOrDefault(0), value.optString("md5Checksum"))
    }
    override fun list(parentId: String): List<CloudFile> {
        val result = mutableListOf<CloudFile>()
        var page = ""
        do {
            val url = "$BASE/files".toHttpUrl().newBuilder().addQueryParameter("q", "'${parentId.replace("'", "\\'")}' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files($FIELDS)").addQueryParameter("pageSize", "1000")
                .apply { if (page.isNotEmpty()) addQueryParameter("pageToken", page) }.build()
            val response = json(builder(url.toString()).build())
            val files = response.optJSONArray("files")
            if (files != null) for (index in 0 until files.length()) result += item(files.getJSONObject(index), parentId)
            page = response.optString("nextPageToken")
        } while (page.isNotEmpty())
        return result
    }
    override fun stat(id: String): CloudFile = item(json(builder("$BASE/files/$id?fields=$FIELDS").build()))
    override fun read(id: String, offset: Long, length: Int) = HttpRanges.read(http, builder("$BASE/files/$id?alt=media"), offset, length)
    override fun mkdir(parentId: String, name: String): CloudFile {
        val body = JSONObject().put("name", safeFileName(name)).put("mimeType", FOLDER).put("parents", org.json.JSONArray().put(parentId))
        return item(json(builder("$BASE/files?fields=$FIELDS").post(body.toString().toRequestBody(JSON)).build()), parentId)
    }
    override fun rename(file: CloudFile, newName: String): CloudFile = item(json(builder("$BASE/files/${file.id}?fields=$FIELDS")
        .patch(JSONObject().put("name", safeFileName(newName)).toString().toRequestBody(JSON)).build()))
    override fun delete(file: CloudFile) {
        json(builder("$BASE/files/${file.id}").patch("{\"trashed\":true}".toRequestBody(JSON)).build())
    }
    override fun upload(parentId: String, name: String, mime: String, length: Long, input: () -> InputStream, progress: (Long) -> Unit): CloudFile {
        require(length >= 0)
        val metadata = JSONObject().put("name", safeFileName(name)).put("parents", org.json.JSONArray().put(parentId))
        val session = http.newCall(builder("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=$FIELDS")
            .header("X-Upload-Content-Type", mime).header("X-Upload-Content-Length", length.toString())
            .post(metadata.toString().toRequestBody(JSON)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Could not start the Google Drive upload (HTTP ${response.code}).")
            response.header("Location") ?: throw IOException("Google Drive did not return an upload session.")
        }
        // A session URL must stay on Google's API host before sending an access token.
        val sessionUrl = session.toHttpUrl()
        if (!sessionUrl.isHttps || sessionUrl.host != "www.googleapis.com") throw IOException("Unexpected upload host.")
        input().use { source ->
            var sent = 0L
            val buffer = ByteArray(1024 * 1024)
            do {
                val wanted = minOf(buffer.size.toLong(), length - sent).toInt()
                var have = 0
                while (have < wanted) {
                    val count = source.read(buffer, have, wanted - have)
                    if (count < 0) throw IOException("The source file ended during upload.")
                    have += count
                }
                val range = if (length == 0L) "bytes */0" else "bytes $sent-${sent + have - 1}/$length"
                http.newCall(builder(session).header("Content-Range", range)
                    .put(buffer.copyOf(have).toRequestBody(mime.toMediaType())).build()).execute().use { response ->
                    if (response.code != 308 && !response.isSuccessful) throw IOException("Google Drive upload interrupted (HTTP ${response.code}). Retry from Transfers.")
                    if (response.isSuccessful) {
                        progress(length)
                        return item(JSONObject(response.body?.string().orEmpty()), parentId)
                    }
                    val accepted = response.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0
                    if (accepted != sent + have) throw IOException("Google Drive acknowledged an unexpected upload position. Retry the transfer.")
                    sent = accepted
                    progress(sent)
                }
            } while (sent < length)
        }
        throw IOException("Google Drive did not finish the upload.")
    }
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val FOLDER = "application/vnd.google-apps.folder"
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val FIELDS = "id,name,mimeType,size,parents,modifiedTime,md5Checksum"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun ensureRoot(context: Context): String {
            val client = DriveClient(context, Connection("", "Google Drive", "drive"))
            val url = "$BASE/files".toHttpUrl().newBuilder().addQueryParameter("q", "name = 'Cloudies' and mimeType = '$FOLDER' and trashed = false and 'root' in parents")
                .addQueryParameter("fields", "files(id)").build()
            val files = client.json(client.builder(url.toString()).build()).optJSONArray("files")
            return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else client.mkdir("root", "Cloudies").id
        }
    }
}
