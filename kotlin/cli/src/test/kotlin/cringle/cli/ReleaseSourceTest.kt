// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseSourceTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var releaseDir: Path
    private lateinit var server: HttpServer
    private lateinit var source: HttpReleaseSource

    private val sha = "a".repeat(64)

    @BeforeEach
    fun setUp() {
        releaseDir = temp.resolve("release")
        val manifest = """{"version":"1.2.3","files":[{"name":"cringle-1.2.3-linux.tar.gz","size":3,"sha256":"$sha","minJava":21}]}"""
        write("v1.2.3/manifest.json", manifest)
        write("v1.2.3/SHA256SUMS", "$sha  cringle-1.2.3-linux.tar.gz\n")
        write("v1.2.3/cringle-1.2.3-linux.tar.gz", "abc")
        write("latest/manifest.json", manifest)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val file = releaseDir.resolve(exchange.requestURI.path.removePrefix("/"))
            if (Files.isRegularFile(file)) {
                val bytes = Files.readAllBytes(file)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
        }
        server.start()
        source = HttpReleaseSource("http://127.0.0.1:${server.address.port}", defaultBase = false)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun latestVersionComesFromTheLatestManifest() {
        assertEquals("1.2.3", source.latestVersion())
    }

    @Test
    fun manifestIsParsed() {
        val manifest = source.manifest("1.2.3")
        assertEquals("1.2.3", manifest.version)
        assertEquals(1, manifest.files.size)
        val file = manifest.files.single()
        assertEquals("cringle-1.2.3-linux.tar.gz", file.name)
        assertEquals(3L, file.size)
        assertEquals(sha, file.sha256)
        assertEquals(21, file.minJava)
    }

    @Test
    fun checksumsAreParsed() {
        assertEquals(mapOf("cringle-1.2.3-linux.tar.gz" to sha), source.checksums("1.2.3"))
    }

    @Test
    fun downloadWritesTheFile() {
        val target = temp.resolve("out.bin")
        source.download("1.2.3", "cringle-1.2.3-linux.tar.gz", target)
        assertEquals("abc", Files.readString(target))
    }

    @Test
    fun missingFileFails() {
        val thrown = assertThrows(IllegalStateException::class.java) { source.manifest("9.9.9") }
        assertTrue(thrown.message!!.contains("404"))
    }

    private fun write(relative: String, content: String) {
        val file = releaseDir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
