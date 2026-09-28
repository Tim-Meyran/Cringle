// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnginePlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-engine", EnginePlaceholder.name())
    }
}
