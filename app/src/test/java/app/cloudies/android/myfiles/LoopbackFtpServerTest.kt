package app.cloudies.android.myfiles

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.InetSocketAddress

class LoopbackFtpServerTest {
    private class MemoryFiles : BridgeFileSystem {
        val content = ByteArray(700_000) { (it % 251).toByte() }
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun stat(path: String) = when (path) {
            "/" -> BridgeEntry("Cloudies", true)
            "/Cloud" -> BridgeEntry("Cloud", true)
            "/Cloud/नोट्स.mp4" -> BridgeEntry("नोट्स.mp4", false, content.size.toLong())
            else -> throw IOException("Missing.")
        }
        override fun list(path: String) = when (path) {
            "/" -> listOf(stat("/Cloud"))
            "/Cloud" -> listOf(stat("/Cloud/नोट्स.mp4"))
            else -> throw IOException("Missing.")
        }
        override fun open(path: String): BridgeReader {
            val entry = stat(path)
            if (entry.directory) throw IOException("Directory.")
            return object : BridgeReader {
                override val size = entry.size
                override fun read(offset: Long, length: Int): ByteArray {
                    reads.add(offset to length)
                    return content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + length))
                }
            }
        }
    }

    private class Client(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        val input: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        init { assertTrue(input.readLine().startsWith("220 ")) }
        fun command(value: String): String { output.write("$value\r\n"); output.flush(); return input.readLine() }
        fun login() { assertTrue(command("USER cloudies").startsWith("331 ")); assertTrue(command("PASS test-password").startsWith("230 ")) }
        fun passive(extended: Boolean = false): Socket {
            val response = command(if (extended) "EPSV" else "PASV")
            val port = if (extended) response.substringAfter("(|||").substringBefore('|').toInt()
            else response.substringAfter('(').substringBefore(')').split(',').let {
                assertEquals(listOf("127", "0", "0", "1"), it.take(4)); it[4].toInt() * 256 + it[5].toInt()
            }
            return Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        }
        fun download(command: String, extended: Boolean = false): ByteArray = passive(extended).use { data ->
            assertTrue(this.command(command).startsWith("150 "))
            val bytes = data.getInputStream().readBytes()
            assertTrue(input.readLine().startsWith("226 "))
            bytes
        }
        override fun close() { socket.close() }
    }

    private fun server(files: MemoryFiles = MemoryFiles(), run: (LoopbackFtpServer, MemoryFiles) -> Unit) {
        LoopbackFtpServer(files, "cloudies", "test-password", 0).use { server -> server.start(); run(server, files) }
    }

    @Test fun `authentication required and wrong passwords do not unlock files`() = server { server, _ ->
        Client(server.port).use { client ->
            assertTrue(client.command("LIST").startsWith("530 "))
            assertTrue(client.command("PASS test-password").startsWith("530 "))
            assertTrue(client.command("USER cloudies").startsWith("331 "))
            assertTrue(client.command("PASS incorrect").startsWith("530 "))
            assertTrue(client.command("CWD /Cloud").startsWith("530 "))
            client.login()
            assertTrue(client.command("PWD").contains("\"/\""))
        }
    }

    @Test fun `passive listings and unicode names work with both listing formats`() = server { server, _ ->
        Client(server.port).use { client ->
            client.login()
            assertTrue(client.download("LIST -la").toString(Charsets.UTF_8).contains("dr-xr-xr-x"))
            assertTrue(client.command("CWD /Cloud").startsWith("250 "))
            val listing = client.download("MLSD", extended = true).toString(Charsets.UTF_8)
            assertTrue(listing.contains("type=file;")); assertTrue(listing.contains("नोट्स.mp4"))
            assertEquals("नोट्स.mp4\r\n", client.download("NLST").toString(Charsets.UTF_8))
        }
    }

    @Test fun `seek reads only tail and next transfer resets the offset`() = server { server, files ->
        Client(server.port).use { client ->
            client.login()
            assertTrue(client.command("REST 699980").startsWith("350 "))
            assertArrayEquals(files.content.copyOfRange(699980, 700000), client.download("RETR /Cloud/नोट्स.mp4"))
            assertEquals(listOf(699980L to 20), files.reads)
            assertArrayEquals(files.content, client.download("RETR /Cloud/नोट्स.mp4"))
            assertTrue(files.reads.drop(1).all { it.second <= 256 * 1024 })
        }
    }

    @Test fun `mutating commands and active FTP are explicitly refused`() = server { server, _ ->
        Client(server.port).use { client ->
            client.login()
            for (verb in listOf("STOR", "APPE", "DELE", "MKD", "RMD", "RNFR", "RNTO", "SITE"))
                assertTrue(client.command("$verb /Cloud/file").startsWith("550 "))
            assertTrue(client.command("PORT 10,0,0,1,4,0").startsWith("502 "))
            assertTrue(client.command("EPRT |1|8.8.8.8|80|").startsWith("502 "))
            assertTrue(client.command("AUTH TLS").startsWith("502 "))
        }
    }

    @Test fun `metadata invalid seeks and missing files return useful failures`() = server { server, _ ->
        Client(server.port).use { client ->
            client.login()
            assertEquals("213 700000", client.command("SIZE /Cloud/नोट्स.mp4"))
            assertTrue(client.command("MDTM /Cloud/नोट्स.mp4").startsWith("213 "))
            assertTrue(client.command("REST -1").startsWith("501 "))
            assertTrue(client.command("REST 9223372036854775808").startsWith("501 "))
            assertTrue(client.command("REST 700001").startsWith("350 "))
            assertTrue(client.command("RETR /Cloud/नोट्स.mp4").startsWith("551 "))
            assertTrue(client.command("RETR /missing").startsWith("550 "))
            assertTrue(client.command("SIZE /Cloud").startsWith("550 "))
        }
    }

    @Test fun `server shutdown closes control and pending data sockets`() = server { server, _ ->
        Client(server.port).use { client ->
            client.login()
            client.passive().use { data ->
                val port = server.port
                server.close()
                assertEquals(-1, client.socket.getInputStream().read())
                assertTrue(runCatching { data.getInputStream().read() == -1 }.getOrDefault(true))
                assertFalse(server.isRunning)
                // Bind a distinct source port to avoid TCP self-connections.
                var probe = Socket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
                while (probe.localPort == port) {
                    probe.close()
                    probe = Socket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
                }
                probe.use {
                    // A loopback forwarder can acknowledge TCP while its target
                    // is closing. Verify that no new FTP session is available.
                    val greeting = runCatching {
                        it.connect(InetSocketAddress("127.0.0.1", port), 1000)
                        it.soTimeout = 1000
                        BufferedReader(InputStreamReader(it.getInputStream())).readLine()
                    }.getOrNull()
                    assertFalse(greeting?.startsWith("220 ") == true)
                }
            }
        }
    }

    @Test fun `paths stay within the virtual root and block control characters`() {
        assertEquals("/", BridgePaths.normalize("/Cloud/folder", "../../../../"))
        assertEquals("/Cloud/file", BridgePaths.normalize("/Cloud/folder", "../file"))
        assertEquals("/Cloud/नोट्स.mp4", BridgePaths.normalize("/", "/Cloud/नोट्स.mp4"))
        listOf("a\\b", "x\r\nDELE x", "x\u0000").forEach { assertTrue(runCatching { BridgePaths.normalize("/", it) }.isFailure) }
        assertFalse(BridgePaths.visibleName("folder/escape"))
        assertFalse(BridgePaths.visibleName("."))
    }
}
