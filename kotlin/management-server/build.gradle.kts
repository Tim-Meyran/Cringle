// SPDX-License-Identifier: Apache-2.0

plugins {
    `java-test-fixtures`
}

dependencies {
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
