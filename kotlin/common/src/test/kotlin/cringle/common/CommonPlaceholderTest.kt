// SPDX-License-Identifier: Apache-2.0

package cringle.common

import cringle.dummy.v1.DummyMessage
import cringle.dummy.v1.dummyMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CommonPlaceholderTest {
    @Test
    fun placeholderTest() {
        assertEquals("cringle-common", CommonPlaceholder.name())
    }

    @Test
    fun protoGenerationTest() {
        val message: DummyMessage = dummyMessage {
            id = "test-123"
            content = "hello proto"
        }
        assertNotNull(message)
        assertEquals("test-123", message.id)
        assertEquals("hello proto", message.content)
    }
}
