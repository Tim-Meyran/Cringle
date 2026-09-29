// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaRegistryTest {
    private fun doc(origin: String, json: String) = SchemaParser.parse(json, origin)

    private val base = doc(
        "plugin-base",
        """{"namespace":"acme.base","types":{"Money":{"record":{"amount":"cringle.std/Int","currency":"Currency"}},"Currency":{"enum":["EUR","USD"]}}}""",
    )
    private val orders = doc(
        "project-orders",
        """{"namespace":"acme.orders","types":{"Order":{"record":{"total":"acme.base/Money","lines":{"list":"Line"}}},"Line":{"record":{"sku":"cringle.std/String"}}}}""",
    )
    private val shop = doc(
        "plugin-shop",
        """{"namespace":"acme.shop","types":{"Cart":{"record":{"order":"acme.orders/Order","price":{"optional":"acme.base/Money"}}}}}""",
    )

    @Test
    fun resolvesQualifiedAndUnqualifiedReferencesAcrossThreeSources() {
        val registry = SchemaRegistry()
        registry.add(base)
        registry.add(orders)
        registry.add(shop)

        assertNotNull(registry.resolve(SchemaRef("acme.base", "Money")))
        assertNotNull(registry.resolve(SchemaRef("acme.orders", "Line")))
        assertNotNull(registry.resolve(SchemaRef("acme.shop", "Cart")))
        assertTrue(registry.resolve(SchemaRef("acme.base", "Currency")) is TypeDef.Enum)
        // Order refers to acme.base/Money (qualified) and to Line (unqualified, same namespace)
        val order = registry.resolve(SchemaRef("acme.orders", "Order")) as TypeDef.Record
        assertEquals(TypeExpr.Ref(SchemaRef("acme.base", "Money")), order.fields.getValue("total"))
        assertEquals(TypeExpr.ListOf(TypeExpr.Ref(SchemaRef("acme.orders", "Line"))), order.fields.getValue("lines"))
        assertEquals(emptyList<SchemaProblem>(), registry.problems())
    }

    @Test
    fun addingDocumentsInAnyOrderGivesTheSameResult() {
        val registry = SchemaRegistry()
        registry.add(shop)
        registry.add(orders)
        registry.add(base)
        assertEquals(emptyList<SchemaProblem>(), registry.problems())
    }

    @Test
    fun unknownNamespacesAndNamesResolveToNull() {
        val registry = SchemaRegistry().also { it.add(base) }
        assertNull(registry.resolve(SchemaRef("acme.base", "Nope")))
        assertNull(registry.resolve(SchemaRef("nope", "Money")))
    }

    @Test
    fun duplicateNamespaceIsAConflictNamingBothOrigins() {
        val registry = SchemaRegistry().also { it.add(base) }
        val other = doc("other-plugin", """{"namespace":"acme.base","types":{}}""")

        val error = assertThrows<SchemaConflictException> { registry.add(other) }

        assertEquals("acme.base", error.namespace)
        assertEquals("plugin-base", error.existingOrigin)
        assertEquals("other-plugin", error.newOrigin)
        assertTrue("plugin-base" in error.message.orEmpty() && "other-plugin" in error.message.orEmpty())
        // the first document stays untouched
        assertNotNull(registry.resolve(SchemaRef("acme.base", "Money")))
    }

    @Test
    fun theStandardNamespaceCannotBeReplaced() {
        val impostor = SchemaDocument("evil", StandardSchemas.NAMESPACE, emptyMap())
        val error = assertThrows<SchemaConflictException> { SchemaRegistry().add(impostor) }
        assertEquals("built-in", error.existingOrigin)
    }

    @Test
    fun unresolvedReferencesAreReportedWithOriginAndPath() {
        val registry = SchemaRegistry().also { it.add(orders) } // acme.base is missing

        val problems = registry.problems()

        assertEquals(
            listOf(SchemaProblem("project-orders", "$.types.Order.record.total", "unresolved reference 'acme.base/Money'")),
            problems,
        )
    }

    @Test
    fun unresolvedReferencesInsideWrappersAreFound() {
        val registry = SchemaRegistry()
        registry.add(doc("d", """{"namespace":"a","types":{"A":{"record":{"x":{"optional":{"list":{"map":"Missing"}}}}}}}"""))

        assertEquals(
            listOf("$.types.A.record.x.optional.list.map"),
            registry.problems().map { it.path },
        )
    }

    @Test
    fun cyclesThroughRequiredFieldsAreReported() {
        val registry = SchemaRegistry()
        registry.add(doc("cyc", """{"namespace":"a","types":{"A":{"record":{"b":"B"}},"B":{"record":{"a":"A"}}}}"""))

        val problems = registry.problems()

        assertEquals(1, problems.size)
        assertEquals("cyc", problems[0].origin)
        assertEquals("$.types.A", problems[0].path)
        assertTrue("a/A -> a/B -> a/A" in problems[0].message, problems[0].message)
    }

    @Test
    fun aRecordThatRequiresItselfIsACycle() {
        val registry = SchemaRegistry()
        registry.add(doc("self", """{"namespace":"a","types":{"A":{"record":{"me":"A"}}}}"""))
        assertEquals(1, registry.problems().size)
    }

    @Test
    fun cyclesAcrossNamespacesAreFoundAndReportedOnce() {
        val registry = SchemaRegistry()
        registry.add(doc("one", """{"namespace":"a","types":{"A":{"record":{"b":"b/B"}}}}"""))
        registry.add(doc("two", """{"namespace":"b","types":{"B":{"record":{"a":"a/A"}}}}"""))
        assertEquals(1, registry.problems().size)
    }

    @Test
    fun recursiveTypesThroughListMapOrOptionalAreAccepted() {
        val registry = SchemaRegistry()
        registry.add(
            doc(
                "tree",
                """{"namespace":"a","types":{
                    "Tree":{"record":{"children":{"list":"Tree"}}},
                    "Dict":{"record":{"entries":{"map":"Dict"}}},
                    "Chain":{"record":{"next":{"optional":"Chain"}}}
                }}""",
            ),
        )
        assertEquals(emptyList<SchemaProblem>(), registry.problems())
    }
}
