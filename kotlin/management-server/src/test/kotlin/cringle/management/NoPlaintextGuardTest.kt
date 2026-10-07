// SPDX-License-Identifier: Apache-2.0

package cringle.management

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guard of #7 (mTLS Teil 3): the classes that open the channels of the management server, the repository, the
 * daemon and the engine never call `usePlaintext` again. A channel is TLS or it does not exist.
 *
 * The files are found from the working directory of the test upwards (the repository root holds `settings.gradle.kts`),
 * so the test works from every checkout and worktree.
 */
class NoPlaintextGuardTest {

    private val guarded = listOf(
        "kotlin/management-server/src/main/kotlin/cringle/management/ManagementCore.kt",
        "kotlin/repository/src/main/kotlin/cringle/repository/RepositoryServer.kt",
        "kotlin/repository/src/main/kotlin/cringle/repository/RepositoryClient.kt",
        "kotlin/daemon/src/main/kotlin/cringle/daemon/Daemon.kt",
        "kotlin/daemon/src/main/kotlin/cringle/daemon/EngineSupervisor.kt",
        "kotlin/engine/src/main/kotlin/cringle/engine/Engine.kt",
        "kotlin/engine/src/main/kotlin/cringle/engine/RepositoryFetcher.kt",
        "kotlin/engine/src/main/kotlin/cringle/engine/RegistryLink.kt",
        "kotlin/cli/src/main/kotlin/cringle/cli/Connection.kt",
    )

    private fun root(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.parent
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }

    /** Code that calls it, not a comment or a string that only names it. */
    private fun callsUsePlaintext(source: String): Boolean = source.lineSequence()
        .map { it.substringBefore("//") }
        .any { Regex("""\.usePlaintext\s*\(""").containsMatchIn(it) }

    @Test
    fun noGuardedClassOpensAPlaintextChannel() {
        val root = root()
        for (file in guarded) {
            val path = root.resolve(file)
            assertTrue(Files.isRegularFile(path), "the guarded file $file does not exist any more: update the guard")
            assertFalse(callsUsePlaintext(Files.readString(path)), "$file calls usePlaintext(): every channel of this class has to be mutual TLS")
        }
    }

    /** The switch is gone from every start script, installer and main source: no component knows an unencrypted mode. */
    @Test
    fun noComponentOrInstallerKnowsTheInsecureSwitchAnyMore() {
        val root = root()
        val places = listOf("kotlin", "installer").map { root.resolve(it) }.filter { Files.isDirectory(it) }
        val switch = Regex("insecure[-_ ]?dev[-_ ]?mode|INSECURE_DEV_MODE|insecureDevMode", RegexOption.IGNORE_CASE)
        val extensions = listOf(".kt", ".kts", ".sh", ".ps1")
        val offenders = places.flatMap { place ->
            Files.walk(place).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { path ->
                        val text = path.toString().replace(File.separatorChar, '/')
                        "/build/" !in text && "/src/test/" !in text && "/.gradle/" !in text && extensions.any { text.endsWith(it) }
                    }
                    .filter { switch.containsMatchIn(Files.readString(it)) }
                    .map { root.relativize(it).toString() }
                    .toList()
            }
        }
        assertTrue(offenders.isEmpty(), "the insecure switch is back in: $offenders")
    }

    @Test
    fun theGuardSeesACallAndIgnoresAComment() {
        assertTrue(callsUsePlaintext("val c = NettyChannelBuilder.forTarget(a).usePlaintext().build()"))
        assertTrue(callsUsePlaintext("builder\n    .usePlaintext ()\n"))
        assertFalse(callsUsePlaintext("// the channel is not usePlaintext() any more"))
        assertFalse(callsUsePlaintext("val s = \"no plaintext\""))
    }
}
