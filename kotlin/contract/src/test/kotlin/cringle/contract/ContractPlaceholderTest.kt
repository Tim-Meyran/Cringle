// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContractPlaceholderTest {
    @Test
    fun placeholderTest() {
        val placeholder = object : ContractPlaceholder {}
        assertEquals("cringle-contract", placeholder.name())
    }
}
