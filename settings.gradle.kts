// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.support.serviceOf

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cringle"

val modules = listOf(
    "contract",
    "schema",
    "packaging",
    "engine",
    "router",
    "daemon",
    "repository",
    "management-server",
    "cli",
    "common",
    "testkit",
    "gradle-plugin",
)

for (m in modules) {
    include(":$m")
    project(":$m").projectDir = file("kotlin/$m")
}

// `./gradlew ... --warn` hides the line "BUILD SUCCESSFUL" (Gradle prints it at the lifecycle level), but the agents want to see that a
// build went through. This prints it at the end of a successful build; a failed build prints Gradle's own error output.
abstract class ReportSuccess : FlowAction<ReportSuccess.Parameters> {
    interface Parameters : FlowParameters {
        @get:Input val failed: Property<Boolean>
        @get:Input val startNanos: Property<Long>
    }

    override fun execute(parameters: Parameters) {
        if (!parameters.failed.get()) println("BUILD SUCCESSFUL in ${(System.nanoTime() - parameters.startNanos.get()) / 1_000_000_000}s")
    }
}

val buildStart = System.nanoTime()
gradle.serviceOf<FlowScope>().always(ReportSuccess::class.java) {
    parameters.failed.set(gradle.serviceOf<FlowProviders>().buildWorkResult.map { it.failure.isPresent })
    parameters.startNanos.set(buildStart)
}
