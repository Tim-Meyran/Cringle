// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageProblem

/**
 * Renders the findings of the `packaging` library the way the runtime renders them: `<path>: <message>` on one line.
 * Both package kinds are validated the same way, so both tasks report their problems through this.
 */
internal object ProblemRender {

    /** Renders a finding of a validator. */
    fun text(problem: PackageProblem): String = "${problem.path}: ${problem.message}"

    /**
     * Renders a format error. Its message already starts with the path, so nothing has to be added unless the library
     * left the path out.
     */
    fun text(problem: PackageFormatException): String {
        val text = problem.message.orEmpty()
        return if (text.startsWith("${problem.path}: ")) text else "${problem.path}: $text"
    }
}
