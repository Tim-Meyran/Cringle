# Using the Cringle Gradle plugin

The plugin has two ids, one per kind of project a Cringle application is built from. Both turn a normal Gradle project into a package (a ZIP) that the Repository hands out:

| Plugin id | What the build becomes | Which task writes the package |
| --- | --- | --- |
| `cringle.plugin` | A **plugin project**: the `cringle { }` block describes the blocks it contributes. | `./gradlew cringlePackage` → `build/distributions/<name>-<version>.cringle` |
| `cringle.project` | A **project project**: the `cringle { }` block describes the application built from blueprints. | `./gradlew cringlePackage` → `build/distributions/<name>-<version>.cringle` |

A build applies exactly one of the two. Both define the extension `cringle { }` with a different content, and applying both fails the build with a message that names both ids: a build is a plugin package or a project package, and a second package task would quietly overwrite the first.

**Publishing.** The plugin is published to **Maven Local only** — nothing else, nowhere else. `~/.m2` is the single source for projects outside this repository:

```bash
./gradlew publishToMavenLocal
```

That writes the group `cringle` at the version `0.0.0-SNAPSHOT` (the one definition is `localPublish` in `gradle/libs.versions.toml`):

| Coordinates | What it is |
| --- | --- |
| `cringle:gradle-plugin:0.0.0-SNAPSHOT` | The plugin itself (JAR, POM, Gradle module metadata). |
| `cringle.plugin:cringle.plugin.gradle.plugin:0.0.0-SNAPSHOT` | The plugin marker of `cringle.plugin`, the coordinate `plugins { id("cringle.plugin") version … }` resolves. |
| `cringle.project:cringle.project.gradle.plugin:0.0.0-SNAPSHOT` | The plugin marker of `cringle.project`, the coordinate `plugins { id("cringle.project") version … }` resolves. |
| `cringle:contract:0.0.0-SNAPSHOT` | The language-independent contract, needed to compile a plugin against. |
| `cringle:schema:0.0.0-SNAPSHOT` | The schema module, a dependency of `packaging`. |
| `cringle:packaging:0.0.0-SNAPSHOT` | The packaging module, which the plugin uses to read and write packages. |
| `cringle:repository:0.0.0-SNAPSHOT` | The repository module, which the plugin uses to publish a package (`cringlePublish`). |
| `cringle:common:0.0.0-SNAPSHOT` | The gRPC types, a dependency of `repository`. |
| `cringle:router:0.0.0-SNAPSHOT` | The router module, which `repository` needs at runtime for `AuthInterceptor`. |

The last three are there because the plugin publishes with `RepositoryClient` (see “Publishing a plugin” below). The version is a snapshot on purpose: a local publication is overwritten by the next one, and a project that pins it gets whatever the last build of this repository wrote. To look at the result without touching `~/.m2`, point the publication at a folder of your own:

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/cringle-maven-local
find /tmp/cringle-maven-local -name '*.pom'
```

```
/tmp/cringle-maven-local/cringle/common/0.0.0-SNAPSHOT/common-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/contract/0.0.0-SNAPSHOT/contract-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/gradle-plugin/0.0.0-SNAPSHOT/gradle-plugin-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/packaging/0.0.0-SNAPSHOT/packaging-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/plugin/cringle.plugin.gradle.plugin/0.0.0-SNAPSHOT/cringle.plugin.gradle.plugin-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/project/cringle.project.gradle.plugin/0.0.0-SNAPSHOT/cringle.project.gradle.plugin-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/repository/0.0.0-SNAPSHOT/repository-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/router/0.0.0-SNAPSHOT/router-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/schema/0.0.0-SNAPSHOT/schema-0.0.0-SNAPSHOT.pom
```

The same nine publications, the plugin, both markers and the six libraries. `samples/external-plugin-example` and `samples/external-project-example` are two complete projects that do exactly this, one per plugin id, and `MavenLocalPublicationTest` checks the same file set in every build. Their `settings.gradle.kts` list `mavenCentral()` behind the plugin portal because the functional tests build them offline, where the plugin portal cannot serve the libraries the plugin itself needs (coroutines, serialization); a project with a network gets them from the portal.

**Using it in a project.** The plugin comes from `mavenLocal()`, so the build has to look there before the plugin portal. The version belongs into `settings.gradle.kts`, next to the repositories:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
    plugins {
        id("cringle.plugin") version "0.0.0-SNAPSHOT"
        // or, for a project: id("cringle.project") version "0.0.0-SNAPSHOT"
    }
}
```

