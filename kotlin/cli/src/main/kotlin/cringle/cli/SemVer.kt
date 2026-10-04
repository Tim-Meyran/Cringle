// SPDX-License-Identifier: Apache-2.0

package cringle.cli

/**
 * A version like `1.2.3` or `1.2.3-rc.1`. A pre-release sorts before the release of the same numbers
 * (`1.2.3-rc.1 < 1.2.3`), as SemVer defines it.
 */
internal data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        val numbers = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (numbers != 0) return numbers
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")

        /** Parses `MAJOR.MINOR.PATCH` with an optional `-pre-release`; null if [text] is not such a version. */
        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { null })
        }

        /** Like [parse], but throws [IllegalArgumentException] with a message naming [text]. */
        fun parseOrThrow(text: String): SemVer =
            parse(text) ?: throw IllegalArgumentException("'$text' is not a version like 1.2.3 or 1.2.3-rc.1")

        /** SemVer rule 11: a pre-release is lower than the release; numeric identifiers compare numerically. */
        private fun comparePreRelease(a: String?, b: String?): Int {
            if (a == null && b == null) return 0
            if (a == null) return 1
            if (b == null) return -1
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrNull(i) ?: return -1
                val q = y.getOrNull(i) ?: return 1
                val pn = p.toIntOrNull()
                val qn = q.toIntOrNull()
                val c = when {
                    pn != null && qn != null -> pn.compareTo(qn)
                    pn != null -> -1
                    qn != null -> 1
                    else -> p.compareTo(q)
                }
                if (c != 0) return c
            }
            return 0
        }
    }
}
