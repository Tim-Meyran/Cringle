// SPDX-License-Identifier: Apache-2.0

plugins {
    // The plugin itself, from Maven Local. `docs/gradle-plugin.md` says how to publish it.
    id("cringle.project")
}

group = "acme"
version = "0.3.1"

// A project package contains no code, so the project has no dependencies and needs no repository: a project refers to
// its Cringle dependencies by name and version range, and the deploy resolves them against the Cringle repository
// (chapter 8.4). The blueprints below `src/main/cringle/blueprints` name the blocks they use, not the classes.

cringle {
    name = "acme-shop"
    // The plugin the blocks of the blueprints come from. The build does not resolve it, the deploy does.
    dependency("acme-orders", "^1.2.0")
    // Two copies of the blueprint on every engine that has the role `edge` and the label `zone=a`.
    fabric("orders") {
        instances = 2
        roles = listOf("edge")
        labels = mapOf("zone" to "a")
    }
}
