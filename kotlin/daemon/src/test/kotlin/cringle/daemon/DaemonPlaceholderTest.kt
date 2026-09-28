// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DaemonPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-daemon", DaemonPlaceholder.name())
    }
}
