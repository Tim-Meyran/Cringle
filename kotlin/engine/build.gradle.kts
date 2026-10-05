// SPDX-License-Identifier: Apache-2.0

dependencies {
    implementation(project(":contract"))
    implementation(project(":schema"))
    implementation(project(":packaging"))
    implementation(project(":common"))
    implementation(project(":repository"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.jserialcomm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testkit"))
    testImplementation(project(":router"))
}
