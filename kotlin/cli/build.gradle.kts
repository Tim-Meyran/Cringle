// SPDX-License-Identifier: Apache-2.0

plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":management-server"))
    testImplementation(project(":daemon"))
    testImplementation(project(":engine"))
    testImplementation(project(":repository"))
    testImplementation(project(":router"))
    testImplementation(project(":contract"))
    testImplementation(project(":packaging"))
    testImplementation(project(":testkit"))
}

application {
    mainClass.set("cringle.cli.MainKt")
    applicationName = "cringle"
}

// DistributionTest (#57) checks the release archives of `cringleDist` in the root project: their content, the manifest,
// and that an unpacked archive starts. The archives are built before the tests run.
tasks.test {
    val dist = rootProject.tasks.named("cringleDist")
    dependsOn(dist)
    inputs.files(dist)
    val distDir = rootProject.layout.buildDirectory.dir("dist")
    inputs.dir(distDir)
    systemProperty("cringle.distDir", distDir.get().asFile.absolutePath)
    systemProperty("cringle.releaseVersion", rootProject.extra["releaseVersion"] as String)
}
