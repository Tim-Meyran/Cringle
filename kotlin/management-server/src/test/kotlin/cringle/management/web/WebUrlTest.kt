// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WebUrlTest {
    @Test
    fun anHttpsUrlWithoutPathIsAccepted() {
        assertEquals("https://cringle.example", checkWebUrl("https://cringle.example"))
        assertEquals("https://cringle.example:8443", checkWebUrl("https://cringle.example:8443/"))
        assertEquals("https://[::1]:8443", checkWebUrl(" https://[::1]:8443 "))
    }

    @Test
    fun everythingElseIsRefused() {
        for (url in listOf("http://cringle.example", "cringle.example", "https://", "https://u:p@cringle.example", "https://cringle.example/app", "https://cringle.example?x=1", "https://cringle.example#token", "ftp://x", "https://bad host")) {
            assertThrows<IllegalArgumentException>(url) { checkWebUrl(url) }
        }
    }
}
