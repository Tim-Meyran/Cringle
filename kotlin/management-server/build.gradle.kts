// SPDX-License-Identifier: Apache-2.0

plugins {
    `java-test-fixtures`
}

dependencies {
    // the standard blocks (plugin cringle-std): their classes give the definitions, their jar is the lib of the package that is published at start
    implementation(project(":stdblocks"))
    api(project(":common"))
    implementation(project(":contract"))
    implementation(project(":router"))
    implementation(project(":engine"))
    implementation(project(":repository"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    // the mutual-TLS wiring that every test with a daemon, an engine, a repository or a router needs (#61)
    testFixturesApi(testFixtures(project(":common")))
    testFixturesApi(project(":daemon"))
    testFixturesApi(project(":repository"))
    testFixturesApi(project(":router"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":daemon"))
    testImplementation(project(":engine"))
    testImplementation(project(":repository"))
    testImplementation(project(":testkit"))
    testImplementation(testFixtures(project(":common")))
}

// the jar of the standard blocks is a resource: StdBlocksInstaller builds the package `cringle-std` from it
tasks.processResources {
    dependsOn(":stdblocks:jar")
    from(project(":stdblocks").layout.buildDirectory.dir("libs")) {
        include("stdblocks*.jar")
        into("std")
        rename { "stdblocks.jar" }
    }
}
