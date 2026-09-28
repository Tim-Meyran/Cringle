// SPDX-License-Identifier: Apache-2.0

dependencies {
    implementation(project(":contract"))
    implementation(project(":common"))
    implementation(project(":schema"))
    implementation(project(":packaging"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
