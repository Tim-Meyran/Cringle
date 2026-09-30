# Using the Cringle Gradle plugin

The plugin turns a normal Gradle project into a Cringle **plugin project**: the `cringle { }` block in its build script describes the blocks it contributes, and `cringlePackage` writes a plugin package (a ZIP) that the Repository hands out to projects.

**Publishing.** The plugin is published to **Maven Local only** — nothing else, nowhere else. `~/.m2` is the single source for projects outside this repository:

```bash
./gradlew publishToMavenLocal
```

That writes the group `cringle` at the version `0.0.0-SNAPSHOT` (the one definition is `localPublish` in `gradle/libs.versions.toml`):

| Coordinates | What it is |
| --- | --- |
| `cringle:gradle-plugin:0.0.0-SNAPSHOT` | The plugin itself (JAR, POM, Gradle module metadata). |
| `cringle.plugin:cringle.plugin.gradle.plugin:0.0.0-SNAPSHOT` | The plugin marker of `cringle.plugin`, the coordinate `plugins { id("cringle.plugin") version … }` resolves. |
| `cringle:contract:0.0.0-SNAPSHOT` | The language-independent contract, needed to compile a plugin against. |
| `cringle:schema:0.0.0-SNAPSHOT` | The schema module, a dependency of `packaging`. |
| `cringle:packaging:0.0.0-SNAPSHOT` | The packaging module, which the plugin uses to read and write packages. |

The version is a snapshot on purpose: a local publication is overwritten by the next one, and a project that pins it gets whatever the last build of this repository wrote. To look at the result without touching `~/.m2`, point the publication at a folder of your own:

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/cringle-maven-local
find /tmp/cringle-maven-local -name '*.pom'
```

```
/tmp/cringle-maven-local/cringle/contract/0.0.0-SNAPSHOT/contract-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/gradle-plugin/0.0.0-SNAPSHOT/gradle-plugin-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/packaging/0.0.0-SNAPSHOT/packaging-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/plugin/cringle.plugin.gradle.plugin/0.0.0-SNAPSHOT/cringle.plugin.gradle.plugin-0.0.0-SNAPSHOT.pom
/tmp/cringle-maven-local/cringle/schema/0.0.0-SNAPSHOT/schema-0.0.0-SNAPSHOT.pom
```

The same five publications, one JAR and one POM each, without the marker of `cringle.project`: a marker is a promise that the plugin exists, and that one comes with #52. `samples/external-plugin-example` is a complete project that does exactly this, and `MavenLocalPublicationTest` checks the same file set in every build. Its `settings.gradle.kts` lists `mavenCentral()` behind the plugin portal because the functional test builds it offline, where the plugin portal cannot serve the libraries the plugin itself needs (coroutines, serialization); a project with a network gets them from the portal.

**Using it in a project.** The plugin comes from `mavenLocal()`, so the build has to look there before the plugin portal. The version belongs into `settings.gradle.kts`, next to the repositories:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
    plugins {
        id("cringle.plugin") version "0.0.0-SNAPSHOT"
    }
}
```

The build script then applies the plugin, takes `contract` from Maven Local to compile against, and describes what the plugin contributes:

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

`./gradlew cringlePackage` writes `build/distributions/acme-orders-1.2.0.cringle`; `cringleValidate` checks the same package against the contract without writing it. Everything the `cringle { }` block contributes has to exist in the project: the provider and driver classes, a schema document per schema, and whatever goes into `lib/` and `binaries/`. The example project has all of it and is the shortest path from nothing to a working package.

**What is not there yet.** The plugin is nowhere but in Maven Local: no Gradle Plugin Portal, no Maven Central, and no Cringle repository of its own (that is #51, and a plugin project will take `contract` and its dependencies from there instead of from `mavenLocal()`). `cringle.project` is declared in the plugin but published nowhere, so it resolves only inside this build until #52 replaces its placeholder with a real implementation.
