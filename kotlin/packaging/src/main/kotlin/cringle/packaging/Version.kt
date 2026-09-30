// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import java.math.BigInteger

/** A semantic version `MAJOR.MINOR.PATCH[-prerelease]` (build metadata is not part of the package format). */
public class Version(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
    prerelease: List<String> = emptyList(),
) : Comparable<Version> {
    /** Dot separated prerelease identifiers; empty for a release. */
    public val prerelease: List<String> = prerelease.toList()

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "version numbers must not be negative" }
        require(this.prerelease.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' } }) {
            "invalid prerelease identifiers $prerelease"
        }
        // Without this, "1.0.0-1" and "1.0.0-01" would be equal but have different hash codes.
        require(this.prerelease.none { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) {
            "numeric prerelease identifiers must not have leading zeros: $prerelease"
        }
    }

    /** Whether this is a prerelease. */
    public val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return other.prerelease.size.coerceAtMost(1) - prerelease.size.coerceAtMost(1)
        }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val c = compareIdentifier(prerelease[i], other.prerelease[i])
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    private fun compareIdentifier(a: String, b: String): Int {
        val aNum = a.all { it.isDigit() }
        val bNum = b.all { it.isDigit() }
        return when {
            aNum && bNum -> BigInteger(a).compareTo(BigInteger(b))
            aNum -> -1
            bNum -> 1
            else -> a.compareTo(b)
        }
    }

    internal fun sameTuple(other: Version): Boolean = major == other.major && minor == other.minor && patch == other.patch

    /**
     * Two versions are the same when they order the same, which for valid versions means they are written the same
     * (no leading zeros, no build metadata). The hash code uses the same parts, so equal versions always hash alike.
     */
    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0

    override fun hashCode(): Int = listOf(major, minor, patch, prerelease).hashCode()

    override fun toString(): String = "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    public companion object {
        /** Parses [text]; throws [IllegalArgumentException] if it is not a version of [PackageNames]. */
        public fun parse(text: String): Version {
            val problem = PackageNames.versionProblem(text)
            if (problem != null) throw IllegalArgumentException(problem)
            val core = text.substringBefore('-')
            val (major, minor, patch) = core.split('.').map { it.toInt() }
            val pre = if ('-' in text) text.substringAfter('-').split('.') else emptyList()
            return Version(major, minor, patch, pre)
        }
    }
}

/** An npm-style version range. See `spec/versioning.md` for the grammar and semantics. */
public class VersionRange private constructor(private val text: String, private val sets: List<List<Bound>>) {
    private enum class Op { EQ, GT, GTE, LT, LTE }

    private class Bound(val op: Op, val version: Version) {
        fun test(v: Version): Boolean = when (op) {
            Op.EQ -> v.compareTo(version) == 0
            Op.GT -> v > version
            Op.GTE -> v >= version
            Op.LT -> v < version
            Op.LTE -> v <= version
        }
    }

    /** Whether [version] satisfies this range, including the npm prerelease rule. */
    public fun matches(version: Version): Boolean = sets.any { set ->
        set.all { it.test(version) } &&
            (!version.isPrerelease || set.any { it.version.isPrerelease && it.version.sameTuple(version) })
    }

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is VersionRange && text == other.text

    override fun hashCode(): Int = text.hashCode()

    public companion object {
        private val token = Regex("""([<>]=?|=|\^|~)?\s*([^\s<>=^~|]+)""")

        /** Parses [text]; throws [IllegalArgumentException] with a readable message. */
        public fun parse(text: String): VersionRange {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("version range must not be blank")
            if (" - " in trimmed) throw IllegalArgumentException("hyphen ranges are not supported: '$trimmed'")
            val sets = trimmed.split("||").map { part ->
                val p = part.trim()
                if (p.isEmpty()) throw IllegalArgumentException("empty alternative in version range '$trimmed'")
                parseSet(p, trimmed)
            }
            return VersionRange(trimmed, sets)
        }

        private fun parseSet(part: String, whole: String): List<Bound> {
            val bounds = ArrayList<Bound>()
            var rest = part
            while (rest.isNotEmpty()) {
                val m = token.matchAt(rest, 0) ?: throw IllegalArgumentException("cannot parse '$rest' in version range '$whole'")
                bounds += parseToken(m.groupValues[1], m.groupValues[2], whole)
                rest = rest.substring(m.range.last + 1).trim()
            }
            return bounds
        }

        private class Partial(val major: Int, val minor: Int?, val patch: Int?, val pre: List<String>)

        private fun partial(text: String, whole: String): Partial? {
            if (text == "*" || text.equals("x", true)) return null
            val core = text.substringBefore('-')
            val parts = core.split('.')
            if (parts.size > 3) throw IllegalArgumentException("invalid version '$text' in range '$whole'")
            val nums = ArrayList<Int>()
            for ((i, p) in parts.withIndex()) {
                if (p == "*" || p.equals("x", true)) {
                    if (i == 0) return null
                    break
                }
                nums += p.toIntOrNull()?.takeIf { p.all(Char::isDigit) && (p == "0" || !p.startsWith("0")) }
                    ?: throw IllegalArgumentException("invalid version '$text' in range '$whole'")
            }
            val pre = if ('-' in text) {
                if (nums.size != 3) throw IllegalArgumentException("prerelease needs a full version: '$text' in range '$whole'")
                Version.parse(text).prerelease
            } else {
                emptyList()
            }
            return Partial(nums[0], nums.getOrNull(1), nums.getOrNull(2), pre)
        }

        private fun low(p: Partial) = Version(p.major, p.minor ?: 0, p.patch ?: 0, p.pre)

        private fun below(major: Int, minor: Int, patch: Int) = Version(major, minor, patch, listOf("0"))

        private fun parseToken(op: String, text: String, whole: String): List<Bound> {
            val p = partial(text, whole)
            if (p == null) {
                if (op == "" || op == "=" || op == "^" || op == "~") return listOf(Bound(Op.GTE, Version(0, 0, 0)))
                throw IllegalArgumentException("comparator '$op$text' needs a full version in range '$whole'")
            }
            val full = p.minor != null && p.patch != null
            return when (op) {
                "^" -> listOf(Bound(Op.GTE, low(p)), Bound(Op.LT, caretUpper(p)))
                "~" -> listOf(Bound(Op.GTE, low(p)), Bound(Op.LT, if (p.minor == null) below(p.major + 1, 0, 0) else below(p.major, p.minor + 1, 0)))
                "", "=" -> if (full) {
                    listOf(Bound(Op.EQ, low(p)))
                } else {
                    listOf(Bound(Op.GTE, low(p)), Bound(Op.LT, if (p.minor == null) below(p.major + 1, 0, 0) else below(p.major, p.minor + 1, 0)))
                }
                else -> {
                    if (!full) throw IllegalArgumentException("comparator '$op$text' needs a full version in range '$whole'")
                    listOf(Bound(Op.valueOf(mapOf(">" to "GT", ">=" to "GTE", "<" to "LT", "<=" to "LTE").getValue(op)), low(p)))
                }
            }
        }

        private fun caretUpper(p: Partial): Version = when {
            p.major > 0 -> below(p.major + 1, 0, 0)
            p.minor == null -> below(1, 0, 0)
            p.minor > 0 -> below(0, p.minor + 1, 0)
            p.patch == null -> below(0, 1, 0)
            else -> below(0, 0, p.patch + 1)
        }
    }
}
