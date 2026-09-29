// SPDX-License-Identifier: Apache-2.0

dependencies {
    api(project(":contract"))
    api(project(":schema"))
}

kotlin {
    explicitApi()
}
