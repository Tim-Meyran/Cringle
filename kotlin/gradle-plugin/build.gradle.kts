// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    // `java-gradle-plugin` creates the publications (the plugin JAR and one marker per plugin id) but leaves applying
    // `maven-publish` to the build, so the version, the group and the folder of the publications are set here.
    `maven-publish`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val localPublishVersion = libs.findVersion("localPublish").get().requiredVersion
val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion

// The coordinates under which projects outside this build resolve the plugin. The version is the one definition in
// the version catalog, so the plugin, its marker and the three libraries always have the same one.
group = "cringle"
version = localPublishVersion

dependencies {
    implementation(project(":packaging"))
    implementation(project(":contract"))

    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("cringlePlugin") {
            id = "cringle.plugin"
            implementationClass = "cringle.gradle.CringlePlugin"
        }
        create("cringleProject") {
            id = "cringle.project"
            implementationClass = "cringle.gradle.CringleProjectPlaceholder"
        }
    }
}

// The id `cringle.project` is reserved, but its marker must not be published: a marker is a promise that the plugin
// exists, and the plugin itself comes with issue #52. The publication stays, so #52 only has to enable it again.
// `java-gradle-plugin` names the marker publication of an id after the id in camel case, with the suffix
// `PluginMarkerMaven`; the artifact of a publication is only set after this script has run, so the name is what the
// marker is recognised by. The test `MavenLocalPublicationTest` fails if a marker appears all the same.
val unpublishedPluginIds = listOf("cringle.project")
val unpublishedMarkers = unpublishedPluginIds.map { id ->
    id.split('.').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }
        .joinToString("") + "PluginMarkerMaven"
}
val publishing = extensions.getByType<PublishingExtension>()

publishing.publications.withType<MavenPublication>()
    .matching { it.name in unpublishedMarkers }
    .configureEach {
        val marker = this
        tasks.withType<AbstractPublishToMaven>().configureEach {
            if (publication === marker) {
                enabled = false
            }
        }
    }

// The folder with the layout of Maven Local, for projects outside this build. `./gradlew publishToMavenLocal` writes
// the same publications into the real one; this folder is what the functional tests use instead.
publishing.repositories.maven {
    name = "cringleTestMavenLocal"
    url = rootProject.layout.buildDirectory.dir("cringle-test-maven-local").get().asFile.toURI()
}

kotlin {
    explicitApi()
}

// The sample resolves contract, schema and packaging from the Maven repository that the root build publishes them
// into. The path is absolute, so a copy of the sample in another directory keeps working.
val localRepo = rootProject.layout.buildDirectory.dir("cringle-repo")
// The same publications in the layout of Maven Local, this time with the plugin itself, for the example outside this
// build. The path is absolute for the same reason as above.
val testMavenLocal = rootProject.layout.buildDirectory.dir("cringle-test-maven-local")

// TestKit resolves the plugins of a build under test from the classpath it injects and from nowhere else, so the
// Kotlin plugin of the sample goes into that classpath as well. The sample itself therefore names no version.
val kotlinPluginClasspath: Configuration by configurations.creating
dependencies {
    kotlinPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
}

// The nested builds of the functional tests resolve the libraries of the sample like any other build, but with
// --offline: this build has already downloaded them into the Gradle cache while it compiled its own Kotlin code, so
// the tests need no network of their own. TestKit gives a build under test an empty cache of its own, so the nested
// builds are pointed at the cache of this build. The example outside this build resolves the plugin itself from a
// folder with the layout of Maven Local, which the test passes as -Dmaven.repo.local, so no test ever touches ~/.m2.
tasks.withType<Test> {
    dependsOn(rootProject.tasks.named("publishToLocalRepo"), rootProject.tasks.named("publishToTestMavenLocal"))
    systemProperty("cringle.localRepo", localRepo.get().asFile.absolutePath)
    systemProperty("cringle.testMavenLocal", testMavenLocal.get().asFile.absolutePath)
    systemProperty("cringle.version", localPublishVersion)
    systemProperty("cringle.kotlinVersion", kotlinVersion)
    systemProperty("cringle.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
    inputs.files(kotlinPluginClasspath).withNormalizer(ClasspathNormalizer::class)
    doFirst {
        systemProperty(
            "cringle.kotlinPluginClasspath",
            kotlinPluginClasspath.joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}
