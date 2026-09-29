// SPDX-License-Identifier: Apache-2.0

package cringle.engine.classloading

import cringle.contract.BlockProvider
import cringle.packaging.PluginManifest
import cringle.testkit.TestJar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path

class ClassLoadingTest {
    @TempDir
    lateinit var dir: Path

    private val contract = ContractClassLoader()

    /** A provider plus a "library" class whose version differs between plugins, and a class with static state. */
    private fun pluginSources(libVersion: String, providerName: String = "AProvider") = mapOf(
        "org.lib.Version" to "package org.lib; public class Version { public static String get() { return \"$libVersion\"; } }",
        "com.acme.Counter" to "package com.acme; public class Counter { public static int count; public static int next() { return ++count; } }",
        "com.acme.$providerName" to """
            package com.acme;
            import cringle.contract.*;
            import java.util.List;
            public class $providerName implements BlockProvider {
                public List<BlockDefinition> getDefinitions() { return List.of(); }
                public Block createBlock(String name, DriverSet drivers) { throw new UnsupportedOperationException(); }
                public String libVersion() { return org.lib.Version.get(); }
                public int next() { return Counter.next(); }
            }
        """.trimIndent(),
    )

    private fun pluginRoot(name: String, libVersion: String, providerName: String = "AProvider", extra: Map<String, ByteArray> = emptyMap()): Pair<Path, PluginManifest> {
        val root = Files.createDirectories(dir.resolve(name))
        Files.createDirectories(root.resolve("lib"))
        Files.write(root.resolve("lib/$name.jar"), TestJar.fromEntries(compiled(libVersion, providerName) + extra))
        return root to PluginManifest(name, "1.0.0", providers = listOf("com.acme.$providerName"), libs = listOf("lib/$name.jar"))
    }

    private val compileCache = HashMap<Pair<String, String>, Map<String, ByteArray>>()

    private fun compiled(libVersion: String, providerName: String): Map<String, ByteArray> = compileCache.getOrPut(libVersion to providerName) {
        val jar = TestJar.fromJavaSources(pluginSources(libVersion, providerName))
        val out = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(jar.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { out[it.name] = zip.readBytes() }
        }
        out
    }

    private fun Any.call(method: String): Any? = javaClass.getMethod(method).invoke(this)

    @Test
    fun twoProvidersInOneFabricUseDifferentVersionsOfTheSameLibrary() {
        FabricClassLoaders(contract).use { fabric ->
            val (rootA, manifestA) = pluginRoot("plugin-a", "1.0", "AProvider")
            val (rootB, manifestB) = pluginRoot("plugin-b", "2.0", "BProvider")
            val a = fabric.openProviders("plugin-a@1.0.0", rootA, manifestA).single()
            val b = fabric.openProviders("plugin-b@1.0.0", rootB, manifestB).single()
            assertEquals("1.0", a.call("libVersion"))
            assertEquals("2.0", b.call("libVersion"))
            val libA = a.javaClass.classLoader.loadClass("org.lib.Version")
            val libB = b.javaClass.classLoader.loadClass("org.lib.Version")
            assertNotSame(libA, libB)
        }
    }

    @Test
    fun twoFabricInstancesOfOneBlueprintDoNotShareStaticState() {
        val (root, manifest) = pluginRoot("plugin-a", "1.0")
        FabricClassLoaders(contract).use { one ->
            FabricClassLoaders(contract).use { two ->
                val p1 = one.openProviders("plugin-a@1.0.0", root, manifest).single()
                val p2 = two.openProviders("plugin-a@1.0.0", root, manifest).single()
                assertNotSame(p1.javaClass, p2.javaClass)
                assertEquals(1, p1.call("next"))
                assertEquals(2, p1.call("next"))
                assertEquals(1, p2.call("next"))
                assertEquals(3, p1.call("next"))
            }
        }
    }

    @Test
    fun sameProviderIdInOneFabricSharesItsLoaderAndClasses() {
        val (root, manifest) = pluginRoot("plugin-a", "1.0")
        FabricClassLoaders(contract).use { fabric ->
            val first = fabric.openProviders("plugin-a@1.0.0", root, manifest).single()
            val second = fabric.openProviders("plugin-a@1.0.0", root, manifest).single()
            assertSame(first.javaClass, second.javaClass)
            assertEquals(1, first.call("next"))
            assertEquals(2, second.call("next"))
        }
    }

    @Test
    fun contractClassesAreIdenticalAcrossLoaders() {
        val (root, manifest) = pluginRoot("plugin-a", "1.0")
        FabricClassLoaders(contract).use { fabric ->
            val provider = fabric.openProviders("p", root, manifest).single()
            val loader = provider.javaClass.classLoader
            assertSame(BlockProvider::class.java, loader.loadClass("cringle.contract.BlockProvider"))
            assertSame(cringle.contract.Block::class.java, loader.loadClass("cringle.contract.Block"))
            assertSame(Unit::class.java, loader.loadClass("kotlin.Unit"))
            assertSame(kotlinx.coroutines.flow.Flow::class.java, loader.loadClass("kotlinx.coroutines.flow.Flow"))
            assertSame(String::class.java, loader.loadClass("java.lang.String"))
            // the provider, its block and the definitions list are used through the engine's own types
            assertTrue(provider.definitions.isEmpty())
        }
    }

