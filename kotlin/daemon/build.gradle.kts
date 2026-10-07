// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":common"))
    implementation(project(":contract"))
    implementation(project(":engine"))
    implementation(project(":router"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.kotlinx.coroutines.test)
}
