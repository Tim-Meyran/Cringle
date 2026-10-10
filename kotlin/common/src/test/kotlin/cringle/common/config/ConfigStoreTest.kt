// SPDX-License-Identifier: Apache-2.0

package cringle.common.config

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ConfigStoreTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = ConfigStore(dir.resolve("config").resolve("cringle.conf"))

    @Test
    fun everyKeyOfTheCatalogHasAValidDefaultAndARestartTarget() {
        for (key in ConfigCatalog.keys) {
            assertEquals(null, key.problem(key.default).takeIf { key.default.isNotEmpty() }, key.name)
            assertTrue(key.restarts.isNotEmpty() && key.description.isNotBlank(), key.name)
        }
        assertEquals(ConfigCatalog.keys.size, ConfigCatalog.keys.map { it.name }.toSet().size)
    }

    @Test
    fun theDefaultsAreInEffectUntilAKeyIsSetAndComeBackWhenItIsUnset() {
        val store = store()
        assertEquals("7500", store.get("management.port"))
        assertFalse(store.isSet("management.port"))
        assertEquals("7501", store.set("management.port", " 7501 "))
        assertEquals("7501", store.get("management.port"))
        assertTrue(store.isSet("management.port"))
        assertTrue(store.all().first { it.key.name == "management.port" }.isSet)
        store.unset("management.port")
        assertEquals("7500", store.get("management.port"))
        assertFalse(store.isSet("management.port"))
        // unsetting what is not there changes nothing, also without a file
        store.unset("repository.port")
    }

    @Test
    fun everyKeyIsCheckedAndNothingIsWrittenForARefusedValue() {
        val store = store()
        for ((key, value) in listOf(
            "daemon.port" to "0", "daemon.port" to "70000", "management.port" to "abc", "bind" to "192.0.2.7", "components" to "management,router",
            "management.web.url" to "http://x:1", "management.web.url" to "https://x/path", "management.web.url" to "https://u@x:1",
        )) {
            assertThrows<ConfigException>("$key=$value") { store.set(key, value) }
        }
        assertThrows<ConfigException> { store.set("no.such.key", "1") }
        assertThrows<ConfigException> { store.get("no.such.key") }
        assertThrows<ConfigException> { store.unset("no.such.key") }
        assertFalse(Files.exists(store.file))
        assertThrows<ConfigException> { store.setAll(mapOf("daemon.port" to "7401", "bind" to "everything")) }
        assertFalse(Files.exists(store.file), "setAll writes all or nothing")
    }

    @Test
    fun valuesAreWrittenInTheirNormalForm() {
        val store = store()
        assertEquals("all", store.set("bind", "ALL"))
        assertEquals("management,repository", store.set("components", "repository, management"))
        assertEquals("none", store.set("components", ""))
        assertEquals("https://cringle.example:8443", store.set("management.web.url", "https://cringle.example:8443"))
        assertEquals("", store.set("management.web.url", ""))
    }

    @Test
    fun theRestOfTheFileStaysWhenAKeyChanges() {
        val store = store()
        Files.createDirectories(store.file.parent)
        Files.writeString(store.file, "# my notes\n\nbind=loopback\nrepository.port=7600\n")
        store.set("bind", "all")
        store.set("management.port", "7501")
        store.unset("repository.port")
        assertEquals("# my notes\n\nbind=all\nmanagement.port=7501\n", Files.readString(store.file))
        assertFalse(Files.exists(store.file.resolveSibling("cringle.conf.tmp")))
    }

    @Test
    fun linesThatCannotBeUsedAreReportedAndKept() {
        val store = store()
        Files.createDirectories(store.file.parent)
        Files.writeString(store.file, "bind=loopback\nmystery=1\nnot a setting\ndaemon.port=abc\nmanagement.port=7501\n")
        assertEquals(3, store.problems.size, store.problems.toString())
        assertTrue(store.problems.any { it.contains("unknown key 'mystery'") } && store.problems.any { it.contains("daemon.port") })
        // the usable lines count, the others have no effect
        assertEquals("7501", store.get("management.port"))
        assertEquals("7400", store.get("daemon.port"))
        store.set("bind", "all")
        assertTrue(Files.readString(store.file).contains("mystery=1") && Files.readString(store.file).contains("not a setting"))
    }

    @Test
    fun theFirstStartMakesTheFileFromTheEnvironmentAndTheArgumentsOnce() {
        val store = store()
        val env = mapOf("CRINGLE_BIND" to "all", "CRINGLE_WEB_PORT" to "9443", "CRINGLE_COMPONENTS" to "repository,management", "CRINGLE_DAEMON_PORT" to "abc", "HOME" to "x")
        assertTrue(store.migrate(env, mapOf("management.web.port" to "9444", "no.such.key" to "1")))
        assertEquals("all", store.get("bind"))
        assertEquals("9444", store.get("management.web.port"), "an argument wins over the environment")
        assertEquals("management,repository", store.get("components"))
        assertEquals("7400", store.get("daemon.port"), "a value that the catalog refuses is left out")
        assertNotNull(store.file)
        // once: an existing file is not changed, and the environment is not read again
        store.set("bind", "loopback")
        assertFalse(store.migrate(mapOf("CRINGLE_BIND" to "all"), emptyMap()))
        assertEquals("loopback", store.get("bind"))
    }
}
