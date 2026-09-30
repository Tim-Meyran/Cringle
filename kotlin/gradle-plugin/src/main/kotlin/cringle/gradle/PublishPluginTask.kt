// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageHash
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import cringle.packaging.PluginManifest
import cringle.repository.RepositoryClient
import cringle.repository.RepositoryClientException
import io.grpc.Status
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * Publishes the plugin package to a Cringle repository. It builds the package with `cringlePackage` and uploads it
 * unchanged with `RepositoryClient`, so the version and the content of the package are the ones of the build and the
 * repository decides whether it takes them.
 *
 * The address of the repository comes from the task (`-Pcringle.server=host:port` or
 * `cringle { publish { server = … } }`), from `CRINGLE_SERVER` or from the profile `cringle login` wrote. The token
 * comes from `CRINGLE_TOKEN` or from that profile and from nowhere else: it is never a property, so it cannot end up
 * in a build script, in the task graph or in a log.
 *
 * `--dryRun` builds and validates the package, then says what it would publish without opening a connection. Gradle
 * reserves `--dry-run` for the dry run of the whole build, which runs no task at all, so the option of the task cannot
 * be spelled that way.
 */
public abstract class PublishPluginTask : DefaultTask() {

    /** The package to publish, the file `cringlePackage` writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val packageFile: RegularFileProperty

    /** The address of the repository as `host:port`; without it the environment and the profile are asked. */
    @get:Input
    @get:Optional
    public abstract val server: Property<String>

    /** Whether the task only reports what it would publish and connects to nothing. */
    @get:Input
    @get:Option(option = "dryRun", description = "Reports what would be published and connects to nothing.")
    public abstract val dryRun: Property<Boolean>

    @TaskAction
    internal fun publishPackage() {
        val file = packageFile.get().asFile.toPath()
        val manifest = read(file)
        val environment = System.getenv()
        val target = PublishSettings.resolve(server.orNull, environment, CliProfile.load(PublishSettings.home(environment)))
        if (dryRun.getOrElse(false)) {
            logger.lifecycle(
                "cringlePublish: would publish {} {} to {}; --dryRun sent nothing",
                manifest.name,
                manifest.version,
                target.server,
            )
            return
        }
        val entry = upload(file, target, manifest)
        logger.lifecycle("cringlePublish: published {} {} (SHA-256 {}) to {}", entry.name, entry.version, entry.sha256, target.server)
    }

    /**
     * Reads the package back and validates it, so that a package the repository would reject never leaves the build.
     * The text of a finding is the one of the `packaging` module, as `cringleValidate` writes it.
     */
    private fun read(file: Path) = try {
        val pkg = PackageReader.readPlugin(file)
        val problems = PackageValidator.validatePlugin(pkg)
        if (problems.isNotEmpty()) {
            throw GradleException("cringlePublish found ${problems.size} problem(s):\n" + problems.joinToString("\n") { ProblemRender.text(it) })
        }
        pkg.manifest
    } catch (e: PackageFormatException) {
        throw GradleException("cringlePublish cannot read $file: ${ProblemRender.text(e)}", e)
    }

    /** Uploads [file] and checks that the repository stored the bytes that were sent. */
    private fun upload(file: Path, target: PublishTarget, manifest: PluginManifest) = RepositoryClient(target.server, target.token).use { client ->
        val expected = PackageHash.sha256(file)
        val entry = try {
            runBlocking { client.publish(file) }
        } catch (e: RepositoryClientException) {
            throw GradleException(refusal(target, manifest, e))
        }
        if (entry.sha256 != expected) {
            throw GradleException(
                "cringlePublish: the repository at ${target.server} stored SHA-256 ${entry.sha256} of ${entry.name} " +
                    "${entry.version}, but the package that was sent has $expected",
            )
        }
        entry
    }

    /**
     * What a refused or unreachable call means for the build, as one line: a version that exists, a repository that
     * wants a token, a token that may not publish, a server that is not there. The token is in none of them, because
     * none of them knows it.
     */
    private fun refusal(target: PublishTarget, manifest: PluginManifest, e: RepositoryClientException): String {
        val pkg = "${manifest.name} ${manifest.version}"
        return when (e.status) {
            Status.Code.ALREADY_EXISTS ->
                "cringlePublish: $pkg is already published at ${target.server}; a version of a package never changes, " +
                    "so set a higher cringle { version }."
            Status.Code.UNAUTHENTICATED ->
                "cringlePublish: ${target.server} wants a token to publish $pkg: set ${PublishSettings.TOKEN_VARIABLE} " +
                    "or run 'cringle login --server ${target.server}'."
            Status.Code.PERMISSION_DENIED ->
                "cringlePublish: the token may not publish $pkg to ${target.server}: publishing needs the right to " +
                    "operate (Permission.OPERATE)."
            Status.Code.UNAVAILABLE ->
                "cringlePublish: ${target.server} is not reachable: ${e.message}"
            else -> {
                val detail = e.message?.takeIf { it != e.status.name }.orEmpty()
                "cringlePublish: ${target.server} refused $pkg with ${e.status}" + if (detail.isEmpty()) "" else ": $detail"
            }
        }
    }
}
