// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CliPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-cli", CliPlaceholder.name())
    }
}
