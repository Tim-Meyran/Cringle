// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.ManifestJson
import cringle.packaging.ProjectManifest
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.util.concurrent.Callable

/**
 * The `cringle.project` plugin. It adds the `cringle { }` extension and the tasks `cringlePackage`, `cringleValidate`
 * and `cringlePublish`, which turn a normal Gradle project into a Cringle project package (chapter 8.5): blueprints,
 * fabric configs, schemas and binaries, and publish it to a repository.
 *
 * A project package contains no code, so this plugin adds no dependency to the build and needs no Java plugin. The
 * Cringle dependencies a project declares are resolved at deploy time against the repository (chapter 8.4), not by
 * the build; only the metadata of the project itself is written here.
 */
public class CringleProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.plugins.hasPlugin(CringlePlugin::class.java)) throw GradleException(CONFLICT)

        val extension = project.extensions.create(EXTENSION, CringleProjectExtension::class.java)
        extension.name.convention(project.name)
        extension.version.convention(project.provider { project.version.toString() })
        extension.blueprints.convention(project.layout.projectDirectory.dir(DEFAULT_BLUEPRINTS))
        extension.schemas.convention(project.layout.projectDirectory.dir(DEFAULT_SCHEMAS))
        extension.binaries.convention(project.layout.projectDirectory.dir(DEFAULT_BINARIES))

        val manifest = { manifestOf(extension) }

        val validate = project.tasks.register(TASK_VALIDATE, ValidateProjectTask::class.java)
        val packageProject = project.tasks.register(TASK_PACKAGE, PackageProjectTask::class.java)

        validate.configure {
            common(project, extension, manifest)
            group = TASK_GROUP
            description = "Validates the Cringle project package without writing it."
        }
        packageProject.configure {
            common(project, extension, manifest)
            // a package is only written if it passes the validation, so `cringlePublish` never uploads an invalid one
            dependsOn(validate)
            group = TASK_GROUP
            description = "Builds the Cringle project package."
            packageFile.set(packageFile(project, manifest))
        }

        // the same task as in cringle.plugin: it does not tell the two kinds of package apart
        project.tasks.register(TASK_PUBLISH, PublishPluginTask::class.java).configure {
            dependsOn(packageProject)
            packageFile.set(packageProject.flatMap { it.packageFile })
            server.set(project.providers.gradleProperty(PROPERTY_SERVER).orElse(extension.publishSettings.server))
            dryRun.convention(false)
            group = TASK_GROUP
            description = "Publishes the Cringle project package to a repository."
        }
    }

    /** The wiring both package tasks share. */
    private fun CringleProjectPackageTask.common(
        project: Project,
        extension: CringleProjectExtension,
        manifest: () -> ProjectManifest,
    ) {
        manifestJson.set(project.provider { ManifestJson.encode(manifest()) })
        blueprintFiles.from(Callable { documents(extension.blueprints.get().asFile) })
        schemaFiles.from(Callable { documents(extension.schemas.get().asFile) })
        binariesDir.set(extension.binaries)
        binaryFiles.from(project.fileTree(extension.binaries))
    }

    /** The manifest the `cringle { }` block describes, without the entries that depend on the files. */
    private fun manifestOf(extension: CringleProjectExtension): ProjectManifest {
        val version = extension.version.get()
        if (version == Project.DEFAULT_VERSION) {
            throw GradleException(
                "cringle: the project has no version, so none can go into the package. " +
                    "Set the version of the Gradle project or `cringle.version` in build.gradle.kts.",
            )
        }
        return ProjectManifest(
            name = extension.name.get(),
            version = version,
            dependencies = LinkedHashMap(extension.dependencies.get()),
            fabrics = extension.fabrics,
        )
    }

    private fun packageFile(project: Project, manifest: () -> ProjectManifest): Provider<RegularFile> =
        project.layout.buildDirectory.dir("distributions")
            .flatMap { dir -> dir.file(project.provider { "${manifest().name}-${manifest().version}.cringle" }) }

    // The `cringle { }` block that may replace the folders runs after apply(), so they are read lazily.
    private fun documents(dir: File): List<File> =
        dir.listFiles { file: File -> file.isFile && file.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

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

        /** The task group the tasks appear under. */
        public const val TASK_GROUP: String = "cringle"

        /** The blueprints folder, if the project names no other one. */
        public const val DEFAULT_BLUEPRINTS: String = "src/main/cringle/blueprints"

        /** The schemas folder, if the project names no other one. */
        public const val DEFAULT_SCHEMAS: String = "src/main/cringle/schemas"

        /** The binaries folder, if the project names no other one. */
        public const val DEFAULT_BINARIES: String = "src/main/cringle/binaries"

        /**
         * What a project gets when it applies both ids. A plugin contributes blocks and a project deploys them, so
         * one Gradle project is one or the other; whichever id is applied second stops here.
         */
        internal const val CONFLICT: String =
            "cringle.plugin and cringle.project cannot be applied to the same Gradle project: a plugin contributes " +
                "blocks, a project deploys them with blueprints and fabric configs. Apply only one of the two ids."
    }
}
