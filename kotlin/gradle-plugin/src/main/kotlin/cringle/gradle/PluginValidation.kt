// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageHashMismatchException
import cringle.packaging.PackageKind
import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import cringle.packaging.PluginPackage
import cringle.packaging.ResolutionException
import cringle.packaging.Resolver
import cringle.repository.RepositoryClient
import cringle.repository.RepositoryClientException
import io.grpc.Status
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.GradleException

/** What [PluginValidation.validate] found: the [problems] that fail a build and the [warnings] that do not. */
internal class PluginValidationResult(val problems: List<PackageProblem>, val warnings: List<String>)

/**
 * Validates a plugin package the way the repository does at publish: against the plugins it depends on. The schemas of
 * a dependency are only known from its package, so the build asks the repository for them (with the address and the
 * token `cringlePublish` uses). The package itself resolves no Cringle dependency and locks nothing; that stays with the
 * deploy. The repository is only a source for this check.
 *
 * Without a repository, because none is configured or because it cannot be reached or does not answer, the build goes
 * on with a warning and checks what it can. A reference to a schema in a namespace that is not the plugin's own is then
 * taken as coming from the declared dependencies (the repository checks it at publish); the own namespaces are those of
 * the schema documents of the plugin and the one its name spells (`acme-orders` has `acme.orders`), and a reference into
 * them that does not resolve is an error offline as well. A plugin that declares no dependency at all cannot refer to a
 * schema outside itself, so such a reference fails and the message says which dependency is missing.
 */
internal object PluginValidation {

    /** The two wordings of an unresolvable schema reference, in a block definition and inside a schema document. */
    private val UNRESOLVED = Regex("""^(?:schema '([^']+)' does not resolve|unresolved reference '([^']+)')$""")

    /**
     * Validates [plugin]. [server] is the address the task was given; the environment and the profile of `cringle login`
     * are asked behind it, in the order of `cringlePublish`. With [mayConnect] `false` (`--dryRun`) the repository is not
     * contacted.
     */
    fun validate(plugin: PluginPackage, server: String?, mayConnect: Boolean = true): PluginValidationResult {
        val declared = plugin.manifest.dependencies
        if (declared.isEmpty()) return PluginValidationResult(withMissingDependencyHint(PackageValidator.validatePlugin(plugin)), emptyList())
        if (!mayConnect) return withoutRepository(plugin, "--dryRun connects to nothing")

        val environment = System.getenv()
        val profile = try {
            CliProfile.load(PublishSettings.home(environment))
        } catch (e: GradleException) {
            return withoutRepository(plugin, e.message.orEmpty().removePrefix("cringlePublish: "))
        }
        val target = PublishSettings.find(server, environment, profile)
            ?: return withoutRepository(
                plugin,
                "no repository is configured (use -Pcringle.server=host:port, cringle { publish { server = \"host:port\" } }, " +
                    "${PublishSettings.SERVER_VARIABLE} or 'cringle login --server host:port')",
            )
        val dependencies = try {
            fetch(target, declared)
        } catch (e: Unavailable) {
            return withoutRepository(plugin, e.message.orEmpty())
        }
        return PluginValidationResult(PackageValidator.validatePlugin(plugin, dependencies), emptyList())
    }

    /** The plugins [declared] resolves to in the repository of [target], read from their packages. */
    private fun fetch(target: PublishTarget, declared: Map<String, String>): List<PluginPackage> = RepositoryClient(target.server, target.token).use { client ->
        try {
            val resolution = Resolver.resolve(declared, client)
            resolution.packages.values.mapNotNull { info ->
                val version = info.version.toString()
                if (runBlocking { client.get(info.name, version) }.kind != PackageKind.PLUGIN) return@mapNotNull null
                val file = Files.createTempFile("cringle-dependency", ".cringle")
                try {
                    runBlocking { client.download(info.name, version, file) }
                    PackageReader.readPlugin(file)
                } finally {
                    Files.deleteIfExists(file)
                }
            }
        } catch (e: ResolutionException) {
            throw Unavailable("the dependencies cannot be resolved in ${target.server}: ${e.message}")
        } catch (e: RepositoryClientException) {
            throw Unavailable(describe(target, e))
        } catch (e: PackageHashMismatchException) {
            throw Unavailable("a dependency from ${target.server} does not match its hash: ${e.message}")
        } catch (e: PackageFormatException) {
            throw Unavailable("a dependency from ${target.server} is not a readable plugin package: ${ProblemRender.text(e)}")
        } catch (e: IOException) {
            throw Unavailable("a dependency from ${target.server} cannot be read: ${e.message}")
        }
    }

    private fun describe(target: PublishTarget, e: RepositoryClientException): String = when (e.status) {
        Status.Code.UNAVAILABLE -> "${target.server} is not reachable: ${e.message}"
        Status.Code.UNAUTHENTICATED ->
            "${target.server} wants a token to read the dependencies: set ${PublishSettings.TOKEN_VARIABLE} or run 'cringle login --server ${target.server}'"
        Status.Code.PERMISSION_DENIED -> "the token may not read from ${target.server}"
        else -> "${target.server} answered ${e.status}: ${e.message}"
    }

    /**
     * The validation without the dependencies: what cannot be checked without their schemas is taken as theirs and
     * named in a warning, so a build can go on offline and the repository does the full check at publish.
     */
    private fun withoutRepository(plugin: PluginPackage, reason: String): PluginValidationResult {
        val problems = PackageValidator.validatePlugin(plugin)
        val own = ownNamespaces(plugin)
        val external = problems.filter { problem -> namespaceOf(problem)?.let { it !in own } == true }
        val names = plugin.manifest.dependencies.keys.sorted().joinToString(", ")
        val warning = buildString {
            append("cringleValidate: $reason. The plugin was validated without its dependencies ($names)")
            if (external.isNotEmpty()) {
                append("; ${external.size} reference(s) to schemas outside this plugin are taken as coming from them and are checked when the package is published: ")
                append(external.map { it.message }.distinct().joinToString(", "))
            }
            append('.')
        }
        return PluginValidationResult(problems - external.toSet(), listOf(warning))
    }

    /** The namespaces the plugin itself defines: those of its schema documents, and the one its name spells. */
    private fun ownNamespaces(plugin: PluginPackage): Set<String> {
        val documents = plugin.schemas.values.mapNotNull { text ->
            try {
                (Json.parseToJsonElement(text) as? JsonObject)?.get("namespace")?.jsonPrimitive?.contentOrNull
            } catch (_: SerializationException) {
                null
            }
        }
        return (documents + plugin.manifest.name.replace('-', '.')).toSet()
    }

    /** The namespace of a schema reference that does not resolve, or `null` for any other finding. */
    private fun namespaceOf(problem: PackageProblem): String? =
        UNRESOLVED.matchEntire(problem.message)?.let { (it.groups[1] ?: it.groups[2])!!.value.substringBefore('/') }

    /** A plugin without dependencies can only refer to its own schemas, so an unresolved one names what is missing. */
    private fun withMissingDependencyHint(problems: List<PackageProblem>): List<PackageProblem> = problems.map { problem ->
        val namespace = namespaceOf(problem) ?: return@map problem
        PackageProblem(
            problem.path,
            "${problem.message}: the plugin declares no dependency that could provide the namespace '$namespace'; " +
                "declare the plugin that holds it with cringle { dependency(\"<plugin>\", \"<range>\") }",
        )
    }

    /** The repository did not give the dependencies; the reason is the text of the warning. */
    private class Unavailable(message: String) : RuntimeException(message)
}
