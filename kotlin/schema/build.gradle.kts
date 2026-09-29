// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":contract"))
    api(libs.kotlinx.serialization.json)
}

kotlin {
    explicitApi()
}
