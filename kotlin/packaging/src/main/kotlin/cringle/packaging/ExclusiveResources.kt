// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.TetherType

/**
 * What a blueprint holds that only one running copy can have. A fabric with any of it is updated by stopping the old fabric before the new one starts;
 * a fabric without it is updated side by side (Blue-Green, chapter 14.2).
 */
public object ExclusiveResources {
    /**
     * The exclusive resources of [blueprint], described for a message: those that its blocks declare in their definitions ([definition] resolves
     * `pluginName/blockName`), every `TCP` tether (it listens on a fixed port) and every `SERIAL` tether (it opens a device). Empty if there are none.
     */
    public fun of(blueprint: Blueprint, definition: (String) -> BlockDefinition?): List<String> {
        val found = ArrayList<String>()
        for (b in blueprint.blocks) {
            definition(b.block)?.exclusiveResources?.forEach { found += "block '${b.id}' holds ${it}" }
        }
        for (t in blueprint.tethers) {
            when (t.type) {
                TetherType.TCP -> found += "a TCP tether listens on port ${t.port}"
                TetherType.SERIAL -> found += "a SERIAL tether opens ${t.serial?.device ?: "a device"}"
                else -> {}
            }
        }
        return found
    }
}
