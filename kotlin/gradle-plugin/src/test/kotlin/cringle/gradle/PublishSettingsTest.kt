// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Where `cringlePublish` takes the address of the repository and the token from: the task, then the environment, then
 * the profile of `cringle login`. The functional tests prove that the chain works through a build; these tests pin the
 * order down and the wording of the messages, and they read profiles out of a temporary home of the test.
 */
class PublishSettingsTest {

    @TempDir
    lateinit var temp: Path

    private val profile = CliProfile("profile-server:1234", "profile-token")

    @Test
    fun theTaskPropertyWinsOverTheEnvironmentAndTheProfile() {
        val target = PublishSettings.resolve(
            "task-server:4321",
            mapOf("CRINGLE_SERVER" to "env-server:1111", "CRINGLE_TOKEN" to "env-token"),
            profile,
        )
        assertEquals("task-server:4321", target.server)
        assertEquals("env-token", target.token, "the token has no property, so the environment provides it")
    }

    @Test
    fun theEnvironmentWinsOverTheProfile() {
        val target = PublishSettings.resolve(
            null,
            mapOf("CRINGLE_SERVER" to "env-server:1111", "CRINGLE_TOKEN" to "env-token"),
            profile,
        )
        assertEquals("env-server:1111", target.server)
        assertEquals("env-token", target.token)
    }

    @Test
    fun theProfileIsTheLastPlaceBothComeFrom() {
        val target = PublishSettings.resolve(null, emptyMap(), profile)
        assertEquals("profile-server:1234", target.server)
        assertEquals("profile-token", target.token)
    }

    @Test
    fun aRepositoryWithoutATokenIsNormal() {
        val target = PublishSettings.resolve("server:1234", emptyMap(), CliProfile("server:1234", null))
        assertEquals("server:1234", target.server)
        assertNull(target.token, "a repository that wants no token is served with none")
    }

    @Test
    fun withoutAnAddressTheMessageNamesAllThreePlaces() {
        val e = assertThrows<GradleException> { PublishSettings.resolve(null, emptyMap(), null) }
        assertTrue("-Pcringle.server=host:port" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("CRINGLE_SERVER" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("cringle login" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun aBlankValueCountsAsNoValue() {
        val target = PublishSettings.resolve(
            "  ",
            mapOf("CRINGLE_SERVER" to "env-server:1111", "CRINGLE_TOKEN" to "   "),
            profile,
        )
        assertEquals("env-server:1111", target.server)
        assertEquals("profile-token", target.token, "a blank CRINGLE_TOKEN must not win over the profile")
    }

    @Test
    fun theProfileIsReadFromTheCringleHome() {
        val home = temp.resolve("home")
        home.createDirectories()
        home.resolve(CliProfile.FILE_NAME).writeText("""{"server": "server:1234", "token": "secret"}""")
        assertEquals(CliProfile("server:1234", "secret"), CliProfile.load(home))
        assertEquals(home, PublishSettings.home(mapOf("CRINGLE_HOME" to home.toString())))
    }

    @Test
    fun aHomeWithoutAProfileHoldsNone() {
        val home = temp.resolve("empty")
        home.createDirectories()
        assertNull(CliProfile.load(home))
        assertNull(CliProfile.load(temp.resolve("nowhere")), "a home that does not exist holds no profile either")
    }

    @Test
    fun aProfileWithoutATokenAndWithANullValue() {
        val home = temp.resolve("nulls")
        home.createDirectories()
        home.resolve(CliProfile.FILE_NAME).writeText("""{"server": "server:1234", "token": null}""")
        assertEquals(CliProfile("server:1234", null), CliProfile.load(home))
    }

    @Test
    fun aBrokenProfileIsReportedWithItsPath() {
        val home = temp.resolve("broken")
        home.createDirectories()
        val file = home.resolve(CliProfile.FILE_NAME)
        file.writeText("{ this is no json")
        val e = assertThrows<GradleException> { CliProfile.load(home) }
        assertTrue(file.toString() in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun theHomeFallsBackToTheHomeOfTheUser() {
        val expected = Path.of(System.getProperty("user.home"), ".cringle")
        assertEquals(expected, PublishSettings.home(emptyMap()))
        assertEquals(expected, PublishSettings.home(mapOf("CRINGLE_HOME" to "  ")))
    }

    @Test
    fun theTokenIsInNoMessageOfTheResolution() {
        val resolved = runCatching { PublishSettings.resolve("server:1234", mapOf("CRINGLE_TOKEN" to "secret"), null) }
        assertNull(resolved.exceptionOrNull(), "a target is an address and a token, there is no message to leak one")
        val noAddress = assertThrows<GradleException> { PublishSettings.resolve(null, emptyMap(), CliProfile(null, "secret")) }
        assertFalse("secret" in noAddress.message.orEmpty(), noAddress.message.orEmpty())
    }
}
