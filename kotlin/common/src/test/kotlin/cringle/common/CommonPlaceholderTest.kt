// SPDX-License-Identifier: Apache-2.0

package cringle.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommonPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-common", CommonPlaceholder.name())
    }
}
