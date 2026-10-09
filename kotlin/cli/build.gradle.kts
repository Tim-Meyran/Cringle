// SPDX-License-Identifier: Apache-2.0

plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.jline)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":management-server"))
    testImplementation(project(":daemon"))
    testImplementation(project(":engine"))
    testImplementation(project(":repository"))
    testImplementation(project(":router"))
    testImplementation(project(":contract"))
    testImplementation(project(":packaging"))
    testImplementation(project(":testkit"))
    testImplementation(testFixtures(project(":common")))
    testImplementation(testFixtures(project(":management-server")))
}

application {
    mainClass.set("cringle.cli.MainKt")
    applicationName = "cringle"
}

// DistributionTest (#57) checks the release archives of `cringleDist` in the root project: their content, the manifest,
// and that an unpacked archive starts. The archives are built before the tests run.
tasks.withType<Test> {
    val dist = rootProject.tasks.named("cringleDist")
    val distDir = rootProject.layout.buildDirectory.dir("dist")
    if (name == "integrationTest") {
        maxParallelForks = 1 // nested Gradle builds and the unpacked distribution share directories (build/dist, the test home)
        dependsOn(dist)
        inputs.files(dist)
        // the folder exists after `cringleDist`; the fast suite does not read it, and on a clean checkout it is not there
        inputs.dir(distDir)
    }
    systemProperty("cringle.distDir", distDir.get().asFile.absolutePath)
    systemProperty("cringle.releaseVersion", rootProject.extra["releaseVersion"] as String)
}
