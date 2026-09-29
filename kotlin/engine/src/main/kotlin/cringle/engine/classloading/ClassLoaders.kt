// SPDX-License-Identifier: Apache-2.0

package cringle.engine.classloading

import cringle.contract.BlockProvider
import cringle.packaging.PluginManifest
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Enumeration

/** Thrown when a block provider cannot be loaded or instantiated. */
public class ProviderLoadException(public val providerId: String, public val className: String?, message: String, cause: Throwable? = null) :
    RuntimeException("provider '$providerId'" + (className?.let { " ($it)" } ?: "") + ": $message", cause)

/**
 * The narrow parent of all provider class loaders (Architecture chapter 20). It shows the loaders below it only the
 * classes of [sharedPrefixes] taken from [delegate] (the engine's own loader) plus the JDK platform classes; every
 * other class of the engine is invisible to plugins.
 */
public class ContractClassLoader(
    private val delegate: ClassLoader = ContractClassLoader::class.java.classLoader,
    /** Class name prefixes that are shared with plugins. */
    public val sharedPrefixes: List<String> = DEFAULT_SHARED_PREFIXES,
) : ClassLoader(getPlatformClassLoader()) {
    /** Whether the class [name] is shared with plugins, that is, always taken from this loader's delegate. */
    public fun isShared(name: String): Boolean = sharedPrefixes.any { name.startsWith(it) }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!isShared(name)) return super.loadClass(name, resolve)
        val cls = delegate.loadClass(name)
        if (resolve) resolveClass(cls)
        return cls
    }

    override fun getResource(name: String): URL? {
        val className = name.removeSuffix(".class").replace('/', '.')
        return if (name.endsWith(".class") && isShared(className)) delegate.getResource(name) else super.getResource(name)
    }

    public companion object {
        /**
         * The default shared packages: the contract, and the Kotlin standard library and coroutines whose types appear
         * in the contract's signatures `[Zu bestätigen]`.
         */
        public val DEFAULT_SHARED_PREFIXES: List<String> = listOf("cringle.contract.", "kotlin.", "kotlinx.coroutines.")
    }
}

/**
 * Loads the classes of one provider: its JARs first, then (only for shared classes and JDK classes) the parent. A class
 * with the same name in the plugin's JARs never replaces a shared class, so contract types are identical across all
 * loaders and no `ClassCastException` can arise at the boundary.
 */
public class ProviderClassLoader(jars: List<Path>, private val contract: ContractClassLoader) :
    URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), contract) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val cls = if (contract.isShared(name) || name.startsWith("java.")) {
                contract.loadClass(name)
            } else {
                try {
                    findClass(name)
                } catch (e: ClassNotFoundException) {
                    contract.loadClass(name)
                }
            }
            if (resolve) resolveClass(cls)
            return cls
        }
    }

    override fun getResource(name: String): URL? = findResource(name) ?: contract.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val own = findResources(name).toList()
        val inherited = contract.getResources(name).toList()
        return Collections.enumeration(own + inherited)
    }
}

/**
 * The class loaders of one fabric instance: one [ProviderClassLoader] per provider id, all below the shared
 * [contract] loader. Create one per fabric instance and [close] it when the fabric stops; after that the loaders and
 * every class loaded through them can be garbage collected, provided the engine keeps no other reference.
 *
 * Cost (chapter 20): every fabric instance loads its own copy of every provider class and library, so memory use and
 * start-up time grow with the number of parallel instances. This is the accepted price for isolation.
 */
public class FabricClassLoaders(private val contract: ContractClassLoader) : AutoCloseable {
    private val loaders = LinkedHashMap<String, ProviderClassLoader>()
    private var closed = false

    /** Returns the loader for [providerId], creating it with [jars] on first use; later calls ignore [jars]. */
    @Synchronized
    public fun loaderFor(providerId: String, jars: List<Path>): ProviderClassLoader {
        check(!closed) { "class loaders of this fabric are closed" }
        return loaders.getOrPut(providerId) { ProviderClassLoader(jars, contract) }
    }

    /**
     * Instantiates every provider class listed in [manifest] from the JARs of the unpacked plugin at [pluginRoot],
     * through the loader of [providerId].
     */
    public fun openProviders(providerId: String, pluginRoot: Path, manifest: PluginManifest): List<BlockProvider> {
        val root = pluginRoot.toAbsolutePath().normalize()
        val jars = manifest.libs.map { entry ->
            val jar = root.resolve(entry).normalize()
            if (!jar.startsWith(root)) throw ProviderLoadException(providerId, null, "jar '$entry' is outside the plugin directory")
            if (!Files.isRegularFile(jar)) throw ProviderLoadException(providerId, null, "jar '$entry' does not exist")
            jar
        }
        val loader = loaderFor(providerId, jars)
        return manifest.providers.map { instantiate(providerId, loader, it) }
    }

    private fun instantiate(providerId: String, loader: ProviderClassLoader, className: String): BlockProvider {
        val cls = try {
            Class.forName(className, true, loader)
        } catch (e: ClassNotFoundException) {
            throw ProviderLoadException(providerId, className, "class not found in the plugin's JARs", e)
        } catch (e: LinkageError) {
            throw ProviderLoadException(providerId, className, "class cannot be linked: ${e.message}", e)
        }
        if (!BlockProvider::class.java.isAssignableFrom(cls)) {
            throw ProviderLoadException(providerId, className, "does not implement cringle.contract.BlockProvider")
        }
        return try {
            cls.getConstructor().newInstance() as BlockProvider
        } catch (e: NoSuchMethodException) {
            throw ProviderLoadException(providerId, className, "needs a public no-argument constructor", e)
        } catch (e: ReflectiveOperationException) {
            throw ProviderLoadException(providerId, className, "cannot be instantiated: ${e.cause ?: e}", e)
        }
    }

    /** Closes all loaders. Safe to call more than once. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: IOException? = null
        for (loader in loaders.values) {
            try {
                loader.close()
            } catch (e: IOException) {
                failure = failure ?: e
            }
        }
        loaders.clear()
        failure?.let { throw it }
    }
}
