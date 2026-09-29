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