    @Test
    fun pluginsCannotShadowContractClasses() {
        val fake = TestJar.fromJavaSources(mapOf("cringle.contract.Block" to "package cringle.contract; public class Block {}"))
        val root = Files.createDirectories(dir.resolve("evil/lib"))
        Files.write(root.resolve("evil.jar"), fake)
        FabricClassLoaders(contract).use { fabric ->
            val loader = fabric.loaderFor("evil", listOf(root.resolve("evil.jar")))
            assertSame(cringle.contract.Block::class.java, loader.loadClass("cringle.contract.Block"))
        }
    }

    @Test
    fun theContractLoaderHidesEverythingElseOfTheEngine() {
        assertThrows<ClassNotFoundException> { contract.loadClass("cringle.engine.classloading.FabricClassLoaders") }
        assertThrows<ClassNotFoundException> { contract.loadClass("org.junit.jupiter.api.Test") }
        assertThrows<ClassNotFoundException> { contract.loadClass("cringle.packaging.Version") }
        assertSame(BlockProvider::class.java, contract.loadClass("cringle.contract.BlockProvider"))
        assertSame(String::class.java, contract.loadClass("java.lang.String"))
        assertNull(contract.getResource("org/junit/jupiter/api/Test.class"))
        assertNotNull(contract.getResource("cringle/contract/Block.class"))
        FabricClassLoaders(contract).use { fabric ->
            val loader = fabric.loaderFor("p", emptyList())
            assertThrows<ClassNotFoundException> { loader.loadClass("cringle.engine.classloading.FabricClassLoaders") }
        }
    }

    @Test
    fun resourcesAreChildFirst() {
        val (root, manifest) = pluginRoot("plugin-a", "1.0", extra = mapOf("config.txt" to "own".toByteArray()))
        FabricClassLoaders(contract).use { fabric ->
            fabric.openProviders("p", root, manifest)
            val loader = fabric.loaderFor("p", emptyList())
            assertEquals("own", loader.getResource("config.txt")!!.readText())
            assertEquals(1, loader.getResources("config.txt").toList().size)
            assertNull(loader.getResource("does-not-exist.txt"))
        }
    }

    @Test
    fun loadersBecomeCollectableAfterTheFabricIsClosed() {
        val (root, manifest) = pluginRoot("plugin-a", "1.0")
        val refs = ArrayList<WeakReference<Any>>()
        run {
            val fabric = FabricClassLoaders(contract)
            val provider = fabric.openProviders("p", root, manifest).single()
            provider.call("next")
            refs += WeakReference(provider.javaClass.classLoader)
            refs += WeakReference(provider.javaClass)
            refs += WeakReference(provider)
            fabric.close()
        }
        var attempts = 0
        while (refs.any { it.get() != null } && attempts < 100) {
            System.gc()
            ByteArray(8 * 1024 * 1024)
            attempts++
        }
        assertTrue(refs.all { it.get() == null }, "loaders or classes still reachable after close and $attempts GC attempts")
        // the shared contract loader keeps working for the next fabric
        FabricClassLoaders(contract).use { assertEquals("1.0", it.openProviders("p", root, manifest).single().call("libVersion")) }
    }

    @Test
    fun closedFabricRejectsNewLoadersAndCloseIsRepeatable() {
        val fabric = FabricClassLoaders(contract)
        fabric.close()
        fabric.close()
        assertThrows<IllegalStateException> { fabric.loaderFor("p", emptyList()) }
    }

    @Test
    fun providerLoadFailuresAreReadable() {
        val (root, manifest) = pluginRoot("plugin-a", "1.0")
        fun failure(m: PluginManifest, r: Path = root): ProviderLoadException = FabricClassLoaders(contract).use { fabric ->
            assertThrows { fabric.openProviders("plugin-a@1.0.0", r, m) }
        }
        assertTrue(failure(manifest.copy(providers = listOf("com.acme.Missing"))).message!!.contains("class not found"))
        assertTrue(failure(manifest.copy(providers = listOf("org.lib.Version"))).message!!.contains("does not implement cringle.contract.BlockProvider"))
        assertTrue(failure(manifest.copy(libs = listOf("lib/nope.jar"))).message!!.contains("does not exist"))
        assertTrue(failure(manifest.copy(libs = listOf("../outside.jar"))).message!!.contains("outside the plugin directory"))

        val noCtor = TestJar.fromJavaSources(
            mapOf(
                "com.acme.NoCtor" to """
                    package com.acme;
                    import cringle.contract.*;
                    import java.util.List;
                    public class NoCtor implements BlockProvider {
                        public NoCtor(String s) {}
                        public List<BlockDefinition> getDefinitions() { return List.of(); }
                        public Block createBlock(String n, DriverSet d) { throw new UnsupportedOperationException(); }
                    }
                """.trimIndent(),
            ),
        )
        Files.write(root.resolve("lib/plugin-a.jar"), noCtor)
        assertTrue(failure(manifest.copy(providers = listOf("com.acme.NoCtor"))).message!!.contains("public no-argument constructor"))
    }
}
