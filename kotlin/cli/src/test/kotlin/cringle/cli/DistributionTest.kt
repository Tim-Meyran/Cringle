// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.v1.EngineId
import cringle.daemon.v1.CreateEngineRequest
import cringle.daemon.v1.DaemonServiceGrpcKt
import cringle.daemon.v1.EngineProcessState
import cringle.daemon.v1.EngineRequest
import cringle.engine.v1.EngineManagementServiceGrpc
import cringle.engine.v1.GetStatusRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * The release archives of `./gradlew cringleDist` (#57, docs/releasing.md): content and layout of both archives, the
 * manifest with the SHA-256 of every file, and an unpacked archive that really starts, with and without a JDK 21.
 *
 * The test task builds the archives first (see `kotlin/cli/build.gradle.kts`). The archive that matches the platform
 * of the machine is unpacked and started; the other one is read and checked without being started, because its start
 * scripts belong to another operating system.
 */
@Tag("integration")
class DistributionTest {

    @TempDir
    lateinit var temp: Path

    private val distDir: Path = Path.of(property("cringle.distDir"))
    private val version: String = property("cringle.releaseVersion")
    private val top = "cringle-$version"
    private val onWindows = System.getProperty("os.name").lowercase().contains("win")
    private val linuxArchive: Path get() = distDir.resolve("$top-linux.tar.gz")
    private val windowsArchive: Path get() = distDir.resolve("$top-windows.zip")

    private val launchers = mapOf(
        "cringle" to "cringle.cli.MainKt",
        "cringle-daemon" to "cringle.daemon.MainKt",
        "cringle-management-server" to "cringle.management.MainKt",
    )

    // --- manifest ---

    /** AC: `manifest.json` holds the version and, for every file, name, size, SHA-256 and the minimum Java version. */
    @Test
    fun theManifestHasTheCorrectSha256OfEveryFile() {
        val manifest = Json.parseToJsonElement(Files.readString(distDir.resolve("manifest.json"))).jsonObject
        assertEquals(version, manifest.getValue("version").jsonPrimitive.content)
        val files = manifest.getValue("files").jsonArray.map { it.jsonObject }
        assertEquals(listOf("$top-linux.tar.gz", "$top-windows.zip"), files.map { it.getValue("name").jsonPrimitive.content })
        for (entry in files) {
            val name = entry.getValue("name").jsonPrimitive.content
            val file = distDir.resolve(name)
            assertEquals(Files.size(file), entry.getValue("size").jsonPrimitive.content.toLong(), "size of $name")
            assertEquals(sha256(file), entry.getValue("sha256").jsonPrimitive.content, "SHA-256 of $name")
            assertEquals(21, entry.getValue("minJava").jsonPrimitive.content.toInt(), "minJava of $name")
        }
        // SHA256SUMS is the same in the format of `sha256sum -c`
        val sums = Files.readAllLines(distDir.resolve("SHA256SUMS")).filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
        assertEquals(files.associate { it.getValue("name").jsonPrimitive.content to it.getValue("sha256").jsonPrimitive.content }, sums)
    }

    // --- layout of both archives ---

    @Test
    fun theLinuxArchiveHasTheLayoutAndExecutableStartScripts() {
        val entries = tarEntries(linuxArchive).associateBy { it.name }
        assertTrue(entries.keys.all { it.startsWith("$top/") }, "everything is in the directory $top: ${entries.keys.take(5)}")
        for ((command, mainClass) in launchers) {
            val script = entries.getValue("$top/bin/$command")
            assertEquals("755", Integer.toOctalString(script.mode and 511), "$command has to be executable")
            val text = String(script.content!!)
            assertTrue(text.startsWith("#!/bin/sh\n"), "$command starts with the interpreter line")
            assertFalse('\r' in text, "$command must have Unix line ends")
            assertTrue("cringle.home" in text && mainClass in text, "$command starts $mainClass")
            assertFalse("@COMMAND@" in text || "@MAIN_CLASS@" in text, "no placeholder is left in $command")
        }
        assertTrue(entries.keys.none { it.endsWith(".bat") }, "no Windows script in the Linux archive")
        assertEquals("644", Integer.toOctalString(entries.getValue("$top/lib/cli.jar").mode and 511))
        assertLayout(entries.keys.map { it.removePrefix("$top/") })
        assertEquals("$version\n", String(entries.getValue("$top/VERSION").content!!))
    }

    @Test
    fun theWindowsArchiveHasTheLayoutAndBatchScripts() {
        val entries = zipEntries(windowsArchive)
        assertTrue(entries.keys.all { it.startsWith("$top/") }, "everything is in the directory $top: ${entries.keys.take(5)}")
        for ((command, mainClass) in launchers) {
            val text = String(entries.getValue("$top/bin/$command.bat")!!)
            assertTrue(text.startsWith("@echo off\r\n"), "$command.bat starts with @echo off")
            assertFalse(Regex("(?<!\r)\n").containsMatchIn(text), "$command.bat must have Windows line ends")
            assertTrue("cringle.home" in text && mainClass in text, "$command.bat starts $mainClass")
            assertFalse("@COMMAND@" in text || "@MAIN_CLASS@" in text, "no placeholder is left in $command.bat")
        }
        assertTrue(entries.keys.none { it.startsWith("$top/bin/") && !it.endsWith(".bat") && !it.endsWith("/") }, "only batch scripts in bin/")
        assertLayout(entries.keys.map { it.removePrefix("$top/") })
        assertEquals("$version\n", String(entries.getValue("$top/VERSION")!!))
        // the JARs do not depend on the platform: both archives carry the same ones
        val linuxJars = tarEntries(linuxArchive).map { it.name }.filter { it.startsWith("$top/lib/") && !it.endsWith("/") }.toSet()
        assertEquals(linuxJars, entries.keys.filter { it.startsWith("$top/lib/") && !it.endsWith("/") }.toSet())
    }

    /** What both archives hold (paths relative to `cringle-<version>/`): bin, lib, conf, LICENSE, NOTICE, VERSION. */
    private fun assertLayout(paths: List<String>) {
        for (required in listOf("VERSION", "LICENSE", "NOTICE", "conf/README.txt")) assertTrue(required in paths, "$required is missing in $paths")
        val jars = paths.filter { it.startsWith("lib/") && it.endsWith(".jar") }.map { it.removePrefix("lib/") }
        for (module in listOf("cli", "daemon", "management-server", "engine")) assertTrue("$module.jar" in jars, "$module.jar is missing in lib/: $jars")
        for (module in listOf("router", "repository", "common", "contract", "packaging", "schema")) {
            assertTrue(jars.any { it.startsWith("$module-") && it.endsWith(".jar") }, "$module is missing in lib/: $jars")
        }
        assertTrue(jars.any { it.startsWith("grpc-netty-shaded") } && jars.any { it.startsWith("kotlin-stdlib") }, "the runtime libraries are in lib/: $jars")
    }

    /** What must never be shipped: test frameworks, the test helpers of this build and their support libraries. */
    private val testLibraries = Regex("(?i)^(junit|opentest4j|apiguardian|testkit|mockk|mockito|kotlin-test|kotlinx-coroutines-test|assertj|hamcrest)")

    /** `lib/` of both archives holds no test library and no TestKit, and both hold the same JARs. */
    @Test
    fun libHoldsNoTestLibrariesOrTestkit() {
        val linux = tarEntries(linuxArchive).map { it.name }.filter { it.startsWith("$top/lib/") && it.endsWith(".jar") }.map { it.substringAfterLast('/') }.sorted()
        val windows = zipEntries(windowsArchive).keys.filter { it.startsWith("$top/lib/") && it.endsWith(".jar") }.map { it.substringAfterLast('/') }.sorted()
        assertEquals(linux, windows)
        assertTrue(linux.isNotEmpty())
        val found = linux.filter { testLibraries.containsMatchIn(it) }
        assertTrue(found.isEmpty(), "test libraries in lib/: $found")
        // printed so that the pull request can name the list
        println("lib/ of $top (${linux.size} JARs): ${linux.joinToString(", ")}")
    }

    // --- an unpacked archive starts ---

    /** AC: an unpacked archive runs `bin/cringle --version` and prints the version of the archive. */
    @Test
    fun anUnpackedArchiveStartsAndPrintsItsVersion() {
        val home = unpack()
        val version = run(script(home, "cringle"), "--version", environment = javaHome(System.getProperty("java.home")))
        assertEquals(0, version.code, version.error)
        assertEquals("cringle ${this.version}", version.output.trim())

        // the other two programs start as well: without arguments they say what they need and stop with exit code 2
        for (command in listOf("cringle-daemon", "cringle-management-server")) {
            val started = run(script(home, command), "--no-such-option", environment = javaHome(System.getProperty("java.home")))
            assertEquals(2, started.code, "$command: ${started.error}")
            assertTrue("usage:" in started.error, "$command has to print its usage: ${started.error}")
        }
    }

    /**
     * A daemon started from the unpacked archive starts an engine process: the engine classes are on the class path
     * that is built from the JARs in lib, the engine reports RUNNING and answers on its management port.
     */
    @Test
    fun aDaemonFromAnUnpackedArchiveStartsAnEngine() {
        val home = unpack()
        val cringleHome = Files.createDirectories(temp.resolve("cringle-home"))
        val stderrFile = temp.resolve("daemon.err").toFile()
        // the daemon API is mutual TLS: the daemon has to trust this test before it starts, as an operator enters a fingerprint
        val client = DistributionTestClient(temp.resolve("client-tls"))
        client.trustInDaemonHome(cringleHome)
        val builder = ProcessBuilder(script(home, "cringle-daemon").toString(), "--home", cringleHome.toString(), "--port", "0")
        builder.environment().remove("CRINGLE_JVM_OPTS")
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        builder.redirectError(stderrFile)
        val daemon = builder.start()
        var channel: ManagedChannel? = null
        try {
            // the daemon prints `daemon-port=<port>` when it listens
            val line = java.util.concurrent.CompletableFuture.supplyAsync { daemon.inputStream.bufferedReader().readLine() }
                .get(120, TimeUnit.SECONDS)
            assertTrue(line != null && line.startsWith("daemon-port="), "daemon output: $line; stderr: ${stderrFile.readText()}")
            val port = line.substringAfter("=").trim().toInt()
            channel = client.channel(port)
            val api = DaemonServiceGrpcKt.DaemonServiceCoroutineStub(channel)
            val engineId = EngineId.newBuilder().setValue("dist-e1").build()
            runBlocking {
                api.createEngine(CreateEngineRequest.newBuilder().setEngineId("dist-e1").setName("From the archive").build())
                val started = api.startEngine(EngineRequest.newBuilder().setEngineId(engineId).build())
                assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_RUNNING, started.state, "engine logs: ${engineLogs(cringleHome)}")
                assertTrue(started.managementPort > 0 && started.pid > 0)
                val engineChannel = client.channel(started.managementPort)
                try {
                    val status = EngineManagementServiceGrpc.newBlockingStub(engineChannel).getStatus(GetStatusRequest.getDefaultInstance())
                    assertEquals("dist-e1", status.engineId.value)
                    assertEquals("From the archive", status.name)
                } finally {
                    engineChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
                }
                api.stopEngine(EngineRequest.newBuilder().setEngineId(engineId).build())
            }
        } finally {
            channel?.shutdownNow()
            // on Windows the start script is a cmd.exe whose child is the JVM: end the whole tree
            daemon.descendants().forEach { it.destroyForcibly() }
            daemon.destroyForcibly()
            daemon.waitFor(30, TimeUnit.SECONDS)
        }
    }

    private fun engineLogs(cringleHome: Path): String = runCatching {
        Files.walk(cringleHome).use { s -> s.filter { it.toString().endsWith(".log") }.toList().joinToString("\n") { "${it.fileName}:\n${Files.readString(it)}" } }
    }.getOrDefault("(none)")

    /**
     * AC: without a JDK 21 every start script stops with exit code 1 and a message that names the requirement, for a
     * Java that is too old, for one whose version cannot be read and for a `JAVA_HOME` without a Java.
     */
    @Test
    fun startWithoutAJdk21StopsWithAMessageAndExitCode1() {
        val home = unpack()
        val cases = listOf(
            Triple("17.0.11", """openjdk version "17.0.11" 2024-04-16""", "Java 17.0.11"),
            Triple("8", """java version "1.8.0_392"""", "Java 1.8.0_392"),
            Triple("unreadable", "this is not a java", "cannot tell the Java version"),
        )
        for ((name, line, expected) in cases) {
            val jdk = fakeJdk(name, line)
            for (command in launchers.keys) {
                val result = run(script(home, command), "--version", environment = javaHome(jdk.toString()))
                assertEquals(1, result.code, "$command with a Java $name: ${result.output} ${result.error}")
                assertTrue("JDK 21" in result.error, "the message has to say what is needed: ${result.error}")
                assertTrue(expected in result.error, "the message has to say what was found ($expected): ${result.error}")
                assertEquals("", result.output.trim(), "nothing on standard output")
            }
        }
        val missing = run(script(home, "cringle"), "--version", environment = javaHome(temp.resolve("no-such-jdk").toString()))
        assertEquals(1, missing.code, missing.error)
        assertTrue("no Java found" in missing.error && "JDK 21" in missing.error, missing.error)
    }

    /**
     * The Windows start scripts use the Java runtime in `jre\` of the installation before `JAVA_HOME` and the `PATH`
     * (#155): with a `jre` folder, an invalid `JAVA_HOME` and no `java` on the `PATH`, `cringle --version` still works.
     * `jre` is a junction to the running JDK (it needs no privilege, unlike a symbolic link).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun theWindowsStartScriptPrefersTheJreOfTheInstallation() {
        val home = unpack()
        val junction = ProcessBuilder("cmd", "/c", "mklink", "/J", home.resolve("jre").toString(), System.getProperty("java.home")).redirectErrorStream(true).start()
        val made = junction.inputStream.bufferedReader().readText()
        assertTrue(junction.waitFor(60, TimeUnit.SECONDS) && junction.exitValue() == 0, "mklink failed: $made")
        val system32 = System.getenv("SystemRoot") + "\\System32"
        val environment = mapOf("JAVA_HOME" to temp.resolve("no-such-jdk").toString(), "PATH" to system32)
        val version = run(script(home, "cringle"), "--version", environment = environment)
        assertEquals(0, version.code, version.error)
        assertEquals("cringle ${this.version}", version.output.trim())
    }

    /**
     * The tree that `./gradlew cringleWindowsRuntime` stages for the MSI (#155): the Windows distribution plus `jre/` and
     * `THIRD-PARTY.txt`. The task downloads the JRE and the tests never do, so this test only runs if the task ran before.
     */
    @Test
    fun theWindowsRuntimeStageHasTheJreAndTheThirdPartyNotice() {
        val stage = distDir.parent.resolve("dist-stage/windows-jre").resolve(top)
        assumeTrue(Files.isDirectory(stage), "run ./gradlew cringleWindowsRuntime -PreleaseVersion=$version first: $stage does not exist")
        assertTrue(Files.isRegularFile(stage.resolve("jre/bin/java.exe")), "jre/bin/java.exe")
        assertTrue(Files.isDirectory(stage.resolve("jre/legal")), "jre/legal")
        assertTrue(Files.isRegularFile(stage.resolve("jre/release")), "jre/release")
        val notice = Files.readString(stage.resolve("THIRD-PARTY.txt"))
        assertTrue("Eclipse Temurin" in notice && "Classpath Exception" in notice && "jre/legal/" in notice, notice)
        assertEquals("$version\n", Files.readString(stage.resolve("VERSION")))
        for (command in launchers.keys) {
            assertTrue(Files.isRegularFile(stage.resolve("bin/$command.bat")), "bin/$command.bat")
            assertTrue("%APP_HOME%\\jre\\bin\\java.exe" in Files.readString(stage.resolve("bin/$command.bat")), "$command.bat has to look for the jre first")
        }
        assertTrue(Files.list(stage.resolve("lib")).use { files -> files.anyMatch { it.fileName.toString().startsWith("cli") } }, "lib/ has the JARs of the distribution")
    }

    /** A directory that looks like a JDK for the start script: `bin/java` that only prints [versionLine] as `java -version` does. */
    private fun fakeJdk(name: String, versionLine: String): Path {
        val home = temp.resolve("jdk-$name")
        val bin = Files.createDirectories(home.resolve("bin"))
        if (onWindows) {
            Files.writeString(bin.resolve("java.cmd"), "@echo off\r\necho $versionLine 1>&2\r\n")
        } else {
            val java = bin.resolve("java")
            Files.writeString(java, "#!/bin/sh\necho '$versionLine' >&2\n")
            check(java.toFile().setExecutable(true)) { "cannot make $java executable" }
        }
        return home
    }

    private fun javaHome(path: String): Map<String, String> = mapOf("JAVA_HOME" to path)

    private fun script(home: Path, command: String): Path = home.resolve("bin").resolve(if (onWindows) "$command.bat" else command)

    /** Unpacks the archive of this platform into the temporary directory and returns `cringle-<version>`. */
    private fun unpack(): Path {
        val target = Files.createDirectories(temp.resolve("unpacked"))
        if (onWindows) {
            ZipFile(windowsArchive.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    val path = target.resolve(entry.name)
                    if (entry.isDirectory) {
                        Files.createDirectories(path)
                    } else {
                        Files.createDirectories(path.parent)
                        zip.getInputStream(entry).use { Files.copy(it, path) }
                    }
                }
            }
        } else {
            val tar = ProcessBuilder("tar", "-xzf", linuxArchive.toString(), "-C", target.toString()).redirectErrorStream(true).start()
            val output = tar.inputStream.bufferedReader().readText()
            assertTrue(tar.waitFor(120, TimeUnit.SECONDS) && tar.exitValue() == 0, "tar failed: $output")
        }
        return target.resolve(top)
    }

    private class Result(val code: Int, val output: String, val error: String)

    private fun run(script: Path, vararg arguments: String, environment: Map<String, String>): Result {
        val builder = ProcessBuilder(listOf(script.toString()) + arguments)
        builder.environment().remove("CRINGLE_JVM_OPTS")
        builder.environment().putAll(environment)
        val process = builder.start()
        val error = StringBuilder()
        val errorReader = Thread { process.errorStream.bufferedReader().use { error.append(it.readText()) } }.apply { start() }
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "${script.fileName} did not end within 120 seconds" }
        errorReader.join()
        return Result(process.exitValue(), output, error.toString())
    }

    // --- reading the archives ---

    private class TarEntry(val name: String, val mode: Int, val content: ByteArray?)

    /** Reads a `.tar.gz` (ustar, with GNU long names); the content of files up to 1 MB is kept, of bigger ones not. */
    private fun tarEntries(file: Path): List<TarEntry> {
        val entries = ArrayList<TarEntry>()
        GZIPInputStream(Files.newInputStream(file)).use { input ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (input.readNBytes(header, 0, 512) < 512 || header.all { it == 0.toByte() }) break
                val name = text(header, 0, 100)
                val mode = text(header, 100, 8).ifEmpty { "0" }.toInt(8)
                val size = text(header, 124, 12).ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val prefix = if (text(header, 257, 5) == "ustar") text(header, 345, 155) else ""
                val content = if (size <= 1_000_000) input.readNBytes(size.toInt()) else null.also { input.skipNBytes(size) }
                input.skipNBytes((512 - size % 512) % 512)
                if (type == 'L') {
                    longName = String(content ?: ByteArray(0)).trimEnd('\u0000')
                    continue
                }
                val fullName = longName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
                longName = null
                entries += TarEntry(fullName, mode, content)
            }
        }
        return entries
    }

    private fun text(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.ISO_8859_1).trim { it == ' ' || it == '\u0000' }

    /** The entries of a `.zip` with their content (`null` for directories). */
    private fun zipEntries(file: Path): Map<String, ByteArray?> = ZipFile(file.toFile()).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to if (entry.isDirectory) null else zip.getInputStream(entry).use(InputStream::readAllBytes) }
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

    private fun property(name: String): String = checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }
}
