// SPDX-License-Identifier: Apache-2.0

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

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

kotlin {
    explicitApi()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The sample resolves contract, schema and packaging from the Maven repository that the root build publishes them
// into. The path is absolute, so a copy of the sample in another directory keeps working.
val localRepo = rootProject.layout.buildDirectory.dir("cringle-repo")
val localPublishVersion = libs.findVersion("localPublish").get().requiredVersion

// TestKit resolves the plugins of a build under test from the classpath it injects and from nowhere else, so the
// Kotlin plugin of the sample goes into that classpath as well. The sample itself therefore names no version.
val kotlinPluginClasspath: Configuration by configurations.creating
dependencies {
    kotlinPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.findVersion("kotlin").get().requiredVersion}")
}

// The nested builds of the functional tests resolve the libraries of the sample like any other build, but with
// --offline: this build has already downloaded them into the Gradle cache while it compiled its own Kotlin code, so
// the tests need no network of their own. TestKit gives a build under test an empty cache of its own, so the nested
// builds are pointed at the cache of this build.
tasks.withType<Test> {
    dependsOn(rootProject.tasks.named("publishToLocalRepo"))
    systemProperty("cringle.localRepo", localRepo.get().asFile.absolutePath)
    systemProperty("cringle.version", localPublishVersion)
    systemProperty("cringle.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
    inputs.files(kotlinPluginClasspath).withNormalizer(ClasspathNormalizer::class)
    doFirst {
        systemProperty(
            "cringle.kotlinPluginClasspath",
            kotlinPluginClasspath.joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}
