// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RepositoryPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-repository", RepositoryPlaceholder.name())
    }
}
