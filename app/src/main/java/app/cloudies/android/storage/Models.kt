package app.cloudies.android.storage

import java.io.InputStream

data class Connection(
    val id: String,
    val name: String,
    val kind: String,
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val rootId: String = ""
)

data class CloudFile(
    val id: String,
    val parentId: String,
    val name: String,
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val directory: Boolean = false,
    val modified: Long = 0,
    val etag: String = ""
)

interface CloudClient {
    fun list(parentId: String): List<CloudFile>
    fun stat(id: String): CloudFile
    fun read(id: String, offset: Long, length: Int): ByteArray
    fun upload(parentId: String, name: String, mime: String, length: Long,
               input: () -> InputStream, progress: (Long) -> Unit): CloudFile
    fun mkdir(parentId: String, name: String): CloudFile
    fun rename(file: CloudFile, newName: String): CloudFile
    fun delete(file: CloudFile)
}

fun safeFileName(name: String): String {
    require(name.isNotBlank() && name != "." && name != "..") { "Enter a file name." }
    require(name.length <= 240 && name.none { it == '/' || it == '\\' || it.code < 32 }) {
        "Names cannot contain slashes or control characters."
    }
    return name.trim()
}

fun humanBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var n = value.toDouble() / 1024
    var unit = 0
    while (n >= 1024 && unit < units.lastIndex) { n /= 1024; unit++ }
    return java.lang.String.format(java.util.Locale.ROOT, "%.1f %s", n, units[unit])
}
