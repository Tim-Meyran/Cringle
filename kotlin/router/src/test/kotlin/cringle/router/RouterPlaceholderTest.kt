// SPDX-License-Identifier: Apache-2.0

package cringle.router

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouterPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-router", RouterPlaceholder.name())
    }
}
