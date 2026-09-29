// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackageIoTest {
    @TempDir
    lateinit var dir: Path

    private fun zip(name: String, entries: Map<String, String>): Path =
        dir.resolve(name).also { Files.write(it, Fixtures.rawZip(entries)) }

    private val projectEntries = mapOf(
        "cringle-project.json" to Fixtures.projectManifest,
        "blueprints/main.json" to Fixtures.blueprint,
    )

    @Test
    fun projectRoundTripYieldsIdenticalModel() {
        val pkg = PackageReader.readProject(Fixtures.writeProject(dir))
        assertEquals(Fixtures.project, pkg.manifest)
        assertEquals(listOf(Fixtures.main), pkg.blueprints)
        assertEquals(listOf("binaries/site/index.html", "blueprints/main.json", "cringle-project.json"), pkg.files)
    }

    @Test
    fun pluginRoundTripYieldsIdenticalModel() {
        val pkg = PackageReader.readPlugin(Fixtures.writePlugin(dir))
        assertEquals(Fixtures.plugin, pkg.manifest)
        assertEquals(mapOf("schemas/orders.json" to Fixtures.ordersSchema), pkg.schemas)
        assertTrue("lib/acme-orders.jar" in pkg.files)
    }

    @Test
    fun writingIsDeterministicSoTheHashIsReproducible() {
        val a = Fixtures.writeProject(dir, "a.zip")
        val b = Fixtures.writeProject(dir, "b.zip")
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b))
        assertEquals(PackageHash.sha256(a), PackageHash.sha256(b))
    }

    @Test
    fun hashIsSha256OfTheFileBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PackageHash.sha256("abc".toByteArray()))
        val f = dir.resolve("abc.bin").also { Files.write(it, "abc".toByteArray()) }
        assertEquals(PackageHash.sha256("abc".toByteArray()), PackageHash.sha256(f))
    }

    @Test
    fun hashVerificationAcceptsMatchAndRejectsMismatch() {
        val file = Fixtures.writePlugin(dir)
        val hash = PackageHash.sha256(file)
        PackageHash.verify(file, hash)
        PackageHash.verify(file, hash.uppercase())
        val e = assertThrows<PackageHashMismatchException> { PackageHash.verify(file, "0".repeat(64)) }
        assertEquals(hash, e.actual)
        Files.write(file, Files.readAllBytes(file) + 0)
        assertThrows<PackageHashMismatchException> { PackageHash.verify(file, hash) }
    }

    @Test
    fun kindIsDetectedFromTheManifest() {
        assertEquals(PackageKind.PROJECT, PackageReader.kind(Fixtures.writeProject(dir)))
        assertEquals(PackageKind.PLUGIN, PackageReader.kind(Fixtures.writePlugin(dir)))
        assertThrows<PackageFormatException> { PackageReader.kind(zip("empty.zip", mapOf("x.txt" to ""))) }
    }

    @Test
    fun readerRejectsZipSlipEntries() {
        for (name in listOf("../evil.txt", "binaries/../../evil.txt", "/abs.txt", "binaries\\..\\evil.txt", "C:/evil.txt", "binaries//x")) {
            val e = assertThrows<PackageFormatException>(name) {
                PackageReader.readProject(zip("slip.zip", projectEntries + (name to "x")))
            }
            assertEquals(name, e.path)
        }
    }

    @Test
    fun readerRejectsStructuralProblems() {
        fun problem(entries: Map<String, String>, kind: PackageKind = PackageKind.PROJECT): String = assertThrows<PackageFormatException> {
            val f = zip("s.zip", entries)
            if (kind == PackageKind.PROJECT) PackageReader.readProject(f) else PackageReader.readPlugin(f)
        }.message!!
        assertTrue(problem(mapOf("blueprints/main.json" to "{}")).contains("missing cringle-project.json"))
        assertTrue(problem(projectEntries - "blueprints/main.json").contains("missing from the package"))
        assertTrue(problem(projectEntries + ("blueprints/extra.json" to "{}")).contains("not listed in the manifest"))
        assertTrue(problem(projectEntries + ("notes.txt" to "hi")).contains("unexpected entry"))
        assertTrue(problem(projectEntries + ("cringle-plugin.json" to Fixtures.pluginManifest)).contains("unexpected manifest"))
        assertTrue(problem(projectEntries + ("blueprints/main.json" to Fixtures.blueprint.replace("\"main\"", "\"other\""))).contains("must match the file name"))
        assertTrue(problem(projectEntries + ("blueprints/main.json" to "{")).contains("malformed JSON"))
        val plugin = mapOf("cringle-plugin.json" to Fixtures.pluginManifest, "schemas/orders.json" to Fixtures.ordersSchema)
        assertTrue(problem(plugin, PackageKind.PLUGIN).contains("lib/acme-orders.jar"))
        assertTrue(problem(plugin + ("lib/acme-orders.jar" to "x") + ("lib/other.jar" to "x"), PackageKind.PLUGIN).contains("not listed"))
    }

    @Test
    fun readerRejectsNonZipFile() {
        val f = dir.resolve("no.zip").also { Files.writeString(it, "not a zip") }
        assertThrows<PackageFormatException> { PackageReader.readProject(f) }
    }

    @Test
    fun writerRejectsMismatchingBlueprintListAndUnsafeNames() {
        val out = java.io.ByteArrayOutputStream()
        assertThrows<IllegalArgumentException> {
            PackageWriter.writeProject(Fixtures.project.copy(blueprints = emptyList()), listOf(Fixtures.main), emptyMap(), emptyMap(), out)
        }
        assertThrows<IllegalArgumentException> {
            PackageWriter.writeProject(Fixtures.project, listOf(Fixtures.main), emptyMap(), mapOf("binaries/../x" to byteArrayOf()), out)
        }
        assertThrows<IllegalArgumentException> {
            PackageWriter.writeProject(Fixtures.project, listOf(Fixtures.main), emptyMap(), mapOf("other/x" to byteArrayOf()), out)
        }
    }

    @Test
    fun safeUnzipExtractsWithStructure() {
        val target = dir.resolve("out")
        SafeUnzip.extract(Fixtures.writeProject(dir), target)
        assertEquals("<html/>", Files.readString(target.resolve("binaries/site/index.html")))
        assertTrue(Files.exists(target.resolve("cringle-project.json")))
    }

    @Test
    fun safeUnzipRejectsZipSlipAndWritesNothingOutside() {
        val evil = zip("evil.zip", mapOf("../escaped.txt" to "x"))
        val target = dir.resolve("out")
        val e = assertThrows<PackageFormatException> { SafeUnzip.extract(evil, target) }
        assertEquals("../escaped.txt", e.path)
        assertFalse(Files.exists(dir.resolve("escaped.txt")))
    }

    @Test
    fun safeUnzipNeverOverwritesAndEnforcesSizeCap() {
        val pkg = Fixtures.writeProject(dir)
        val target = dir.resolve("out")
        SafeUnzip.extract(pkg, target)
        assertTrue(assertThrows<PackageFormatException> { SafeUnzip.extract(pkg, target) }.message!!.contains("already exists"))
        val e = assertThrows<PackageFormatException> { SafeUnzip.extract(pkg, dir.resolve("small"), maxBytes = 10) }
        assertTrue(e.message!!.contains("exceeds"))
    }

    @Test
    fun entryNameRules() {
        assertEquals(null, EntryNames.problem("binaries/a/b.txt"))
        assertEquals(null, EntryNames.problem("binaries/dir/"))
        for (bad in listOf("", "/", "a/./b", "a/../b", "a//b", "..", "a\\b", "a:b")) {
            assertTrue(EntryNames.problem(bad) != null, "'$bad' should be unsafe")
        }
    }
}
