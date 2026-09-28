// SPDX-License-Identifier: Apache-2.0

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    alias(libs.plugins.spotless)
}

val licenseHeaderPattern = "(package|import|syntax|plugins|pluginManagement|dependencyResolutionManagement|rootProject|dependencies|val|tasks|configure|subprojects|sourceSets|include|gradlePlugin)"

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        create("cringlePlugin") {
            id = "cringle.plugin"
            implementationClass = "cringle.gradle.CringlePluginPlaceholder"
        }
        create("cringleProject") {
            id = "cringle.project"
            implementationClass = "cringle.gradle.CringlePluginPlaceholder"
        }
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.PLATFORM_NATIVE
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n")
    }
    kotlinGradle {
        target("*.gradle.kts")
        targetExclude("**/build/**")
        licenseHeader("// SPDX-License-Identifier: Apache-2.0\n\n", licenseHeaderPattern)
    }
}
