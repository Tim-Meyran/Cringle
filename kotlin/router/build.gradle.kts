// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":common"))
    api(project(":contract"))
    api("org.jetbrains.kotlin:kotlin-stdlib")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.grpc.netty.shaded)
    testImplementation(libs.kotlinx.coroutines.test)
}
