// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.engine.tether.toJson

/** How the engine shows a value that crossed a tether to a person (the debugger and the monitor): JSON text, bytes as a short hex dump, shortened. */
internal object PayloadText {
    /** The longest text a record of a value carries. */
    const val MAX: Int = 4096

    /** [payload] as text of at most [max] characters (a shortened one ends with `...`). */
    fun of(payload: Any?, max: Int = MAX): String {
        val text = when (payload) {
            is ByteArray -> "${payload.size} bytes: " + payload.take(32).joinToString(" ") { "%02x".format(it) } + if (payload.size > 32) " ..." else ""
            else -> try {
                toJson(payload).toString()
            } catch (e: IllegalArgumentException) {
                payload.toString()
            }
        }
        return if (text.length > max) text.take(max) + "..." else text
    }
}
