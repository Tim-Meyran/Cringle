// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.management.ManagementCore
import cringle.packaging.PluginPackage
import cringle.schema.SchemaConflictException
import cringle.schema.SchemaParseException
import cringle.schema.SchemaParser
import cringle.schema.SchemaRegistry
import java.util.concurrent.ConcurrentHashMap

/** The blocks of the plugins in the repository, as the blueprint editor offers and checks them: each plugin in its highest version. */
internal class PluginCatalog(private val core: ManagementCore) {
    private val packages = ConcurrentHashMap<String, PluginPackage>()

    /** The state of the repository at one moment: the plugin packages and the schemas of all of them. */
    class Snapshot(val plugins: List<PluginPackage>) {
        val registry: SchemaRegistry = SchemaRegistry().also { registry ->
            for (plugin in plugins) {
                for ((entry, text) in plugin.schemas) {
                    try {
                        registry.add(SchemaParser.parse(text, "${plugin.manifest.name}@${plugin.manifest.version}:$entry"))
                    } catch (e: SchemaParseException) {
                        // the schema of a broken plugin is simply not known
                    } catch (e: SchemaConflictException) {
                    }
                }
            }
        }

        /** The definition of `plugin/block`, or `null`. */
        fun block(reference: String): BlockDefinition? {
            val plugin = reference.substringBefore('/', "")
            val name = reference.substringAfter('/', "")
            return plugins.firstOrNull { it.manifest.name == plugin }?.manifest?.blocks?.firstOrNull { it.name == name }
        }
    }

    /** Reads the current plugins; a package is downloaded once per version. */
    suspend fun snapshot(): Snapshot = Snapshot(
        core.latestPlugins().map { (name, version) -> packages[name + "@" + version] ?: core.readPlugin(name, version).also { packages[name + "@" + version] = it } },
    )
}
