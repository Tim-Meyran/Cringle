// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SelfUpdateTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var releaseDir: Path
    private lateinit var installRoot: Path
    private lateinit var server: HttpServer
    private lateinit var source: HttpReleaseSource
    private val requests = ConcurrentHashMap<String, Int>()

    @BeforeEach
    fun setUp() {
        releaseDir = Files.createDirectories(temp.resolve("release"))
        installRoot = Files.createDirectories(temp.resolve("install"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            requests.merge(path, 1, Int::plus)
            val file = releaseDir.resolve(path)
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

    // --- tests ---

    @Test
    fun checkReportsANewerVersion() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        assertEquals(listOf("current 1.0.0", "latest 1.1.0", "update available"), check("1.0.0"))
    }

    @Test
    fun checkReportsUpToDate() {
        install("1.0.0", "1.0.0")
        release("1.0.0")
        assertEquals(listOf("current 1.0.0", "latest 1.0.0", "up to date"), check("1.0.0"))
    }

    @Test
    fun checkTreatsAPreReleaseAsOlderThanTheRelease() {
        install("1.0.0", "1.0.0")
        release("1.0.0-rc.1")
        assertEquals("up to date", check("1.0.0").last())
    }

    @Test
    fun checkOffersTheReleaseOverAPreRelease() {
        install("1.0.0-rc.1", "1.0.0-rc.1")
        release("1.0.0")
        assertEquals("update available", check("1.0.0-rc.1").last())
    }

    @Test
    fun updateSwitchesCurrentAndRestartsTheServices() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        val services = FakeServices()
        val lines = update("1.0.0", services = services)
        assertEquals("1.1.0", currentTarget())
        assertEquals(listOf("restart cringle-daemon.service"), services.calls)
        assertTrue(Files.exists(installRoot.resolve("1.1.0/bin/cringle")))
        assertTrue(lines.contains("updated to 1.1.0"))
    }

    @Test
    fun updateToTheSameVersionDoesNothing() {
        install("1.0.0", "1.0.0")
        release("1.0.0")
        assertEquals(listOf("already up to date (1.0.0)"), update("1.0.0"))
        assertEquals("1.0.0", currentTarget())
    }

    @Test
    fun wrongManifestShaAborts() {
        install("1.0.0", "1.0.0")
        release("1.1.0", manifestSha = "b".repeat(64))
        assertThrows(IllegalStateException::class.java) { update("1.0.0") }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertNoStaging()
    }

    @Test
    fun wrongSumsShaAborts() {
        install("1.0.0", "1.0.0")
        release("1.1.0", sumsSha = "b".repeat(64))
        assertThrows(IllegalStateException::class.java) { update("1.0.0") }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertNoStaging()
    }

    @Test
    fun wrongArchiveShaAborts() {
        install("1.0.0", "1.0.0")
        release("1.1.0", manifestSha = "b".repeat(64), sumsSha = "b".repeat(64))
        assertThrows(IllegalStateException::class.java) { update("1.0.0") }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertNoStaging()
    }

    @Test
    fun wrongSizeAborts() {
        install("1.0.0", "1.0.0")
        release("1.1.0", manifestSize = 1L)
        assertThrows(IllegalStateException::class.java) { update("1.0.0") }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertNoStaging()
    }

    // --- helpers ---

    /** Creates the release files for [version] and points `latest` at it. */
    private fun release(version: String, manifestSha: String? = null, sumsSha: String? = null, manifestSize: Long? = null) {
        val dir = Files.createDirectories(releaseDir.resolve("v$version"))
        val name = "cringle-$version-linux.tar.gz"
        Files.copy(makeArchive(version), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        val actualSha = sha256(dir.resolve(name))
        val actualSize = Files.size(dir.resolve(name))
        val manifest = """{"version":"$version","files":[{"name":"$name","size":${manifestSize ?: actualSize},"sha256":"${manifestSha ?: actualSha}","minJava":21}]}"""
        Files.writeString(dir.resolve("manifest.json"), manifest)
        Files.writeString(dir.resolve("SHA256SUMS"), "${sumsSha ?: actualSha}  $name\n")
        val latest = Files.createDirectories(releaseDir.resolve("latest"))
        Files.writeString(latest.resolve("manifest.json"), manifest)
    }

    /** A tar.gz with `cringle-<version>/bin/cringle` (executable) and `cringle-<version>/VERSION`. */
    private fun makeArchive(version: String): Path {
        val top = Files.createDirectories(temp.resolve("build-$version/cringle-$version"))
        Files.createDirectories(top.resolve("bin"))
        val script = top.resolve("bin/cringle")
        Files.writeString(script, "#!/bin/sh\necho cringle $version\n")
        script.toFile().setExecutable(true)
        Files.writeString(top.resolve("VERSION"), "$version\n")
        val archive = temp.resolve("cringle-$version-linux.tar.gz")
        val tar = ProcessBuilder("tar", "-czf", archive.toString(), "-C", top.parent.toString(), "cringle-$version").start()
        check(tar.waitFor(60, TimeUnit.SECONDS) && tar.exitValue() == 0) { "tar failed" }
        return archive
    }

    /** An install root with `current` pointing at [current] and a directory for every version in [versions]. */
    private fun install(current: String, vararg versions: String) {
        for (v in versions) {
            val dir = Files.createDirectories(installRoot.resolve(v))
            Files.writeString(dir.resolve("VERSION"), "$v\n")
        }
        SymlinkLinkSwitcher().switchTo(installRoot, current)
    }

    private fun update(running: String, requested: String? = null, allowMajor: Boolean = false, services: ServiceController = FakeServices()): List<String> =
        SelfUpdate(installRoot, running, Platform.LINUX, source, services, SymlinkLinkSwitcher(), Duration.ZERO, allowMajor).update(requested)

    private fun check(running: String): List<String> =
        SelfUpdate(installRoot, running, Platform.LINUX, source, FakeServices(), SymlinkLinkSwitcher(), Duration.ZERO).check()

    private fun currentTarget(): String? = SymlinkLinkSwitcher().currentTarget(installRoot)

    private fun assertNoStaging() {
        val staging = Files.list(installRoot).use { stream -> stream.filter { it.fileName.toString().startsWith(".update-") }.toList() }
        assertTrue(staging.isEmpty(), "staging directories left behind: $staging")
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class FakeServices(private val active: List<String> = listOf("cringle-daemon.service"), var healthy: Boolean = true) : ServiceController {
        val calls = mutableListOf<String>()
        override fun activeServices() = active
        override fun stop(name: String) { calls += "stop $name" }
        override fun start(name: String) { calls += "start $name" }
        override fun restart(name: String) { calls += "restart $name" }
        override fun isActive(name: String) = healthy
    }
}
