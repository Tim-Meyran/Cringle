// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import java.io.File
import java.util.Properties

/**
 * What a build under test sees as its plugin classpath: the Cringle plugin with everything it needs at runtime, and
 * the Kotlin plugin of this repository. TestKit injects both, and a build under test resolves its plugins from that
 * classpath only.
 */
internal object TestKitClasspath {

    /** The Cringle plugin with everything it needs at runtime, which is what `cringle.plugin` and `cringle.project` need. */
    private val cringlePlugin: List<File> by lazy {
        val metadata = checkNotNull(javaClass.classLoader.getResource("plugin-under-test-metadata.properties")) {
            "the java-gradle-plugin plugin did not write plugin-under-test-metadata.properties"
        }
        Properties().apply { metadata.openStream().use { load(it) } }
            .getProperty("implementation-classpath")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map(::File)
    }

    private val kotlinPlugin: List<File> by lazy {
        required("cringle.kotlinPluginClasspath")
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map(::File)
    }

    /** The Cringle plugin alone. A project package holds no code, so a project needs nothing else. */
    val cringle: List<File> get() = cringlePlugin

    /** Both, without a duplicate. The Kotlin plugin is only needed by a sample that compiles Kotlin code. */
    val forNestedBuild: List<File> get() = (cringlePlugin + kotlinPlugin).distinctBy { it.absolutePath }

    private fun required(name: String): String =
        checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }
}
