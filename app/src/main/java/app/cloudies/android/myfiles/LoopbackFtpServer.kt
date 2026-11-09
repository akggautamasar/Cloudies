package app.cloudies.android.myfiles

import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/**
 * Minimal read-only FTP adapter for Samsung My Files' Network storage client.
 * Every control/data socket binds to IPv4 loopback, never to Wi-Fi or mobile interfaces.
 * Active FTP is deliberately rejected, so this cannot be used for FTP bounce attacks.
 */
class LoopbackFtpServer(
    private val files: BridgeFileSystem,
    private val username: String,
    private val password: String,
    private val requestedPort: Int = 2121
) : Closeable {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private val slots = Semaphore(4)
    private val workers = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "cloudies-myfiles-client").apply { isDaemon = true } }
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var closed = false
    @Volatile var lastActivityNanos: Long = System.nanoTime()
        private set
    val port: Int get() = listener?.localPort ?: 0
    val isRunning: Boolean get() = listener?.isClosed == false && !closed

    @Synchronized fun start() {
        check(!closed) { "Create a new server after stopping." }
        if (isRunning) return
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(loopback, requestedPort), 4)
        } catch (error: Exception) { server.close(); throw error }
        listener = server
        touch()
        Thread({ acceptClients(server) }, "cloudies-myfiles-listener").apply { isDaemon = true; start() }
    }

    private fun acceptClients(server: ServerSocket) {
        while (!closed && !server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            if (!socket.inetAddress.isLoopbackAddress || !slots.tryAcquire()) {
                runCatching { socket.getOutputStream().write("421 Connection limit reached.\r\n".toByteArray()) }
                socket.close()
                continue
            }
            val session = Session(socket)
            sessions.add(session)
            if (closed) { session.close(); sessions.remove(session); slots.release(); break }
            try {
                workers.execute {
                    try { session.run() } finally { session.close(); sessions.remove(session); slots.release() }
                }
            } catch (_: RejectedExecutionException) { session.close(); sessions.remove(session); slots.release() }
        }
    }

    private fun touch() { lastActivityNanos = System.nanoTime() }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { listener?.close() }
        // Do not use Kotlin's size-optimized toList(): clients can leave between
        // its size check and iterator.next(). The concurrent iterator is safe.
        for (session in sessions) session.close()
        workers.shutdownNow()
    }

    private inner class Session(private val control: Socket) : Closeable {
        private val input = BufferedInputStream(control.getInputStream())
        private val output = BufferedWriter(OutputStreamWriter(control.getOutputStream(), Charsets.UTF_8))
        @Volatile private var passive: ServerSocket? = null
        @Volatile private var data: Socket? = null
        private var authenticated = false
        private var userAccepted = false
        private var failures = 0
        private var cwd = "/"
        private var restart = 0L
        private var finished = false

        fun run() {
            control.soTimeout = 180_000
            try {
                reply(220, "Cloudies on this phone. Read-only access.")
                while (!closed && !finished && !control.isClosed) {
                    val line = readCommand() ?: break
                    touch()
                    val command = line.substringBefore(' ').uppercase(Locale.ROOT)
                    val argument = line.substringAfter(' ', "")
                    try { command(command, argument) }
                    catch (_: IllegalArgumentException) { closeData(); reply(501, "Invalid argument.") }
                    catch (_: Exception) { closeData(); reply(550, "File unavailable. Check the account in Cloudies.") }
                }
            } catch (_: IOException) { /* Client closed, timed out, or service stopped. */ }
        }

        private fun readCommand(): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 4096) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == 10) return bytes.toByteArray().toString(Charsets.UTF_8).removeSuffix("\r")
                bytes.write(byte)
            }
            throw IOException("Command too long.")
        }

        private fun command(command: String, argument: String) {
            when (command) {
                "QUIT" -> { reply(221, "Goodbye."); finished = true; return }
                "SYST" -> { reply(215, "UNIX Type: L8"); return }
                "FEAT" -> {
                    output.write("211-Features\r\n UTF8\r\n EPSV\r\n REST STREAM\r\n SIZE\r\n MDTM\r\n MLST type*;size*;modify*;perm*;\r\n211 End\r\n")
                    output.flush(); return
                }
                "USER" -> {
                    authenticated = false; userAccepted = constantEquals(username, argument); restart = 0; cwd = "/"; closeData()
                    reply(331, "Password required."); return
                }
                "PASS" -> {
                    authenticated = userAccepted && constantEquals(password, argument)
                    if (authenticated) reply(230, "Signed in. Read-only cloud access.")
                    else { failures++; reply(530, "Login failed."); if (failures >= 3) finished = true }
                    return
                }
                "AUTH" -> { reply(502, "TLS unavailable. This connection stays on this phone."); return }
            }
            if (!authenticated) { reply(530, "Sign in first."); return }
            when (command) {
                "NOOP" -> reply(200, "OK.")
                "OPTS" -> if (argument.equals("UTF8 ON", true) || argument.startsWith("MLST ", true)) reply(200, "OK.") else reply(501, "Unsupported option.")
                "CLNT" -> reply(200, "OK.")
                "TYPE" -> if (argument.uppercase(Locale.ROOT) in setOf("I", "A", "A N")) reply(200, "Type accepted.") else reply(504, "Unsupported type.")
                "MODE" -> if (argument.equals("S", true)) reply(200, "Stream mode.") else reply(504, "Stream mode only.")
                "STRU" -> if (argument.equals("F", true)) reply(200, "File structure.") else reply(504, "File structure only.")
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\" is the current directory.")
                "CWD", "XCWD" -> changeDirectory(path(argument))
                "CDUP", "XCUP" -> changeDirectory(path(".."))
                "PASV", "EPSV" -> passive(command == "EPSV")
                "PORT", "EPRT" -> reply(502, "Use passive mode.")
                "LIST", "NLST", "MLSD" -> listing(command, argument)
                "MLST" -> {
                    val target = path(argument)
                    val entry = files.stat(target)
                    output.write("250-Listing\r\n ${facts(entry)} ${target}\r\n250 End\r\n"); output.flush()
                }
                "SIZE" -> {
                    val entry = files.stat(path(argument))
                    if (entry.directory) reply(550, "Not a file.") else reply(213, entry.size.toString())
                }
                "MDTM" -> reply(213, timestamp(files.stat(path(argument)).modified))
                "REST" -> {
                    val offset = argument.toLongOrNull()
                    if (offset == null || offset < 0) reply(501, "Invalid offset.")
                    else { restart = offset; reply(350, "Restart position accepted.") }
                }
                "RETR" -> retrieve(path(argument))
                "ABOR" -> { closeData(); restart = 0; reply(226, "No transfer active.") }
                "STOR", "STOU", "APPE", "DELE", "RMD", "XRMD", "MKD", "XMKD", "RNFR", "RNTO", "MFMT", "SITE" -> {
                    closeData(); reply(550, "Read-only. Upload using Share > Cloudies.")
                }
                else -> reply(502, "Command not supported.")
            }
        }

        private fun path(argument: String) = BridgePaths.normalize(cwd, argument)
        private fun changeDirectory(target: String) {
            if (!files.stat(target).directory) { reply(550, "Not a directory."); return }
            cwd = target; reply(250, "Directory changed.")
        }

        private fun passive(extended: Boolean) {
            closeData()
            val server = ServerSocket()
            try {
                server.bind(InetSocketAddress(loopback, 0), 1)
                server.soTimeout = 15_000
                passive = server
                val port = server.localPort
                if (extended) reply(229, "Entering Extended Passive Mode (|||$port|)")
                else reply(227, "Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
            } catch (error: Exception) { server.close(); throw error }
        }

        private fun transfer(write: (Socket) -> Unit) {
            val server = passive ?: run { reply(425, "Enter passive mode first."); return }
            try {
                reply(150, "Opening data connection.")
                val socket = server.accept()
                data = socket
                if (!socket.inetAddress.isLoopbackAddress) throw IOException("Loopback required.")
                socket.soTimeout = 30_000
                socket.use { write(it) }
                closeData()
                reply(226, "Transfer complete.")
            } catch (_: Exception) { closeData(); reply(426, "Transfer interrupted. Check Cloudies and retry.") }
        }

        private fun listing(command: String, argument: String) {
            // My Files/FTP clients may request LIST -a or LIST -la.
            val option = argument.substringBefore(' ')
            val actual = if (option in setOf("-a", "-l", "-la", "-al")) argument.substringAfter(' ', "") else argument
            val target = path(actual)
            val entry = files.stat(target)
            val entries = if (entry.directory) files.list(target) else listOf(entry)
            val lines = entries.filter { BridgePaths.visibleName(it.name) }.map { file ->
                when (command) {
                    "NLST" -> file.name
                    "MLSD" -> "${facts(file)} ${file.name}"
                    else -> "${if (file.directory) "dr-xr-xr-x" else "-r--r--r--"} 1 cloudies cloudies ${file.size.coerceAtLeast(0)} ${listDate.format(Instant.ofEpochMilli(file.modified.coerceAtLeast(0)))} ${file.name}"
                }
            }
            transfer { socket ->
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                lines.forEach { writer.write(it); writer.write("\r\n"); touch() }
                writer.flush()
            }
        }

        private fun retrieve(target: String) {
            val offset = restart
            restart = 0
            val file = files.open(target)
            if (offset > file.size) { closeData(); reply(551, "Offset beyond end of file."); return }
            transfer { socket ->
                val stream = socket.getOutputStream()
                var position = offset
                while (position < file.size) {
                    if (closed || control.isClosed || Thread.currentThread().isInterrupted) throw IOException("Stopped.")
                    val wanted = minOf(256L * 1024, file.size - position).toInt()
                    val bytes = file.read(position, wanted)
                    if (bytes.size != wanted) throw IOException("Short cloud read.")
                    stream.write(bytes)
                    position += bytes.size
                    touch()
                }
                stream.flush()
            }
        }

        private fun reply(code: Int, message: String) { output.write("$code $message\r\n"); output.flush() }
        private fun closeData() {
            runCatching { data?.close() }; data = null
            runCatching { passive?.close() }; passive = null
        }
        override fun close() { runCatching { control.close() }; closeData() }
    }

    companion object {
        private val machineDate = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(ZoneOffset.UTC)
        private val listDate = DateTimeFormatter.ofPattern("MMM dd  yyyy", Locale.US).withZone(ZoneOffset.UTC)
        private fun timestamp(modified: Long) = machineDate.format(Instant.ofEpochMilli(modified.coerceAtLeast(0)))
        private fun facts(entry: BridgeEntry) = "type=${if (entry.directory) "dir" else "file"};size=${entry.size.coerceAtLeast(0)};modify=${timestamp(entry.modified)};perm=${if (entry.directory) "el" else "r"};"
        private fun constantEquals(expected: String, actual: String) = MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }
}
