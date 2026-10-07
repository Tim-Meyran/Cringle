// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":common"))
    api(project(":packaging"))
    implementation(project(":contract"))
    implementation(project(":schema"))
    implementation(project(":router"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":common")))
}
