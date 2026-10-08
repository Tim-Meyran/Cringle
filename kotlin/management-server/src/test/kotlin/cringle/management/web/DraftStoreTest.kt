// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DraftStoreTest {
    @TempDir
    lateinit var dir: Path

    private fun content(v: String) = JsonObject(mapOf("namespace" to JsonPrimitive(v)))

    @Test
    fun roundTripListAndDelete() {
        val store = DraftStore(dir)
        assertNull(store.load("schema", "orders"))
        val first = store.save("schema", "orders", "1.0.0", content("a"))
        assertEquals(1L, first.revision)
        val second = store.save("schema", "orders", "1.1.0", content("b"))
        assertEquals(2L, second.revision)
        assertEquals(second, store.load("schema", "orders"))
        store.save("project", "shop", "1.0.0", content("c"))
        assertEquals(listOf("project/shop", "schema/orders"), store.list().map { "${it.kind}/${it.name}" }.sortedBy { it.substringBefore('/') })
        assertEquals(listOf("orders"), store.list("schema").map { it.name })
        assertTrue(store.delete("schema", "orders"))
        assertFalse(store.delete("schema", "orders"))
        assertTrue(Files.list(dir.resolve("schema")).use { it.toList() }.isEmpty(), "no temporary files stay")
    }

    @Test
    fun aStaleRevisionIsRefusedAndWritesNothing() {
        val store = DraftStore(dir)
        store.save("schema", "orders", "1.0.0", content("a"), expectedRevision = 0)
        store.save("schema", "orders", "1.0.0", content("b"), expectedRevision = 1)
        assertThrows<DraftConflictException> { store.save("schema", "orders", "1.0.0", content("c"), expectedRevision = 1) }
        assertThrows<DraftConflictException> { store.save("schema", "new", "1.0.0", content("c"), expectedRevision = 3) }
        assertEquals(content("b"), store.load("schema", "orders")!!.content)
        assertNull(store.load("schema", "new"))
    }

    @Test
    fun namesAndKindsThatCouldLeaveTheFolderAreRejected() {
        val store = DraftStore(dir.resolve("drafts"))
        for (name in listOf("..", "../x", "a/b", "a\\b", "", "A", "con", "x.", ".x")) {
            assertThrows<DraftException>(name) { store.save("schema", name, "1.0.0", content("a")) }
            assertThrows<DraftException>(name) { store.load("schema", name) }
        }
        assertThrows<DraftException> { store.save("../schema", "x", "1.0.0", content("a")) }
        assertFalse(Files.exists(dir.resolve("x.json")))
    }
}
