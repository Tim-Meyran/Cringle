// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.packaging.Blueprint
import cringle.packaging.PackageProblem

/** A problem of a blueprint as the editor shows it: [text] says which block or tether it is about, [blocks] are the ids of the blocks to mark. */
internal data class DescribedProblem(val text: String, val blocks: List<String>, val path: String)

/**
 * Turns the problems of the validator, which point into the blueprint by index (`$.blocks[2].config`, `$.tethers[0].type`), into text a reader does not have to
 * count blocks for: `Block trig (cringle-std/flow.trigger), config: missing required field 'x'`, `Tether a.out → b.in: …`. A problem that names no block or tether keeps
 * its path and has no blocks.
 */
internal object BlueprintProblems {
    private val block = Regex("""\$\.blocks\[(\d+)]([^\s]*)""")
    private val tether = Regex("""\$\.tethers\[(\d+)]([^\s]*)""")

    fun describe(problems: List<PackageProblem>, blueprint: Blueprint): List<DescribedProblem> = problems.map { describe(it, blueprint) }

    fun describe(problem: PackageProblem, blueprint: Blueprint): DescribedProblem {
        block.find(problem.path)?.let { m ->
            val b = blueprint.blocks.getOrNull(m.groupValues[1].toInt())
            if (b != null) return DescribedProblem("Block ${b.id} (${b.block})${place(m.groupValues[2])}: ${problem.message}", listOf(b.id), problem.path)
        }
        tether.find(problem.path)?.let { m ->
            val t = blueprint.tethers.getOrNull(m.groupValues[1].toInt())
            if (t != null) {
                fun end(e: cringle.packaging.Endpoint?, remote: String) = e?.let { "${it.block}.${it.port}${it.index?.let { i -> "[$i]" }.orEmpty()}" } ?: remote
                val remote = if (t.service != null) "service ${t.service}" else "another engine"
                val blocks = listOfNotNull(t.from?.block, t.to?.block).distinct()
                return DescribedProblem("Tether ${end(t.from, remote)} → ${end(t.to, remote)}${place(m.groupValues[2])}: ${problem.message}", blocks, problem.path)
            }
        }
        return DescribedProblem(problem.message, emptyList(), problem.path)
    }

    /** `.config.limit` → `, config.limit`; nothing for the block itself. */
    private fun place(rest: String): String = rest.removePrefix(".").takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
}
