// SPDX-License-Identifier: Apache-2.0

package cringle.management

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagementServerPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-management-server", ManagementServerPlaceholder.name())
    }
}
