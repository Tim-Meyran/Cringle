// SPDX-License-Identifier: Apache-2.0

import cringle.contract.PortDirection
import cringle.contract.TetherType

plugins {
    // TestKit injects the plugin classpath, so the sample names no version of the Kotlin plugin.
    kotlin("jvm")
    id("cringle.plugin")
}

group = "acme"
version = "1.2.0"

repositories {
    // The libraries of this repository. The functional tests pass the folder as -PcringleRepo, a real plugin project
    // would use the Cringle repository here.
    maven { url = uri(providers.gradleProperty("cringleRepo").get()) }
    mavenCentral()
}

// A dependency of the sample that the parent classloader does not provide, so that lib/ of the package contains more
// than the JAR of the project itself. A real plugin project would depend on a library from a repository.
val supportJar = tasks.register<Jar>("supportJar") {
    archiveFileName = "acme-orders-support.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
}

dependencies {
    // The parent classloader provides the contract module at runtime, so it is compiled against but never packaged.
    compileOnly("cringle:contract:" + providers.gradleProperty("cringleVersion").get())
    implementation(files(supportJar))
}

cringle {
    name = "acme-orders"
    dependency("acme-core", "^1.0.0")
    provider("acme.orders.OrdersProvider")
    driver("acme.orders.OrdersDriver")
    block("orders") {
        configSchema("acme.orders/OrdersConfig")
        schema("acme.orders/Order")
        requiredDriver("acme.orders.orders")
        port("in", PortDirection.IN, "acme.orders/Order", TetherType.REQUEST_RESPONSE)
        port("out", PortDirection.OUT, "acme.orders/Order", TetherType.MESSAGE)
    }
}
