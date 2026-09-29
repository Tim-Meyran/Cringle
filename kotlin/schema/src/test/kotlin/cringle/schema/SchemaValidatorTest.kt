// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaValidatorTest {
    private val registry = SchemaRegistry().also {
        it.add(
            SchemaParser.parse(
                """
                {
                  "namespace": "acme.orders",
                  "types": {
                    "Status": { "enum": ["NEW", "PAID", "SHIPPED"] },
                    "Item":   { "record": { "sku": "cringle.std/String", "quantity": "cringle.std/Int" } },
                    "Order":  { "record": {
                      "id": "cringle.std/String",
                      "status": "Status",
                      "items": { "list": "Item" },
                      "labels": { "map": "cringle.std/String" },
                      "comment": { "optional": "cringle.std/String" }
                    } }
                  }
                }
                """.trimIndent(),
                "orders.json",
            ),
        )
    }
    private val validator = SchemaValidator(registry)
    private val order = SchemaRef("acme.orders", "Order")

    private fun errors(json: String, type: SchemaRef) = validator.validate(Json.parseToJsonElement(json), type)

    private fun assertValid(json: String, type: SchemaRef) = assertEquals(emptyList<ValidationError>(), errors(json, type), "value $json")

    private fun assertInvalid(json: String, type: SchemaRef, path: String, fragment: String) {
        val found = errors(json, type)
        assertEquals(1, found.size, "value $json gave $found")
        assertEquals(path, found[0].path)
        assertTrue(fragment in found[0].message, "expected '$fragment' in '${found[0].message}'")
    }

    // --- standard types --------------------------------------------------------------

    @Test
    fun string() {
        assertValid("\"hello\"", StandardSchemas.STRING)
        assertValid("\"\"", StandardSchemas.STRING)
        assertInvalid("5", StandardSchemas.STRING, "$", "expected a string, got a number")
        assertInvalid("true", StandardSchemas.STRING, "$", "got a boolean")
    }

    @Test
    fun boolean() {
        assertValid("true", StandardSchemas.BOOLEAN)
        assertValid("false", StandardSchemas.BOOLEAN)
        assertInvalid("\"true\"", StandardSchemas.BOOLEAN, "$", "expected a boolean, got a string")
        assertInvalid("1", StandardSchemas.BOOLEAN, "$", "got a number")
    }

    @Test
    fun int() {
        for (ok in listOf("0", "-1", "42", "9223372036854775807", "-9223372036854775808")) assertValid(ok, StandardSchemas.INT)
        assertInvalid("9223372036854775808", StandardSchemas.INT, "$", "out of the signed 64-bit range")
        assertInvalid("-9223372036854775809", StandardSchemas.INT, "$", "out of the signed 64-bit range")
        assertInvalid("1.5", StandardSchemas.INT, "$", "without fraction or exponent")
        assertInvalid("1.0", StandardSchemas.INT, "$", "without fraction or exponent")
        assertInvalid("1e2", StandardSchemas.INT, "$", "without fraction or exponent")
        assertInvalid("\"1\"", StandardSchemas.INT, "$", "expected an integer, got a string")
        assertInvalid("true", StandardSchemas.INT, "$", "expected an integer, got a boolean")
    }

    @Test
    fun double() {
        for (ok in listOf("0", "-1.5", "3.14159", "1e10", "1E-7", "2.5e+3")) assertValid(ok, StandardSchemas.DOUBLE)
        assertInvalid("1e999", StandardSchemas.DOUBLE, "$", "not a finite number")
        assertInvalid("\"1.5\"", StandardSchemas.DOUBLE, "$", "expected a number, got a string")
        assertInvalid("false", StandardSchemas.DOUBLE, "$", "got a boolean")
    }

    @Test
    fun bytes() {
        assertValid("\"\"", StandardSchemas.BYTES)
        assertValid("\"aGVsbG8=\"", StandardSchemas.BYTES) // hello
        assertValid("\"AAEC\"", StandardSchemas.BYTES)
        assertInvalid("\"@@@@\"", StandardSchemas.BYTES, "$", "not valid base64")
        assertInvalid("\"aGVsbG8\"", StandardSchemas.BYTES, "$", "not valid base64") // padding missing
        assertInvalid("\"YR==\"", StandardSchemas.BYTES, "$", "not valid base64") // non-canonical trailing bits
        assertInvalid("\"aGVs bG8=\"", StandardSchemas.BYTES, "$", "not valid base64") // whitespace
        assertInvalid("12", StandardSchemas.BYTES, "$", "expected a base64 string")
    }

    @Test
    fun timestamp() {
        assertValid("\"2026-09-29T10:15:30.123Z\"", StandardSchemas.TIMESTAMP)
        assertValid("\"2028-02-29T00:00:00.000Z\"", StandardSchemas.TIMESTAMP) // leap day
        for (bad in listOf(
            "2026-09-29T10:15:30Z", // no fraction
            "2026-09-29T10:15:30.1234Z", // too many digits
            "2026-09-29T10:15:30.123+00:00", // not written as Z
            "2026-09-29T10:15:30.123+02:00",
            "2026-09-29 10:15:30.123Z",
            "2026-02-30T10:15:30.000Z", // impossible date
            "2027-02-29T00:00:00.000Z", // not a leap year
            "2026-09-29T24:00:00.000Z", // impossible time
            "2026-09-29T10:15:60.000Z",
            "yesterday",
        )) {
            assertInvalid("\"$bad\"", StandardSchemas.TIMESTAMP, "$", "not a valid timestamp")
        }
        assertInvalid("1759140930123", StandardSchemas.TIMESTAMP, "$", "expected a timestamp string")
    }

    @Test
    fun emptyAndError() {
        assertValid("{}", StandardSchemas.EMPTY)
        assertInvalid("""{"x":1}""", StandardSchemas.EMPTY, "$.x", "unknown field 'x'")
        assertValid("""{"code":"E1","message":"boom"}""", StandardSchemas.ERROR)
        assertValid("""{"code":"E1","message":"boom","details":{"where":"here"}}""", StandardSchemas.ERROR)
        assertInvalid("""{"code":"E1"}""", StandardSchemas.ERROR, "$", "missing required field 'message'")
        assertInvalid("""{"code":"E1","message":"m","details":{"k":1}}""", StandardSchemas.ERROR, "$.details.k", "expected a string")
    }

    // --- enum, record, list, map ---------------------------------------------------

    @Test
    fun enumeration() {
        val status = SchemaRef("acme.orders", "Status")
        assertValid("\"PAID\"", status)
        assertInvalid("\"paid\"", status, "$", "unknown enum value 'paid'")
        assertInvalid("\"REFUNDED\"", status, "$", "expected one of NEW, PAID, SHIPPED")
        assertInvalid("1", status, "$", "expected a string (an enum value), got a number")
    }

    private val validOrder = """
        {"id":"o-1","status":"NEW","items":[{"sku":"a","quantity":1},{"sku":"b","quantity":2}],"labels":{"vip":"yes"}}
    """.trimIndent()

    @Test
    fun recordWithOptionalFieldAbsentPresentAndNull() {
        assertValid(validOrder, order) // comment absent
        assertValid("""{"id":"o","status":"NEW","items":[],"labels":{},"comment":"hi"}""", order) // present
        assertInvalid(
            """{"id":"o","status":"NEW","items":[],"labels":{},"comment":null}""",
            order,
            "$.comment",
            "null is not a valid value",
        )
        assertInvalid(
            """{"id":"o","status":"NEW","items":[],"labels":{},"comment":5}""",
            order,
            "$.comment",
            "expected a string, got a number",
        )
    }

    @Test
    fun recordFailureModes() {
        assertInvalid("""{"status":"NEW","items":[],"labels":{}}""", order, "$", "missing required field 'id'")
        assertInvalid(
            """{"id":"o","status":"NEW","items":[],"labels":{},"extra":true}""",
            order,
            "$.extra",
            "unknown field 'extra'",
        )
        assertInvalid("""{"id":null,"status":"NEW","items":[],"labels":{}}""", order, "$.id", "null is not a valid value")
        assertInvalid("[]", order, "$", "expected an object, got an array")
        assertInvalid("\"x\"", order, "$", "expected an object, got a string")
        assertInvalid("null", order, "$", "null is not a valid value")
    }

    @Test
    fun nestedErrorsCarryTheirPaths() {
        val found = errors(
            """{"id":"o","status":"PAYED","items":[{"sku":"a","quantity":1},{"sku":"b","quantity":2},{"sku":7,"quantity":"3"}],"labels":{"vip":1}}""",
            order,
        )
        assertEquals(
            listOf("$.status", "$.items[2].sku", "$.items[2].quantity", "$.labels.vip"),
            found.map { it.path },
        )
    }

    @Test
    fun listAndMapFailureModes() {
        assertInvalid("""{"id":"o","status":"NEW","items":{},"labels":{}}""", order, "$.items", "expected an array, got an object")
        assertInvalid("""{"id":"o","status":"NEW","items":[],"labels":[]}""", order, "$.labels", "expected an object, got an array")
        assertInvalid(
            """{"id":"o","status":"NEW","items":[[]],"labels":{}}""",
            order,
            "$.items[0]",
            "expected an object, got an array",
        )
    }

    @Test
    fun mapKeysThatAreNotSimpleUseBracketPaths() {
        assertInvalid(
            """{"id":"o","status":"NEW","items":[],"labels":{"a key":1}}""",
            order,
            "$.labels[\"a key\"]",
            "expected a string",
        )
    }

    @Test
    fun aRecursiveTypeIsValidatedToItsDepth() {
        val local = SchemaRegistry().also {
            it.add(SchemaParser.parse("""{"namespace":"a","types":{"Tree":{"record":{"value":"cringle.std/Int","children":{"list":"Tree"}}}}}""", "t"))
        }
        val tree = SchemaRef("a", "Tree")
        val v = SchemaValidator(local)
        assertEquals(emptyList<ValidationError>(), v.validate(Json.parseToJsonElement("""{"value":1,"children":[{"value":2,"children":[]}]}"""), tree))
        val found = v.validate(Json.parseToJsonElement("""{"value":1,"children":[{"value":"x","children":[]}]}"""), tree)
        assertEquals(listOf("$.children[0].value"), found.map { it.path })
    }

    // --- unknown types --------------------------------------------------------------

    @Test
    fun anUnknownTypeYieldsOneErrorAndNeverThrows() {
        val found = errors("{}", SchemaRef("nope", "Missing"))
        assertEquals(listOf(ValidationError("$", "unknown type 'nope/Missing'")), found)
    }

    @Test
    fun aReferenceToAnUnknownTypeInsideARecordIsAnError() {
        val local = SchemaRegistry().also { it.add(SchemaParser.parse("""{"namespace":"a","types":{"A":{"record":{"f":"Missing"}}}}""", "d")) }
        val found = SchemaValidator(local).validate(Json.parseToJsonElement("""{"f":1}"""), SchemaRef("a", "A"))
        assertEquals(listOf(ValidationError("$.f", "unknown type 'a/Missing'")), found)
    }
}
