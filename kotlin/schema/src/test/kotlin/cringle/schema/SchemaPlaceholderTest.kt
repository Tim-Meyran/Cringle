// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SchemaPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-schema", SchemaPlaceholder.name())
    }
}
