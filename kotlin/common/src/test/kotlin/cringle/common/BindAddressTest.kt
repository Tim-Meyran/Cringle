// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BindAddressTest {
    @Test
    fun nothingEmptyAndLoopbackMeanTheLoopbackInterface() {
        for (text in listOf(null, "", "  ", "loopback", "LOOPBACK")) {
            val address = BindAddress.socketAddress(text, 7500)
            assertTrue(address.address.isLoopbackAddress, "$text")
            assertEquals(7500, address.port)
            assertFalse(BindAddress.isOpen(text), "$text")
        }
    }

    @Test
    fun allMeansEveryInterface() {
        val address = BindAddress.socketAddress("all", 7500)
        assertTrue(address.address.isAnyLocalAddress)
        assertEquals(7500, address.port)
        assertTrue(BindAddress.isOpen("all"))
        assertTrue(BindAddress.isOpen("ALL"))
    }

    @Test
    fun anAddressIsUsedAsItIs() {
        assertEquals(InetAddress.getByName("192.0.2.7"), BindAddress.socketAddress("192.0.2.7", 1).address)
        assertEquals(InetAddress.getByName("::1"), BindAddress.socketAddress("::1", 1).address)
        assertEquals(InetAddress.getByName("0.0.0.0"), BindAddress.socketAddress("0.0.0.0", 1).address)
        assertTrue(BindAddress.isOpen("192.0.2.7"))
    }

    @Test
    fun theComponentsOfAMachineTakeLoopbackOrAllOnly() {
        assertNull(BindAddress.interfaceChoice(null))
        assertNull(BindAddress.interfaceChoice(" loopback "))
        assertEquals("all", BindAddress.interfaceChoice("All"))
        assertThrows<IllegalArgumentException> { BindAddress.interfaceChoice("192.0.2.7") }
    }

    @Test
    fun aNameThatDoesNotResolveIsRefusedWithAMessage() {
        val e = assertThrows<IllegalArgumentException> { BindAddress.socketAddress("no-such-host.invalid", 1) }
        assertTrue(e.message!!.contains("no-such-host.invalid") && e.message!!.contains("loopback, all"), e.message)
    }

    @Test
    fun theEnvironmentVariableIsReadAndEmptyMeansUnset() {
        assertEquals("all", BindAddress.fromEnvironment(mapOf("CRINGLE_BIND" to " all ")))
        assertNull(BindAddress.fromEnvironment(mapOf("CRINGLE_BIND" to " ")))
        assertNull(BindAddress.fromEnvironment(emptyMap()))
    }
}
