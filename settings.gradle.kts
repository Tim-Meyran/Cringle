// SPDX-License-Identifier: Apache-2.0

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cringle"

val modules = listOf(
    "contract",
    "schema",
    "wire",
    "packaging",
    "engine",
    "router",
    "daemon",
    "repository",
    "management-server",
    "cli",
    "common",
    "testkit",
    "gradle-plugin",
)

for (m in modules) {
    include(":$m")
    project(":$m").projectDir = file("kotlin/$m")
}
