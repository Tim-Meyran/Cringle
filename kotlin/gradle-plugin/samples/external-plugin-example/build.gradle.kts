// SPDX-License-Identifier: Apache-2.0

import cringle.contract.PortDirection
import cringle.contract.TetherType

plugins {
    // A plugin project pins its own Kotlin version. The functional test replaces it with the version of the build it
    // runs in, so that the example needs no network.
    kotlin("jvm") version "2.1.10"
    // The plugin itself, from Maven Local. `docs/gradle-plugin.md` says how to publish it.
    id("cringle.plugin")
}

group = "acme"
version = "1.2.0"

repositories {
    // contract is needed to compile against. A plugin project would take it from the Cringle repository, which does
    // not exist yet (#51).
    mavenLocal()
    mavenCentral()
}

dependencies {
    // The parent classloader provides the contract module at runtime, so it is compiled against but never packaged.
    // The functional test replaces the version with the one of the build it runs in.
    compileOnly("cringle:contract:0.0.0-SNAPSHOT")
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
