// SPDX-License-Identifier: Apache-2.0

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
    // The task cringlePublish uploads with `RepositoryClient` and reads the profile of `cringle login` with the same
    // JSON library the CLI uses.
    implementation(project(":repository"))
    implementation(libs.findLibrary("kotlinx-serialization-json").get())

    testImplementation(project(":router"))
    testImplementation(testFixtures(project(":common")))
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
            implementationClass = "cringle.gradle.CringleProjectPlugin"
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

// `cringlePublish` reads the address and the token of the profile that `cringle login` writes from the Cringle home, so
// the tests point that home at a folder of the build directory. No test of this build ever reads the `cli.json` of the
// user who runs it, and the path is an input of the test task, so a build with another home runs the tests again.
val cringleTestHome = rootProject.layout.buildDirectory.dir("cringle-test-home")

// The nested builds of the functional tests resolve the libraries of the sample like any other build, but with
// --offline: this build has already downloaded them into the Gradle cache while it compiled its own Kotlin code, so the
// tests need no network of their own. TestKit gives a build under test an empty cache of its own, so the nested
// builds are pointed at the cache of this build. The example outside this build resolves the plugin itself from a
// folder with the layout of Maven Local, which the test passes as -Dmaven.repo.local, so no test ever touches ~/.m2.
tasks.withType<Test> {
    if (name == "integrationTest") {
        dependsOn(rootProject.tasks.named("publishToLocalRepo"), rootProject.tasks.named("publishToTestMavenLocal"))
    }
    systemProperty("cringle.localRepo", localRepo.get().asFile.absolutePath)
    systemProperty("cringle.testMavenLocal", testMavenLocal.get().asFile.absolutePath)
    systemProperty("cringle.version", localPublishVersion)
    systemProperty("cringle.kotlinVersion", kotlinVersion)
    systemProperty("cringle.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
    systemProperty("cringle.testHome", cringleTestHome.get().asFile.absolutePath)
    environment("CRINGLE_HOME", cringleTestHome.get().asFile.absolutePath)
    inputs.property("cringleTestHome", cringleTestHome.get().asFile.absolutePath)
    inputs.files(kotlinPluginClasspath).withNormalizer(ClasspathNormalizer::class)
    doFirst {
        systemProperty(
            "cringle.kotlinPluginClasspath",
            kotlinPluginClasspath.joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}
