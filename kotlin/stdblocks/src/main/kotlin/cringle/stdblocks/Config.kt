// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

/** The configuration of a block as the engine hands it over: numbers may arrive as `Int`, `Long` or `Double`. */
internal fun Map<String, Any?>.long(name: String): Long? = (this[name] as? Number)?.toLong()

internal fun Map<String, Any?>.requireLong(name: String, min: Long): Long {
    val value = long(name) ?: throw IllegalArgumentException("the configuration '$name' is missing")
    require(value >= min) { "the configuration '$name' must be at least $min, not $value" }
    return value
}
