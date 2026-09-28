// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CringlePluginPlaceholderTest {
    @Test
    fun placeholderTest() {
        val plugin = CringlePluginPlaceholder()
        assertNotNull(plugin)
    }
}