## A plugin project

The build script applies the plugin, takes `contract` from Maven Local to compile against, and describes what the plugin contributes:

```kotlin
plugins {
    kotlin("jvm") version "2.1.10"
    id("cringle.plugin")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // The runtime provides the contract module, so a plugin only compiles against it.
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
```

`./gradlew cringlePackage` writes `build/distributions/acme-orders-1.2.0.cringle`; `cringleValidate` checks the same package against the contract without writing it. The package is checked against the plugins it depends on, as the repository does when it is published: a block that uses a schema of a dependency passes, a schema that the dependency does not have fails the build. The build asks the repository for those plugins, with the address and the token of `cringlePublish` (see "Publishing a plugin"); it only reads them and locks or resolves nothing, that is the deploy's job. Without a repository, because none is configured or because it is not reachable or does not answer, `cringleValidate` goes on with a warning and checks what it can: a reference to a schema outside the namespaces of the plugin itself is then taken as coming from its dependencies and checked when the package is published, while a reference into the plugin's own namespace that does not resolve still fails, and so does any reference outside the plugin in a plugin that declares no dependency (the message names the namespace and how to declare the dependency). `cringlePackage` runs `cringleValidate` first. Everything the `cringle { }` block contributes has to exist in the project: the provider and driver classes, a schema document per schema, and whatever goes into `lib/` and `binaries/`. The example project has all of it and is the shortest path from nothing to a working package.

## Publishing a plugin

This section holds for both plugin ids: `cringle.plugin` publishes its plugin package, `cringle.project` its project package, with the same task, the same `cringle { publish { server = "host:port" } }` block, the same `-Pcringle.server`, `CRINGLE_SERVER`, `CRINGLE_TOKEN` and profile, and the same messages. The task does not tell the two kinds apart: the repository checks a package as it is (a project against the plugins it depends on), and the repository has to hold those plugins before a project that uses their blocks can be published.

`cringlePublish` hands the package of the build to a Cringle repository. It runs `cringlePackage` first, reads and validates the package, uploads it unchanged with `RepositoryClient` and checks the SHA-256 the repository reports against the SHA-256 of the local file. `cringlePackage` in turn runs `cringleValidate` first in both plugins, so a package that does not pass the validation is never written and never uploaded; `cringlePublish` itself depends on `cringlePackage` alone:

```bash
./gradlew cringlePublish
```

The task belongs to neither `build` nor `check` and never runs on its own. The version of the package is the one in `cringle { version }`, the repository takes a version once: publishing the same version a second time fails the build.

**Where the address comes from.** The first of these four that names a repository wins:

| Source | Example |
| --- | --- |
| The task property | `./gradlew cringlePublish -Pcringle.server=host:9090` |
| The `cringle { }` block | `cringle { publish { server = "host:9090" } }` |
| The environment | `CRINGLE_SERVER=host:9090` |
| The profile of `cringle login` | `$CRINGLE_HOME/cli.json`, else `~/.cringle/cli.json`, with its `server` entry |

```kotlin
cringle {
    publish { server = "host:9090" }
    name = "acme-orders"
    // …
}
```

The same ranking as in the CLI, so that a `cringle login` also holds for a Gradle build. Without an address anywhere the build fails with a message that names all four ways to set one.

**The token.** A repository that knows users wants a token with the right `Permission.OPERATE`. The task takes it from `CRINGLE_TOKEN` or from the `token` entry of the profile, never from a build script and never from a command line, so it cannot end up in a `build.gradle.kts`. The task itself never writes the token anywhere: not into a message, not into the output of a build at the default level or with `--info`. With `--debug` Gradle lowers the log level of the gRPC transport, which then prints the headers of every call, including `authorization: Bearer ...`, so a `--debug` log can contain the token; do not share one. A repository that needs a token and gets none fails the build with a message that names `CRINGLE_TOKEN` and `cringle login`.

**What a build says.** On success the task reports name, version, SHA-256 and the repository it published to:

