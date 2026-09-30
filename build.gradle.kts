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
// without touching ~/.m2. The same publications go into a second folder, this time in the layout of Maven Local and
// including the plugin itself, which is what projects outside this build resolve.
val localPublishVersion = versionCatalog.findVersion("localPublish").get().requiredVersion
val localPublishRepo = layout.buildDirectory.dir("cringle-repo")
val testMavenLocalRepo = layout.buildDirectory.dir("cringle-test-maven-local")
val localPublishModules = listOf(":contract", ":schema", ":packaging")

localPublishModules.forEach { path ->
    val module = project(path)
    module.group = "cringle"
    module.version = localPublishVersion
    module.apply(plugin = "maven-publish")
    module.extensions.configure<PublishingExtension> {
        publications { register<MavenPublication>("maven") { from(module.components["java"]) } }
        repositories {
            maven { name = "cringleLocal"; url = localPublishRepo.get().asFile.toURI() }
            maven { name = "cringleTestMavenLocal"; url = testMavenLocalRepo.get().asFile.toURI() }
        }
    }
}

// `java-gradle-plugin` creates the publications of the Cringle Gradle plugin: the plugin JAR and one marker per
// plugin id in `gradlePlugin { }`. The build script of the module sets its own coordinates, the folder its
// publications are written to and the marker it leaves out; `build/cringle-repo` stays the way it is for the samples.

tasks.register("publishToLocalRepo") {
    group = "build"
    description = "Publishes contract, schema and packaging into build/cringle-repo for the Gradle plugin samples."
    dependsOn(localPublishModules.map { "$it:publishAllPublicationsToCringleLocalRepository" })
}

// The tests read the folder of the publications, so it holds what one publication writes and nothing of an earlier
// one. A Gradle repository gives a snapshot a new timestamp in the name of its files on every publication, and the
// tests would otherwise see the publications of every build that ever ran.
val deleteTestMavenLocal = tasks.register<Delete>("deleteTestMavenLocal") {
    group = "build"
    description = "Empties build/cringle-test-maven-local before the next publication into it."
    delete(testMavenLocalRepo)
}

// The plugin publishes itself, `java-gradle-plugin` creates its publications and its marker.
val testMavenLocalModules = localPublishModules + ":gradle-plugin"

tasks.register("publishToTestMavenLocal") {
    group = "build"
    description = "Publishes contract, schema, packaging and the Cringle Gradle plugin in the layout of Maven Local " +
        "into build/cringle-test-maven-local, for the tests of samples/external-plugin-example."
    dependsOn(testMavenLocalModules.map { "$it:publishAllPublicationsToCringleTestMavenLocalRepository" })
    dependsOn(deleteTestMavenLocal)
}

testMavenLocalModules.forEach { path ->
    project(path).tasks.matching { it.name == "publishAllPublicationsToCringleTestMavenLocalRepository" }
        .configureEach { mustRunAfter(deleteTestMavenLocal) }
}
