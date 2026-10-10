// SPDX-License-Identifier: Apache-2.0

dependencies {
    compileOnly(project(":contract"))
    testImplementation(project(":contract"))
    testImplementation(project(":testkit"))
    // the real sandbox of the filesystem driver: the file blocks are tested against it
    testImplementation(project(":engine"))
    testImplementation(project(":packaging"))
    testImplementation(project(":schema"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    explicitApi()
}
