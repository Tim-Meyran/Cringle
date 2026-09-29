// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.schema.SchemaParser
import cringle.schema.SchemaRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** The full example of `spec/package-format.md` (section 8). */
object Fixtures {
    val ordersSchema = """
        {
          "namespace": "acme.orders",
          "types": {
            "Order": { "record": { "id": "cringle.std/String", "total": "cringle.std/Int" } },
            "SinkConfig": { "record": { "limit": "cringle.std/Int", "label": { "optional": "cringle.std/String" } } }
          }
        }
    """.trimIndent()

    val pluginManifest = """
        {
          "format": 1,
          "kind": "plugin",
          "name": "acme-orders",
          "version": "1.2.0",
          "dependencies": { "cringle-core": "^1.0.0" },
          "providers": ["com.acme.orders.OrdersProvider"],
          "drivers": [],
          "blocks": [
            {
              "name": "order-source",
              "schemas": ["acme.orders/Order"],
              "ports": [
                { "name": "out", "direction": "OUT", "tetherTypes": ["MESSAGE", "STREAM"], "schema": "acme.orders/Order" }
              ],
              "requiredDrivers": []
            },
            {
              "name": "order-sink",
              "schemas": ["acme.orders/Order"],
              "ports": [
                { "name": "in", "direction": "IN", "tetherTypes": ["MESSAGE"], "schema": "acme.orders/Order" },
                { "name": "replicas", "direction": "IN", "tetherTypes": ["MESSAGE"], "schema": "acme.orders/Order", "varArg": true }
              ],
              "requiredDrivers": ["logging"],
              "configSchema": "acme.orders/SinkConfig"
            },
            {
              "name": "text-sink",
              "ports": [
                { "name": "in", "direction": "IN", "tetherTypes": ["MESSAGE"], "schema": "cringle.std/String" }
              ]
            }
          ],
          "libs": ["lib/acme-orders.jar"],
          "schemas": ["schemas/orders.json"],
          "processors": { "update": "com.acme.orders.Migrate", "downgrade": "com.acme.orders.Rollback" }
        }
    """.trimIndent()

    val blueprint = """
        {
          "name": "main",
          "blocks": [
            { "id": "source", "block": "acme-orders/order-source" },
            {
              "id": "sink",
              "block": "acme-orders/order-sink",
              "config": { "limit": 10 },
              "isolation": "PROCESS",
              "varArgCounts": { "replicas": 2 }
            }
          ],
          "tethers": [
            { "type": "MESSAGE", "from": { "block": "source", "port": "out" }, "to": { "block": "sink", "port": "in" } },
            { "type": "MESSAGE", "from": { "block": "source", "port": "out" }, "to": { "block": "sink", "port": "replicas", "index": 1 } }
          ]
        }
    """.trimIndent()

    val projectManifest = """
        {
          "format": 1,
          "kind": "project",
          "name": "shop",
          "version": "0.3.1",
          "dependencies": { "acme-orders": "~1.2.0" },
          "blueprints": ["blueprints/main.json"],
          "schemas": [],
          "fabrics": [
            { "blueprint": "main", "instances": 2, "roles": ["edge"], "labels": { "zone": "a" } }
          ]
        }
    """.trimIndent()

    val plugin: PluginManifest = ManifestJson.parsePlugin(pluginManifest)
    val project: ProjectManifest = ManifestJson.parseProject(projectManifest)
    val main: Blueprint = ManifestJson.parseBlueprint(blueprint, "blueprints/main.json")

    fun pluginPackage(): PluginPackage = PluginPackage(plugin, mapOf("schemas/orders.json" to ordersSchema), emptyList())

    fun projectPackage(blueprints: List<Blueprint> = listOf(main), manifest: ProjectManifest = project): ProjectPackage =
        ProjectPackage(manifest, blueprints, emptyMap(), emptyList())

    fun registry(): SchemaRegistry = SchemaRegistry().also { it.add(SchemaParser.parse(ordersSchema, "test")) }

    fun writeProject(dir: Path, name: String = "project.zip"): Path {
        val file = dir.resolve(name)
        Files.newOutputStream(file).use {
            PackageWriter.writeProject(
                project,
                listOf(main),
                emptyMap(),
                mapOf("binaries/site/index.html" to "<html/>".toByteArray()),
                it,
            )
        }
        return file
    }

    fun writePlugin(dir: Path, name: String = "plugin.zip"): Path {
        val file = dir.resolve(name)
        Files.newOutputStream(file).use {
            PackageWriter.writePlugin(
                plugin,
                mapOf("schemas/orders.json" to ordersSchema),
                mapOf("lib/acme-orders.jar" to byteArrayOf(1, 2, 3)),
                emptyMap(),
                it,
            )
        }
        return file
    }

    /** Builds an arbitrary ZIP, including entries the writer would refuse. */
    fun rawZip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
