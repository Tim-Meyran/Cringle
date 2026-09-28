// SPDX-License-Identifier: Apache-2.0

dependencies {
    implementation(project(":contract"))
    implementation(project(":schema"))
    implementation(project(":packaging"))
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
