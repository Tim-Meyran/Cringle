// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
        // a junction or link `current` is removed itself, so that the cleanup of the temporary folder never follows it
        Files.deleteIfExists(installRoot.resolve("current"))
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

    @Test
    fun rollbackWhenTheNewVersionDoesNotComeUp() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        val services = FakeServices(healthy = false)
        assertThrows(IllegalStateException::class.java) { update("1.0.0", services = services) }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertEquals(listOf("restart cringle-daemon.service", "restart cringle-daemon.service"), services.calls)
        assertNoStaging()
    }

    @Test
    fun rollbackWhenServiceRestartFails() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        val services = ThrowingServices()
        assertThrows(IllegalStateException::class.java) { update("1.0.0", services = services) }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertNoStaging()
    }

    @Test
    fun rollbackDoesNotDeleteTheActiveVersionWhenReinstalling() {
        install("1.0.0", "1.0.0")
        release("1.0.0")
        val services = FakeServices(healthy = false)
        assertThrows(IllegalStateException::class.java) { update("1.0.0", requested = "1.0.0", services = services) }
        assertEquals("1.0.0", currentTarget())
        assertTrue(Files.exists(installRoot.resolve("1.0.0")))
        assertNoStaging()
    }

    @Test
    fun healthyRequiresAStablePeriod() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        val services = FlakyServices()
        assertThrows(IllegalStateException::class.java) { update("1.0.0", services = services, stablePeriod = Duration.ofMillis(200), timeout = Duration.ofSeconds(2)) }
        assertEquals("1.0.0", currentTarget())
        assertFalse(Files.exists(installRoot.resolve("1.1.0")))
        assertNoStaging()
    }

    @Test
    fun cleanupKeepsOnlyTheTwoNewestVersions() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        update("1.0.0")
        release("1.2.0")
        update("1.1.0")
        release("1.3.0")
        update("1.2.0")
        assertEquals(setOf("1.2.0", "1.3.0"), versionDirs())
        assertEquals("1.3.0", currentTarget())
    }

    @Test
    fun majorJumpIsRefusedWithoutAllowMajor() {
        install("1.0.0", "1.0.0")
        release("2.0.0")
        assertThrows(UsageException::class.java) { update("1.0.0") }
        assertEquals("1.0.0", currentTarget())
        assertTrue(requests.keys.none { it.startsWith("v2.0.0/") }, "nothing of 2.0.0 may be downloaded: ${requests.keys}")
    }

    @Test
    fun majorJumpIsAllowedWithAllowMajor() {
        install("1.0.0", "1.0.0")
        release("2.0.0")
        update("1.0.0", allowMajor = true)
        assertEquals("2.0.0", currentTarget())
    }

    @Test
    fun windowsUpdateStopsSwitchesAndStarts() {
        install("1.0.0", "1.0.0")
        releaseWindows("1.1.0")
        val services = FakeServices()
        val lines = SelfUpdate(installRoot, "1.0.0", Platform.WINDOWS, source, services, linkSwitcherFor(Platform.current()), Duration.ZERO, false, Duration.ZERO).update(null)
        assertEquals("1.1.0", currentTarget())
        assertEquals(listOf("stop cringle-daemon.service", "start cringle-daemon.service"), services.calls)
        assertTrue(lines.contains("updated to 1.1.0"))
    }

    @Test
    fun theCommandChecksAgainstTheConfiguredBaseUrl() {
        install("1.0.0", "1.0.0")
        release("1.1.0")
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val environment = mapOf("CRINGLE_RELEASE_BASE_URL" to "http://127.0.0.1:${server.address.port}")
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), environment)
            .run(listOf("self-update", "--check", "--install-root", installRoot.toString()))
        assertEquals(0, code, err.toString())
        assertTrue(out.toString().contains("update available"), out.toString())
    }

    @Test
    fun theCommandRefusesADirectoryThatIsNotAnInstallation() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), emptyMap())
            .run(listOf("self-update", "--check", "--install-root", temp.resolve("nope").toString()))
        assertEquals(2, code)
        assertTrue(err.toString().contains("not an installed distribution"), err.toString())
    }

    @Test
    fun theCommandExitsWithOneWhenTheChecksumIsWrong() {
        install("1.0.0", "1.0.0")
        release("1.1.0", manifestSha = "b".repeat(64))
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val environment = mapOf("CRINGLE_RELEASE_BASE_URL" to "http://127.0.0.1:${server.address.port}")
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), environment)
            .run(listOf("self-update", "--install-root", installRoot.toString()))
        assertEquals(1, code, err.toString())
        assertEquals("1.0.0", currentTarget())
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

    /** The names of the version directories below the install root. */
    private fun versionDirs(): Set<String> =
        Files.list(installRoot).use { stream -> stream.map { it.fileName.toString() }.filter { SemVer.parse(it) != null }.toList() }.toSet()

    /** Creates the Windows release files for [version] and points `latest` at it. */
    private fun releaseWindows(version: String) {
        val dir = Files.createDirectories(releaseDir.resolve("v$version"))
        val name = Platform.WINDOWS.archiveName(version)
        Files.copy(makeWindowsArchive(version), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        val actualSha = sha256(dir.resolve(name))
        val actualSize = Files.size(dir.resolve(name))
        val manifest = """{"version":"$version","files":[{"name":"$name","size":$actualSize,"sha256":"$actualSha","minJava":21}]}"""
        Files.writeString(dir.resolve("manifest.json"), manifest)
        Files.writeString(dir.resolve("SHA256SUMS"), "$actualSha  $name\n")
        val latest = Files.createDirectories(releaseDir.resolve("latest"))
        Files.writeString(latest.resolve("manifest.json"), manifest)
    }

    /** A zip with `cringle-<version>/bin/cringle.bat` and `cringle-<version>/VERSION`. */
    private fun makeWindowsArchive(version: String): Path {
        val top = Files.createDirectories(temp.resolve("build-win-$version/cringle-$version"))
        Files.createDirectories(top.resolve("bin"))
        Files.writeString(top.resolve("bin/cringle.bat"), "@echo off\r\n")
        Files.writeString(top.resolve("VERSION"), "$version\n")
        val archive = temp.resolve(Platform.WINDOWS.archiveName(version))
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            Files.walk(top).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    zip.putNextEntry(java.util.zip.ZipEntry("cringle-$version/" + top.relativize(file).toString().replace('\\', '/')))
                    zip.write(Files.readAllBytes(file))
                    zip.closeEntry()
                }
            }
        }
        return archive
    }

    /** An install root with `current` pointing at [current] and a directory for every version in [versions]. */
    private fun install(current: String, vararg versions: String) {
        for (v in versions) {
            val dir = Files.createDirectories(installRoot.resolve(v))
            Files.writeString(dir.resolve("VERSION"), "$v\n")
        }
        linkSwitcherFor(Platform.current()).switchTo(installRoot, current)
    }

    private fun update(running: String, requested: String? = null, allowMajor: Boolean = false, services: ServiceController = FakeServices(), stablePeriod: Duration = Duration.ZERO, timeout: Duration = Duration.ofSeconds(30)): List<String> =
        SelfUpdate(installRoot, running, Platform.LINUX, source, services, linkSwitcherFor(Platform.current()), timeout, allowMajor, stablePeriod).update(requested)

    private fun check(running: String): List<String> =
        SelfUpdate(installRoot, running, Platform.LINUX, source, FakeServices(), linkSwitcherFor(Platform.current()), Duration.ZERO).check()

    private fun currentTarget(): String? = linkSwitcherFor(Platform.current()).currentTarget(installRoot)

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

    private class FlakyServices : ServiceController {
        val calls = mutableListOf<String>()
        private var isActiveCount = 0
        override fun activeServices() = listOf("cringle-daemon.service")
        override fun stop(name: String) { calls += "stop $name" }
        override fun start(name: String) { calls += "start $name" }
        override fun restart(name: String) { calls += "restart $name" }
        override fun isActive(name: String): Boolean {
            isActiveCount++
            return isActiveCount == 1
        }
    }

    private class ThrowingServices : ServiceController {
        val calls = mutableListOf<String>()
        override fun activeServices() = listOf("cringle-daemon.service")
        override fun stop(name: String) { calls += "stop $name" }
        override fun start(name: String) { calls += "start $name" }
        override fun restart(name: String) { throw IllegalStateException("restart failed") }
        override fun isActive(name: String) = true
    }
}
