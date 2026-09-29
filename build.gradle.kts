// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.protobuf) apply false
}

val licenseHeaderPattern = "(package|import|syntax|plugins|pluginManagement|dependencyResolutionManagement|rootProject|dependencies|val|tasks|configure|subprojects|sourceSets|include)"

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.PLATFORM_NATIVE
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n")
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n", licenseHeaderPattern)
    }
    format("proto") {
        target("proto/**/*.proto")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n", licenseHeaderPattern)
    }
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        add("implementation", versionCatalog.findLibrary("kotlin-stdlib").get())
        add("testImplementation", versionCatalog.findLibrary("junit-jupiter").get())
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.11.4")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// The Cringle Gradle plugin and the TestKit sample projects need contract, schema and packaging as ordinary
// dependencies. A local Maven repository inside build/ keeps them resolvable without publishing anywhere and
// without touching ~/.m2.
val localPublishRepo = layout.buildDirectory.dir("cringle-repo")
val localPublishModules = listOf(":contract", ":schema", ":packaging")

localPublishModules.forEach { path ->
    val module = project(path)
    module.group = "cringle"
    module.version = versionCatalog.findVersion("localPublish").get().requiredVersion
    module.apply(plugin = "maven-publish")
    module.extensions.configure<PublishingExtension> {
        publications { register<MavenPublication>("maven") { from(module.components["java"]) } }
        repositories { maven { name = "cringleLocal"; url = localPublishRepo.get().asFile.toURI() } }
    }
}

tasks.register("publishToLocalRepo") {
    group = "build"
    description = "Publishes contract, schema and packaging into build/cringle-repo for the Gradle plugin samples."
    dependsOn(localPublishModules.map { "$it:publishAllPublicationsToCringleLocalRepository" })
}
