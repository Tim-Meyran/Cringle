# Samples

| Sample | What it shows | Where to read on |
|---|---|---|
| [`kotlin/gradle-plugin/samples/sample-plugin`](../kotlin/gradle-plugin/samples/sample-plugin) | A plugin `acme-orders`: provider, driver, block `orders` with ports and schemas, a test with the testkit (`OrdersBlockTest`), and data migrations (`OrdersUpdate`, `OrdersDowngrade`, a `SteppedProcessor`) named in the `processors { }` block | [gradle-plugin.md](../docs/gradle-plugin.md), [migration-guide.md](../docs/migration-guide.md) |
| [`kotlin/gradle-plugin/samples/sample-project`](../kotlin/gradle-plugin/samples/sample-project) | A project `acme-shop` without code: blueprints, schemas, binaries, a fabric with roles and labels, and a processor for the data of the whole instance | [gradle-plugin.md](../docs/gradle-plugin.md) |
| [`kotlin/gradle-plugin/samples/external-plugin-example`](../kotlin/gradle-plugin/samples/external-plugin-example), `external-project-example` | The same, built from outside this repository (plugin from Maven Local) | [gradle-plugin.md](../docs/gradle-plugin.md) |
| [`samples/shared-service`](shared-service) | Two projects: `orders-service` provides the service `orders`, `shop` uses it; bindings and failover | [shared-services.md](../docs/shared-services.md) |

`PluginPackageFunctionalTest`, `ProjectPackageFunctionalTest` (module `gradle-plugin`) build the Gradle samples with TestKit and `SamplesTest` (module `packaging`) reads `shared-service`, so they stay valid with the format. A new project starts best by copying `sample-plugin` and `sample-project`; the way from there to a running application is [getting-started.md](../docs/getting-started.md).
