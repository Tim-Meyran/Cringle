// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestkitPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-testkit", TestkitPlaceholder.name())
    }
}
