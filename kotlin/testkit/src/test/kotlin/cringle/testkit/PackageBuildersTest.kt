// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.FabricConfig
import cringle.packaging.PackageHash
import cringle.packaging.PackageKind
import cringle.packaging.PackageReader
import cringle.packaging.TetherDef
import cringle.contract.TetherType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class PackageBuildersTest {
    @TempDir
    lateinit var dir: Path

    private val helloSource = "package com.acme; public class Hello { public String greet() { return \"hi\"; } }"

    private fun plugin(): TestPluginBuilder = TestPluginBuilder("acme-samples", "2.0.0")
        .provider("com.acme.Provider")
        .block(SampleProvider.shout)
        .block(SampleProvider.counter)
        .lib("acme.jar", TestJar.fromJavaSources(mapOf("com.acme.Hello" to helloSource)))
        .binary("site/index.html", "<html/>".toByteArray())

    @Test
    fun compiledTestClassesLoadFromTheJar() {
        val jar = dir.resolve("t.jar").also { Files.write(it, TestJar.fromJavaSources(mapOf("com.acme.Hello" to helloSource))) }
        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
            val hello = loader.loadClass("com.acme.Hello").getDeclaredConstructor().newInstance()
            assertEquals("hi", hello.javaClass.getMethod("greet").invoke(hello))
        }
    }

    @Test
    fun compileErrorsAreReported() {
        val e = assertThrows<IllegalStateException> { TestJar.fromJavaSources(mapOf("a.B" to "package a; class B { int x = \"s\"; }")) }
        assertTrue(e.message!!.contains("compilation failed"), e.message)
    }

    @Test
    fun jarsFromEntriesAreDeterministic() {
        val entries = mapOf("b.txt" to byteArrayOf(2), "a.txt" to byteArrayOf(1))
        assertEquals(PackageHash.sha256(TestJar.fromEntries(entries)), PackageHash.sha256(TestJar.fromEntries(entries.toSortedMap())))
    }

    @Test
    fun pluginBuilderProducesValidPackage() {
        val schemaText = """{"namespace":"acme.samples","types":{"Note":{"record":{"text":"cringle.std/String"}}}}"""
        val built = plugin().schema("notes.json", schemaText).build(dir)
        assertEquals(PackageKind.PLUGIN, PackageReader.kind(built.file))
        assertEquals("acme-samples", built.pkg.manifest.name)
        assertEquals(listOf("lib/acme.jar"), built.pkg.manifest.libs)
        assertEquals(schemaText, built.pkg.schemas["schemas/notes.json"])
        assertTrue("binaries/site/index.html" in built.pkg.files)
        assertEquals(PackageHash.sha256(built.file), built.hash)
    }

    @Test
    fun invalidPluginFailsWithProblemList() {
        val builder = TestPluginBuilder("bad").provider("x.P").block(
            cringle.contract.BlockDefinition("b", listOf(cringle.contract.SchemaRef("missing", "T")), emptyList(), emptyList()),
        )
        val e = assertThrows<InvalidTestPackageException> { builder.build(dir) }
        assertTrue(e.problems.any { it.message.contains("does not resolve") }, e.message)
        assertEquals("bad-1.0.0.zip", builder.build(dir, validate = false).file.fileName.toString())
    }

    @Test
    fun projectBuilderValidatesAgainstPlugins() {
        val plugin = plugin().build(dir)
        val blueprint = Blueprint(
            "main",
            listOf(BlueprintBlock("s", "acme-samples/shout"), BlueprintBlock("t", "acme-samples/shout")),
            listOf(TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), Endpoint("t", "in"))),
        )
        val project = TestProjectBuilder("demo", "0.1.0")
            .dependency("acme-samples", "^2.0.0")
            .blueprint(blueprint)
            .fabric(FabricConfig("main", 1, listOf("edge"), emptyMap()))
            .build(dir, listOf(plugin.pkg))
        assertEquals(PackageKind.PROJECT, PackageReader.kind(project.file))
        assertEquals(listOf(blueprint), project.pkg.blueprints)

        val broken = blueprint.copy(blocks = listOf(BlueprintBlock("s", "acme-samples/nope")), tethers = emptyList())
        val e = assertThrows<InvalidTestPackageException> {
            TestProjectBuilder("demo2").blueprint(broken).build(dir, listOf(plugin.pkg))
        }
        assertTrue(e.problems.single().message.contains("unknown block"))
    }
}
