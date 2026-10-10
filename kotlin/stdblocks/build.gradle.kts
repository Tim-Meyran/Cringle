// SPDX-License-Identifier: Apache-2.0

dependencies {
    compileOnly(project(":contract"))
    testImplementation(project(":contract"))
    testImplementation(project(":testkit"))
    testImplementation(project(":packaging"))
    testImplementation(project(":schema"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    explicitApi()
}
