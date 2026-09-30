// SPDX-License-Identifier: Apache-2.0

pluginManagement {
    repositories {
        // The Cringle Gradle plugin is published into Maven Local by `./gradlew publishToMavenLocal` and is not
        // published anywhere else yet. The portal serves the Kotlin plugin, Maven Central the libraries the plugin
        // itself needs at runtime (coroutines, serialization).
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // The version the plugin is published under, see docs/gradle-plugin.md. The functional test replaces it with
        // the version of the build it runs in.
        id("cringle.plugin") version "0.0.0-SNAPSHOT"
    }
}

rootProject.name = "acme-orders-plugin"
