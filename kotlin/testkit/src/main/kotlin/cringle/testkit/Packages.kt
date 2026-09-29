// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.BlockDefinition
import cringle.packaging.Blueprint
import cringle.packaging.FabricConfig
import cringle.packaging.PackageHash
import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.packaging.PluginPackage
import cringle.packaging.ProcessorSet
import cringle.packaging.ProjectManifest
import cringle.packaging.ProjectPackage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/** A package written by a test builder: the file, its hash and the model read back from it. */
public data class BuiltPlugin(val file: Path, val hash: String, val pkg: PluginPackage)

/** A project written by a test builder: the file, its hash and the model read back from it. */
public data class BuiltProject(val file: Path, val hash: String, val pkg: ProjectPackage)

/** Thrown when a package built by a test builder does not pass validation. */
public class InvalidTestPackageException(public val problems: List<PackageProblem>) :
    IllegalStateException("test package is invalid:\n" + problems.joinToString("\n") { "  ${it.path}: ${it.message}" })

/** Creates small JAR files for tests. */
public object TestJar {
    /** Creates a JAR from [entries] (entry name to content); output is deterministic. */
    public fun fromEntries(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in entries.keys.sorted()) {
                zip.putNextEntry(ZipEntry(name).also { it.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0)) })
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Compiles Java [sources] (fully qualified class name to source text) with the JDK compiler and returns a JAR
     * with the resulting classes. Throws [IllegalStateException] with the compiler output on errors.
     */
    public fun fromJavaSources(sources: Map<String, String>): ByteArray {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("no Java compiler available: run the tests on a JDK")
        val diagnostics = javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
        val files = sources.map { (name, text) ->
            object : SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
            }
        }
        val out = Files.createTempDirectory("cringle-testjar")
        try {
            val ok = compiler.getTask(null, null, diagnostics, listOf("-d", out.toString(), "--release", "21"), null, files).call()
            check(ok) { "compilation failed:\n" + diagnostics.diagnostics.joinToString("\n") { it.toString() } }
            val entries = HashMap<String, ByteArray>()
            Files.walk(out).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach {
                    entries[out.relativize(it).joinToString("/")] = Files.readAllBytes(it)
                }
            }
            return fromEntries(entries)
        } finally {
            Files.walk(out).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Builds plugin packages for tests. The result is validated with `packaging` unless disabled. */
public class TestPluginBuilder(private val name: String, private val version: String = "1.0.0") {
    private val dependencies = LinkedHashMap<String, String>()
    private val providers = ArrayList<String>()
    private val drivers = ArrayList<String>()
    private val blocks = ArrayList<BlockDefinition>()
    private val schemas = LinkedHashMap<String, String>()
    private val libs = LinkedHashMap<String, ByteArray>()
    private val binaries = LinkedHashMap<String, ByteArray>()
    private var processors = ProcessorSet()

    /** Adds a dependency on plugin or project [name] with version [range]. */
    public fun dependency(name: String, range: String): TestPluginBuilder = also { dependencies[name] = range }

    /** Registers a `BlockProvider` class name. */
    public fun provider(className: String): TestPluginBuilder = also { providers += className }

    /** Registers a `Driver` class name. */
    public fun driver(className: String): TestPluginBuilder = also { drivers += className }

    /** Adds a block definition. */
    public fun block(definition: BlockDefinition): TestPluginBuilder = also { blocks += definition }

    /** Adds the schema document [text] as `schemas/<fileName>`. */
    public fun schema(fileName: String, text: String): TestPluginBuilder = also { schemas["schemas/$fileName"] = text }

    /** Adds the JAR [content] as `lib/<fileName>`. */
    public fun lib(fileName: String, content: ByteArray): TestPluginBuilder = also { libs["lib/$fileName"] = content }

    /** Adds a static file as `binaries/<path>`. */
    public fun binary(path: String, content: ByteArray): TestPluginBuilder = also { binaries["binaries/$path"] = content }

    /** Sets update and downgrade processor class names. */
    public fun processors(update: String? = null, downgrade: String? = null): TestPluginBuilder =
        also { processors = ProcessorSet(update, downgrade) }

    /** The manifest as built so far. */
    public fun manifest(): PluginManifest = PluginManifest(
        name, version, dependencies.toMap(), providers.toList(), drivers.toList(), blocks.toList(),
        libs.keys.toList(), schemas.keys.toList(), processors,
    )

    /**
     * Writes `<name>-<version>.zip` into [dir], reads it back and returns it. With [validate], problems found by
     * `PackageValidator.validatePlugin` (using [dependencies] for schema lookup) throw [InvalidTestPackageException].
     */
    public fun build(dir: Path, validate: Boolean = true, dependencies: List<PluginPackage> = emptyList()): BuiltPlugin {
        val manifest = manifest()
        val file = dir.resolve("$name-$version.zip")
        Files.newOutputStream(file).use { PackageWriter.writePlugin(manifest, schemas, libs, binaries, it) }
        val pkg = PackageReader.readPlugin(file)
        if (validate) {
            val problems = PackageValidator.validatePlugin(pkg, dependencies)
            if (problems.isNotEmpty()) throw InvalidTestPackageException(problems)
        }
        return BuiltPlugin(file, PackageHash.sha256(file), pkg)
    }
}

/** Builds project packages for tests. The result is validated with `packaging` unless disabled. */
public class TestProjectBuilder(private val name: String, private val version: String = "1.0.0") {
    private val dependencies = LinkedHashMap<String, String>()
    private val blueprints = ArrayList<Blueprint>()
    private val fabrics = ArrayList<FabricConfig>()
    private val schemas = LinkedHashMap<String, String>()
    private val binaries = LinkedHashMap<String, ByteArray>()

    /** Adds a dependency on plugin or project [name] with version [range]. */
    public fun dependency(name: String, range: String): TestProjectBuilder = also { dependencies[name] = range }

    /** Adds a blueprint. */
    public fun blueprint(blueprint: Blueprint): TestProjectBuilder = also { blueprints += blueprint }

    /** Adds a fabric config. */
    public fun fabric(config: FabricConfig): TestProjectBuilder = also { fabrics += config }

    /** Adds the schema document [text] as `schemas/<fileName>`. */
    public fun schema(fileName: String, text: String): TestProjectBuilder = also { schemas["schemas/$fileName"] = text }

    /** Adds a static file as `binaries/<path>`. */
    public fun binary(path: String, content: ByteArray): TestProjectBuilder = also { binaries["binaries/$path"] = content }

    /** The manifest as built so far. */
    public fun manifest(): ProjectManifest = ProjectManifest(
        name, version, dependencies.toMap(), blueprints.map { "blueprints/${it.name}.json" }, schemas.keys.toList(), fabrics.toList(),
    )

    /**
     * Writes `<name>-<version>.zip` into [dir], reads it back and returns it. With [validate], problems found
     * against the given [plugins] throw [InvalidTestPackageException].
     */
    public fun build(dir: Path, plugins: List<PluginPackage> = emptyList(), validate: Boolean = true): BuiltProject {
        val manifest = manifest()
        val file = dir.resolve("$name-$version.zip")
        Files.newOutputStream(file).use { PackageWriter.writeProject(manifest, blueprints, schemas, binaries, it) }
        val pkg = PackageReader.readProject(file)
        if (validate) {
            val problems = PackageValidator.validateProject(pkg, plugins)
            if (problems.isNotEmpty()) throw InvalidTestPackageException(problems)
        }
        return BuiltProject(file, PackageHash.sha256(file), pkg)
    }
}
