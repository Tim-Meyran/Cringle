// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TrustStoreTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("trust.json")
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun fp(n: Int) = n.toString(16).padStart(64, '0')

    private fun entry(n: Int, kind: TrustKind = TrustKind.COMPONENT, origin: String? = null, address: String? = null) =
        TrustEntry(fp(n), "peer-$n", kind, address, origin, at)

    @Test
    fun entriesSurviveARestart() {
        val store = TrustStore(file)
        val router = entry(1, TrustKind.ROUTER, address = "10.0.0.1:7000")
        val engine = entry(2, TrustKind.ENGINE, origin = fp(1))
        store.add(router)
        store.add(engine)
        val reopened = TrustStore(file)
        assertEquals(listOf(router, engine), reopened.list())
        assertTrue(reopened.isTrusted(fp(1)) && reopened.isTrusted(fp(2)))
        assertFalse(reopened.isTrusted(fp(3)))
    }

    @Test
    fun removingARouterRemovesExactlyTheEntriesItVouchedFor() {
        val store = TrustStore(file)
        store.add(entry(1, TrustKind.ROUTER))
        store.add(entry(2, TrustKind.ENGINE, origin = fp(1)))
        store.add(entry(3, TrustKind.ENGINE, origin = fp(1)))
        store.add(entry(4, TrustKind.ENGINE, origin = fp(9)))
        store.add(entry(5, TrustKind.SERVER))
        assertTrue(store.remove(fp(1)))
        assertEquals(listOf(fp(4), fp(5)), store.list().map { it.fingerprint })
        assertFalse(store.remove(fp(1)))
        assertEquals(listOf(fp(4), fp(5)), TrustStore(file).list().map { it.fingerprint })
    }

    @Test
    fun anEntryWithTheSameFingerprintIsReplaced() {
        val store = TrustStore(file)
        store.add(entry(1))
        store.add(entry(1).copy(name = "renamed"))
        assertEquals(listOf("renamed"), store.list().map { it.name })
    }

    @Test
    fun invalidEntriesAreRefusedAndChangeNothing() {
        val store = TrustStore(file)
        store.add(entry(1))
        val before = Files.readString(file)
        assertThrows<IllegalArgumentException> { store.add(TrustEntry("abc", "x", TrustKind.ENGINE)) }
        assertThrows<IllegalArgumentException> { store.add(TrustEntry(fp(2).uppercase().replace('0', 'A'), "x", TrustKind.ENGINE)) }
        assertThrows<IllegalArgumentException> { store.add(entry(2, origin = "nope")) }
        assertThrows<IllegalArgumentException> { store.add(entry(2, origin = fp(2))) }
        assertEquals(listOf(fp(1)), store.list().map { it.fingerprint })
        assertEquals(before, Files.readString(file))
    }

    @Test
    fun listenersAreToldAboutChangesUntilTheyAreClosed() {
        val store = TrustStore(file)
        val seen = ArrayList<List<String>>()
        val subscription = store.onChange { list -> seen += list.map { it.fingerprint } }
        store.add(entry(1, TrustKind.ROUTER))
        store.add(entry(2, origin = fp(1)))
        store.remove(fp(1))
        assertEquals(listOf(listOf(fp(1)), listOf(fp(1), fp(2)), emptyList()), seen)
        subscription.close()
        store.add(entry(3))
        assertEquals(3, seen.size)
    }

    @Test
    fun theFileIsOwnerOnlyAndNoTemporaryFileIsLeft() {
        val store = TrustStore(file)
        store.add(entry(1))
        assertOwnerOnly(file)
        store.remove(fp(1))
        assertOwnerOnly(file)
        val leftovers = Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList() }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun aMissingFileGivesAnEmptyStoreAndIsNotCreated() {
        val store = TrustStore(file)
        assertEquals(emptyList<TrustEntry>(), store.list())
        assertFalse(Files.exists(file))
    }

    private fun assertOwnerOnly(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views -> assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            "acl" in views -> {
                val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl
                assertEquals(1, acl.size, "access list has other entries: $acl")
                assertEquals(Files.getOwner(file), acl.single().principal())
            }
            else -> fail<Unit>("no POSIX permissions and no ACLs: $views")
        }
    }
}