```text
cringlePublish: published acme-orders 1.2.0 (SHA-256 7596afcb…) to 127.0.0.1:9090
```

A repository that refuses the package fails the build with one line and no stack trace:

| Case | Message names |
| --- | --- |
| The version is already there | `already published` |
| The server is not reachable | The address, and that the server is not reachable |
| No token, or one that may not publish | `Permission.OPERATE`, `CRINGLE_TOKEN` and `cringle login` |

**A build without a repository.** `--dryRun` builds and validates the package, connects to nothing and names what it would publish:

```bash
./gradlew cringlePublish --dryRun
```

```text
cringlePublish: would publish acme-orders 1.2.0 to 127.0.0.1:9090 (--dryRun sent nothing)
```

> **The name of the option is `--dryRun`, not `--dry-run`.** Gradle reserves `--dry-run` for the whole build and takes it before any task sees it, also when a task declares an option of that name: `cringlePublish --dry-run` builds nothing and reports `:cringlePublish SKIPPED`. The option of the task is therefore written in camel case, the way Gradle writes `--no-daemon` beside `--dry-run`.

The connection is unencrypted, like the one of the CLI; TLS and mTLS come with #13.

## A project project

A project is assembled from blueprints, tethers and the plugins that contribute blocks — and holds **no code**. So the plugin that packages one needs no Kotlin plugin, no repository and no dependency at all: it only assembles what the project has under `src/main/cringle` into a package.

```kotlin
plugins {
    // The plugin is all this build applies. TestKit injects it into a build of this repository, so the sample names
    // no version; a project outside this build takes it from Maven Local, see `pluginManagement` above.
    id("cringle.project")
}

// The name and the version of the package are the ones of the Gradle project.
group = "acme"
version = "0.3.1"

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
```

Everything below `src/main/cringle` goes into the package, each kind under a fixed entry name, with the path below the source kept:

| Source | Entry in the package | What it is |
| --- | --- | --- |
| `src/main/cringle/blueprints/**.json` | `blueprints/<name>.json` | One blueprint document per file: its blocks, the plugins they come from, and its tethers. |
| `src/main/cringle/schemas/**.json` | `schemas/<name>.json` | Schema documents, which the blueprints may refer to by name. |
| `src/main/cringle/binaries/**` | `binaries/<path>` | Static resources of the application, e.g. configuration files or a web page. |

The manifest is derived from those files: the list of blueprints, schemas and binaries is what the build found, so the package cannot claim a file it does not carry.

`./gradlew cringlePackage` writes `build/distributions/acme-shop-0.3.1.cringle` (after `cringleValidate` has passed, which it depends on), `cringleValidate` checks the sources and the assembled package without writing it, and `./gradlew cringlePublish` uploads the package to a repository. Both validation and packaging find

* a blueprint with a name that another blueprint already has, or a block id that another block of it already has,
* a fabric that names a blueprint the project does not have,
* a tether that points at a block or a port that the blueprint does not have,
* a dependency whose version range is not a version range, and
* a port that is not a TCP port or a `delivery` that is not one of `fire-and-forget`, `at-least-once` or `exactly-once`,
* a block id, port name or blueprint reference that is not a name a directory can have: letters, digits, `.`, `-`
  and `_`, 1 to 64 characters, never starting or ending with a separator, and never a name Windows reserves
  (`con`, `nul`, `com1` to `com9`, …), whatever its extension.

and fail the build with one line per finding as `<path>: <message>`, where the text is the one of the `packaging` module. `samples/sample-project` is a complete project of this kind and the shortest path from nothing to a working package.

Nothing in a project package is resolved at build time: the dependencies are Cringle plugins, and the deploy resolves them against the Cringle repository (chapter 8.4), so `cringleValidate` runs without a network. Of a tether only the port number and the `delivery` are checked, and only against the contract: the blocks a blueprint names are declared by the plugins the deploy installs.

**What is not there yet.** The plugin is nowhere but in Maven Local: no Gradle Plugin Portal, no Maven Central. Both kinds of project publish into a Cringle repository of their own with `cringlePublish` (see "Publishing a plugin" above, which holds for both ids). The build of a project project still needs no repository: only `cringlePublish` talks to one, and it is the deploy, not the build, that places a project on engines (`cringle deploy`).
