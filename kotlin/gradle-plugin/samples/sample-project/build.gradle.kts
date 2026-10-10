// SPDX-License-Identifier: Apache-2.0

plugins {
    // TestKit injects the plugin classpath, so the sample names no version of the plugin.
    id("cringle.project")
}

group = "acme"
version = "0.3.1"

// A project package contains no code, so the sample has no dependencies at all: a project refers to its dependencies
// by name and version range, and the deploy resolves them against the Cringle repository (chapter 8.4).

cringle {
    name = "acme-shop"
    // The plugin the blocks of the blueprints come from. The build does not resolve it, the deploy does.
    dependency("acme-orders", "^1.2.0")
    // A class of that plugin that migrates the data folder of a whole instance of this project when the version of the project changes
    // (docs/migration-guide.md); optional, like the one for the downgrade.
    processors {
        update = "acme.orders.OrdersInstanceUpdate"
    }
    // Two copies of the blueprint on every engine that has the role `edge` and the label `zone=a`.
    fabric("orders") {
        instances = 2
        roles = listOf("edge")
        labels = mapOf("zone" to "a")
    }
}
