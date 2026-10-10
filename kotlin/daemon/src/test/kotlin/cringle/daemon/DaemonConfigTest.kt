// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.config.ConfigStore
import cringle.daemon.v1.DaemonServiceGrpcKt.DaemonServiceCoroutineStub
import cringle.daemon.v1.GetConfigRequest
import cringle.daemon.v1.ListConfigRequest
import cringle.daemon.v1.SetConfigRequest
import cringle.daemon.v1.UnsetConfigRequest
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The settings of the machine (#314, #315): the store the daemon reads, and the calls that change it. */
@Tag("integration")
class DaemonConfigTest {
    @TempDir
    lateinit var home: Path

    private val closeables = ArrayList<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun store() = ConfigStore(home.resolve("config").resolve("cringle.conf"))

    private fun stub(daemon: Daemon): DaemonServiceCoroutineStub {
        val client = DaemonTestClient(home.resolve("client"))
        val channel = client.channel(daemon)
        closeables += AutoCloseable { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
        return DaemonServiceCoroutineStub(channel)
    }

    private fun daemon(store: ConfigStore = store(), overrides: Map<String, String> = emptyMap()): Daemon =
        Daemon(home, 0, config = store, overrides = overrides).start().also { closeables += it }

    private fun code(body: suspend () -> Unit): Status.Code = assertThrows<StatusException> { runBlocking { body() } }.status.code

    @Test
    fun theValuesInEffectAreTheArgumentsThenTheStoreThenTheDefaults() {
        val store = store()
        store.set("management.port", "7511")
        store.set("repository.port", "7611")
        val daemon = Daemon(home, 0, config = store, overrides = mapOf("repository.port" to "7612")).also { closeables += it }
        assertEquals("7511", daemon.effectiveValue("management.port"))
        assertEquals("7612", daemon.effectiveValue("repository.port"))
        assertEquals("8443", daemon.effectiveValue("management.web.port"))
        assertTrue(daemon.isOverridden("repository.port"))
        assertFalse(daemon.isOverridden("management.port"))
    }

    @Test
    fun theSettingsAreListedReadChangedAndRemoved() = runBlocking {
        val daemon = daemon()
        val stub = stub(daemon)
        val list = stub.listConfig(ListConfigRequest.getDefaultInstance())
        assertEquals(listOf("bind", "components", "daemon.port", "management.port", "management.web.port", "management.web.url", "repository.port"), list.entriesList.map { it.key })
        assertTrue(list.entriesList.none { it.isSet })

        val set = stub.setConfig(SetConfigRequest.newBuilder().setKey("management.web.port").setValue("9443").build())
        assertEquals("9443", set.entry.value)
        assertTrue(set.entry.isSet)
        assertEquals(emptyList<String>(), set.restartedList, "no management server runs here")
        assertFalse(set.restartRequired)
        assertEquals("9443", stub.getConfig(GetConfigRequest.newBuilder().setKey("management.web.port").build()).value)
        assertEquals("9443", store().get("management.web.port"))

        val unset = stub.unsetConfig(UnsetConfigRequest.newBuilder().setKey("management.web.port").build())
        assertEquals("8443", unset.entry.value)
        assertFalse(unset.entry.isSet)
        assertFalse(Files.readString(store().file).contains("management.web.port"))
    }

    @Test
    fun aBadValueChangesNothingAndAnUnknownKeyIsNotFound() = runBlocking {
        val stub = stub(daemon())
        assertEquals(Status.Code.INVALID_ARGUMENT, code { stub.setConfig(SetConfigRequest.newBuilder().setKey("daemon.port").setValue("70000").build()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { stub.setConfig(SetConfigRequest.newBuilder().setKey("bind").setValue("192.0.2.7").build()) })
        assertEquals(Status.Code.NOT_FOUND, code { stub.setConfig(SetConfigRequest.newBuilder().setKey("no.such.key").setValue("1").build()) })
        assertEquals(Status.Code.NOT_FOUND, code { stub.getConfig(GetConfigRequest.newBuilder().setKey("no.such.key").build()) })
        assertFalse(store().isSet("daemon.port"))
    }

    @Test
    fun aChangeOfTheDaemonItselfIsSavedAndSaysThatTheDaemonHasToBeRestarted() = runBlocking {
        val stub = stub(daemon())
        val change = stub.setConfig(SetConfigRequest.newBuilder().setKey("daemon.port").setValue("7411").build())
        assertTrue(change.restartRequired)
        assertTrue(change.note.contains("daemon has to be restarted"), change.note)
        assertEquals(emptyList<String>(), change.restartedList)
        assertEquals("7411", store().get("daemon.port"))
    }

    @Test
    fun anArgumentOfTheStartWinsAndTheAnswerSaysSo() = runBlocking {
        val stub = stub(daemon(overrides = mapOf("management.port" to "7500")))
        val change = stub.setConfig(SetConfigRequest.newBuilder().setKey("management.port").setValue("7511").build())
        assertTrue(change.entry.overridden)
        assertEquals("7500", change.entry.value, "the value in effect is the argument")
        assertTrue(change.note.contains("argument"), change.note)
        assertEquals("7511", store().get("management.port"), "the store has the new value for the next start without the argument")
    }

    @Test
    fun theProgramsAreStartedAndStoppedAsTheComponentsSay() = runBlocking {
        val daemon = daemon()
        val stub = stub(daemon)
        // the class path of this test has no repository: the program ends at once, the daemon starts it again with its delay; what counts is what it says
        val started = stub.setConfig(SetConfigRequest.newBuilder().setKey("components").setValue("repository").build())
        assertEquals(listOf("started repository"), started.restartedList)
        val moved = stub.setConfig(SetConfigRequest.newBuilder().setKey("repository.port").setValue("7611").build())
        assertEquals(listOf("restarted repository"), moved.restartedList)
        val stopped = stub.setConfig(SetConfigRequest.newBuilder().setKey("components").setValue("none").build())
        assertEquals(listOf("stopped repository"), stopped.restartedList)
        assertEquals(emptyList<String>(), stub.setConfig(SetConfigRequest.newBuilder().setKey("repository.port").setValue("7612").build()).restartedList)
    }

    @Test
    fun aPeerThatTheDaemonDoesNotTrustCannotReadTheSettings() = runBlocking {
        val daemon = daemon()
        val client = DaemonTestClient(home.resolve("stranger"))
        val channel = client.channelWithoutTrustEntry(daemon)
        closeables += AutoCloseable { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
        val failure = assertThrows<StatusException> { runBlocking { DaemonServiceCoroutineStub(channel).listConfig(ListConfigRequest.getDefaultInstance()) } }
        assertEquals(Status.Code.UNAVAILABLE, failure.status.code)
    }

    @Test
    fun aDaemonWithoutAStoreSaysSo() = runBlocking {
        val daemon = Daemon(home, 0).start().also { closeables += it }
        val stub = stub(daemon)
        assertEquals(Status.Code.FAILED_PRECONDITION, code { stub.listConfig(ListConfigRequest.getDefaultInstance()) })
    }
}
