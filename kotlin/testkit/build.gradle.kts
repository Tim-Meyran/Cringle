// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":contract"))
    api(project(":common"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit.jupiter)
}
