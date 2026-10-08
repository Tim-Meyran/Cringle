// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.test.TestTls
import io.grpc.Status
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The bindings of service dependencies to fabrics (#171). */
class BindingsTest {
    @TempDir
    lateinit var dir: Path

    private val tls by lazy { TestTls(dir) }
    private val opened = ArrayList<ManagementCore>()

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private fun store() = ManagementStore(dir.resolve("management.json"))

    private fun core(): ManagementCore =
        ManagementCore(store(), tls.identity("ms"), tls.trustStore("ms")).also { opened += it }

    private fun fabric(id: String) = FabricRecord("m1", "e1", id, ByteArray(0), true)

    private fun withFabrics(vararg ids: String): ManagementCore {
        store().save(ManagementData(fabrics = ids.map(::fabric)))
        return core()
    }

    private fun code(block: () -> Unit): Status.Code = assertThrows<ManagementException> { block() }.code

    @Test
    fun bindListAndUnbind() {
        val core = withFabrics("orders-service-0", "other-0")
        core.bind("shop", "orders", listOf("orders-service-0"))
        core.bind("billing", "orders", listOf("orders-service-0"))
        assertEquals(
            listOf(BindingRecord("billing", "orders", listOf("orders-service-0")), BindingRecord("shop", "orders", listOf("orders-service-0"))),
            core.listBindings(""),
        )
        assertEquals(listOf(BindingRecord("shop", "orders", listOf("orders-service-0"))), core.listBindings("shop"))
        core.unbind("shop", "orders")
        assertEquals(listOf("billing"), core.listBindings("").map { it.consumerProject })
    }

    @Test
    fun bindingAnUnknownFabricIsNotFound() {
        val core = withFabrics("a-0")
        assertEquals(Status.Code.NOT_FOUND, code { core.bind("shop", "orders", listOf("nope")) })
        assertEquals(emptyList<BindingRecord>(), core.listBindings(""))
    }

    @Test
    fun bindingTwiceReplacesTheTarget() {
        val core = withFabrics("a-0", "b-0")
        core.bind("shop", "orders", listOf("a-0"))
        core.bind("shop", "orders", listOf("b-0"))
        assertEquals(listOf(BindingRecord("shop", "orders", listOf("b-0"))), core.listBindings("shop"))
    }

    @Test
    fun invalidRequestsAreRefused() {
        val core = withFabrics("a-0")
        assertEquals(Status.Code.INVALID_ARGUMENT, code { core.bind("", "orders", listOf("a-0")) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { core.bind("shop", "Not A Name", listOf("a-0")) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { core.bind("shop", "orders", emptyList()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { core.bind("shop", "orders", listOf("a-0", "a-0")) })
        assertEquals(Status.Code.NOT_FOUND, code { core.unbind("shop", "orders") })
    }

    @Test
    fun bindingsSurviveARestartOfTheManagementServer() {
        withFabrics("a-0").bind("shop", "orders", listOf("a-0"))
        val again = core()
        assertEquals(listOf(BindingRecord("shop", "orders", listOf("a-0"))), again.listBindings(""))
        assertTrue(again.listBindings("other").isEmpty())
    }
}
