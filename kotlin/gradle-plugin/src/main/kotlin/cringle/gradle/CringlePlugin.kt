// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.ManifestJson
import cringle.packaging.PluginManifest
import cringle.packaging.ProcessorSet
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.concurrent.Callable

/**
 * The `cringle.plugin` plugin. It adds the `cringle { }` extension and the tasks `cringlePackage`, `cringleValidate`
 * and `cringlePublish`, which turn a normal Kotlin project into a Cringle plugin package (chapter 8.5) and publish it
 * to a repository.
 *
 * At runtime the `contract` module comes from the parent classloader, so a project adds it as `compileOnly`
 * dependency and it never becomes part of the package.
 */
public class CringlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.plugins.hasPlugin(CringleProjectPlugin::class.java)) throw GradleException(CringleProjectPlugin.CONFLICT)
        project.pluginManager.apply(JavaPlugin::class.java)

        val extension = project.extensions.create(EXTENSION, CringlePluginExtension::class.java)
        extension.name.convention(project.name)
        extension.version.convention(project.provider { project.version.toString() })
        extension.schemas.convention(project.layout.projectDirectory.dir(DEFAULT_SCHEMAS))
        extension.binaries.convention(project.layout.projectDirectory.dir(DEFAULT_BINARIES))

        // Two builds of the same sources must produce the same JAR, so no file timestamps and a fixed entry order.
        project.tasks.withType(Jar::class.java).configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }

        val jar = project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java)
        val manifest = { manifestOf(project, extension) }

        val validate = project.tasks.register(TASK_VALIDATE, ValidatePluginTask::class.java)
        val packagePlugin = project.tasks.register(TASK_PACKAGE, PackagePluginTask::class.java)

        validate.configure {
            common(project, extension, jar, manifest)
            group = TASK_GROUP
            description = "Validates the Cringle plugin package without writing it."
        }
        packagePlugin.configure {
            common(project, extension, jar, manifest)
            group = TASK_GROUP
            description = "Builds the Cringle plugin package."
            packageFile.set(packageFile(project, manifest))
        }

        project.tasks.register(TASK_PUBLISH, PublishPluginTask::class.java).configure {
            dependsOn(packagePlugin)
            packageFile.set(packagePlugin.flatMap { it.packageFile })
            // A property of the command line wins over the block of the build script, and both win over the
            // environment and the profile, which the task itself resolves.
            server.set(project.providers.gradleProperty(PROPERTY_SERVER).orElse(extension.publishSettings.server))
            dryRun.convention(false)
            group = TASK_GROUP
            description = "Publishes the Cringle plugin package to a repository."
        }
    }

    /** The wiring both package tasks share. */
    private fun CringlePluginPackageTask.common(
        project: Project,
        extension: CringlePluginExtension,
        jar: TaskProvider<Jar>,
        manifest: () -> PluginManifest,
    ) {
        dependsOn(jar)
        manifestJson.set(project.provider { ManifestJson.encode(manifest()) })
        projectJar.set(jar.flatMap { it.archiveFile })
        runtimeJars.from(shippableRuntimeJars(project))
        schemaFiles.from(Callable { schemaFiles(extension) })
        binariesDir.set(extension.binaries)
        binaryFiles.from(Callable { project.fileTree(extension.binaries.get().asFile) })
    }

    /** The manifest the `cringle { }` block describes, without the entries that depend on the files. */
    private fun manifestOf(project: Project, extension: CringlePluginExtension): PluginManifest {
        val version = extension.version.get()
        if (version == Project.DEFAULT_VERSION) {
            throw GradleException(
                "cringle: the project has no version, so none can go into the package. " +
                    "Set `version` or `cringle.version` in build.gradle.kts.",
            )
        }
        return PluginManifest(
            name = extension.name.get(),
            version = version,
            dependencies = LinkedHashMap(extension.dependencies.get()),
            providers = extension.providers.get(),
            drivers = extension.drivers.get(),
            blocks = extension.blocks,
            processors = ProcessorSet(),
        )
    }

    private fun packageFile(project: Project, manifest: () -> PluginManifest) =
        project.layout.buildDirectory.dir("distributions")
            .flatMap { dir -> dir.file(project.provider { "${manifest().name}-${manifest().version}.cringle" }) }

    /**
     * The JARs of the runtime classpath that the plugin may ship. What the parent classloader provides stays out:
     * the `contract` module, the Kotlin standard library, coroutines and serialization, and the Gradle API with
     * the local Groovy. Everything else the project depends on is part of the plugin, because the plugin's class
     * loader does not see it.
     */
    private fun shippableRuntimeJars(project: Project): FileCollection =
        project.configurations.getByName("runtimeClasspath").incoming.artifactView {
            componentFilter(Spec<ComponentIdentifier> { id -> !providedByParentClassloader(id) })
        }.files

    private fun providedByParentClassloader(id: ComponentIdentifier): Boolean = when (id) {
        is ModuleComponentIdentifier -> when {
            id.group == CRINGLE_GROUP && id.module == "contract" -> true
            id.group == KOTLIN_GROUP && id.module.startsWith("kotlin-stdlib") -> true
            id.group == KOTLINX_GROUP && id.module.startsWith("kotlinx-coroutines") -> true
            id.group == KOTLINX_GROUP && id.module.startsWith("kotlinx-serialization") -> true
            else -> false
        }
        // Project and file dependencies are not excluded: they are ordinary content of the plugin.
        else -> id.displayName in GRADLE_API
    }

    // The `cringle { }` block that may replace the folder runs after apply(), so the folder is read lazily.
    private fun schemaFiles(extension: CringlePluginExtension): List<File> =
        extension.schemas.get().asFile
            .listFiles { file: File -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    public companion object {
        /** Name of the extension: `cringle { }`. */
        public const val EXTENSION: String = "cringle"

        /** Name of the task that validates the package without writing it. */
        public const val TASK_VALIDATE: String = "cringleValidate"

        /** Name of the task that writes the package. */
        public const val TASK_PACKAGE: String = "cringlePackage"

        /** Name of the task that publishes the package to a repository. */
        public const val TASK_PUBLISH: String = "cringlePublish"

        /** The project property that names the repository, as `-Pcringle.server=host:port`. */
        public const val PROPERTY_SERVER: String = "cringle.server"

        /** The task group both tasks appear under. */
        public const val TASK_GROUP: String = "cringle"

        /** The schemas folder, if the project names no other one. */
        public const val DEFAULT_SCHEMAS: String = "src/main/cringle/schemas"

        /** The binaries folder, if the project names no other one. */
        public const val DEFAULT_BINARIES: String = "src/main/cringle/binaries"

        private const val CRINGLE_GROUP = "cringle"
        private const val KOTLIN_GROUP = "org.jetbrains.kotlin"
        private const val KOTLINX_GROUP = "org.jetbrains.kotlinx"

        private val GRADLE_API = setOf("gradle-api", "localGroovy")
    }
}
