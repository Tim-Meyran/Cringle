// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":common"))
    implementation(project(":contract"))
    implementation(project(":router"))
    implementation(project(":engine"))
    implementation(project(":repository"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":daemon"))
    testImplementation(project(":engine"))
    testImplementation(project(":repository"))
    testImplementation(project(":testkit"))
}
