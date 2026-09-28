// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PackagingPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-packaging", PackagingPlaceholder.name())
    }
}
