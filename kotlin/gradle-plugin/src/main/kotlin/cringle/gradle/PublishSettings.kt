// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.gradle.api.GradleException

/**
 * The repository `cringlePublish` publishes to: the address of the server and the token, if the repository asks for
 * one, and the fingerprint of the repository's key that the connection is pinned to. The token is a secret, so it is never part of
 * [PublishSettings.resolve]'s first argument and never printed.
 */
internal data class PublishTarget(val server: String, val token: String?, val fingerprint: String? = null)

/**
 * The connection profile `<home>/cli.json` that `cringle login` writes: the address of the server and the token. It is
 * the same file with the same fields the CLI reads, so one `cringle login` serves the CLI and a Gradle build.
 */
internal data class CliProfile(val server: String? = null, val token: String? = null, val fingerprint: String? = null) {

    companion object {

        /** The name of the profile file in the Cringle home. */
        const val FILE_NAME: String = "cli.json"

        /** The profile in [home]; a home without that file holds no profile, which is not an error. */
        fun load(home: Path): CliProfile? {
            val file = home.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return null
            val json = try {
                Json.parseToJsonElement(Files.readString(file)) as JsonObject
            } catch (e: Exception) {
                throw GradleException("cringlePublish: $file is not a valid profile: ${e.message}", e)
            }
            fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
            return CliProfile(text("server"), text("token"), text("fingerprint"))
        }
    }
}

/**
 * Where `cringlePublish` takes the address of the repository and the token from, in the order the CLI takes them: the
 * address from the task, then from the environment, then from the profile of `cringle login`. The token has no
 * property and no field in `cringle { publish { } }`, because a build script is not a place for a secret.
 */
internal object PublishSettings {

    /** Environment variable with the address of the repository. */
    const val SERVER_VARIABLE: String = "CRINGLE_SERVER"

    /** Environment variable with the token; the only place besides the profile where a token comes from. */
    const val TOKEN_VARIABLE: String = "CRINGLE_TOKEN"

    /** Environment variable with the fingerprint of the repository key (SHA-256, lowercase hex). */
    const val FINGERPRINT_VARIABLE: String = "CRINGLE_FINGERPRINT"

    /** Environment variable with the Cringle home the profile is read from. */
    const val HOME_VARIABLE: String = "CRINGLE_HOME"

    /** The Cringle home: [HOME_VARIABLE], or `~/.cringle`. */
    fun home(environment: Map<String, String>): Path =
        environment[HOME_VARIABLE]?.takeIf { it.isNotBlank() }?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), ".cringle")

    /**
     * The repository to publish to. [server] is what the task was given, either as `-Pcringle.server=host:port` or
     * through `cringle { publish { server = … } }`; [environment] and [profile] are the two places behind it. A
     * repository without a token is normal, so a missing token is no error here: a repository that wants one answers
     * with `UNAUTHENTICATED`, and the task turns that into a message that names both sources.
     */
    fun resolve(server: String?, environment: Map<String, String>, profile: CliProfile?, fingerprint: String? = null): PublishTarget =
        find(server, environment, profile, fingerprint) ?: throw GradleException(
            "cringlePublish: no repository address: use -Pcringle.server=host:port, " +
                "cringle { publish { server = \"host:port\" } }, $SERVER_VARIABLE " +
                "or 'cringle login --server host:port'",
        )

    /**
     * The same as [resolve], but `null` if no address is configured anywhere: `cringleValidate` uses the repository to
     * look up dependencies when it can and goes on without it when it cannot, where `cringlePublish` has to fail.
     */
    fun find(server: String?, environment: Map<String, String>, profile: CliProfile?, fingerprint: String? = null): PublishTarget? {
        val address = server?.takeIf { it.isNotBlank() }
            ?: environment[SERVER_VARIABLE]?.takeIf { it.isNotBlank() }
            ?: profile?.server?.takeIf { it.isNotBlank() }
            ?: return null
        val token = environment[TOKEN_VARIABLE]?.takeIf { it.isNotBlank() }
            ?: profile?.token?.takeIf { it.isNotBlank() }
        val pinned = fingerprint?.takeIf { it.isNotBlank() }
            ?: environment[FINGERPRINT_VARIABLE]?.takeIf { it.isNotBlank() }
            ?: profile?.fingerprint?.takeIf { it.isNotBlank() }
        return PublishTarget(address, token, pinned)
    }

    /**
     * The message of a task that has to connect and has no repository fingerprint. TLS needs it: trust is explicit and
     * never decided on first use (Architecture 5.1), so the task names all four places it can come from.
     */
    fun missingFingerprint(task: String): String =
        "$task: no repository fingerprint: the connection to the repository is TLS and pinned to the SHA-256 fingerprint of its key. " +
            "Use -Pcringle.fingerprint=<sha256>, cringle { publish { fingerprint = \"<sha256>\" } }, $FINGERPRINT_VARIABLE " +
            "or the \"fingerprint\" field of ${CliProfile.FILE_NAME} in the Cringle home."
}
