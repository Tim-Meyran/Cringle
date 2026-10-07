// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.common.ComponentKind
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.test.TestTls
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path

/**
 * A caller of the management API of an engine over mutual TLS (#60): the engine accepts only peers in its trust store
 * (`<engine dir>/trust.json`), so a test makes the engine trust this client and the client trust the engine's key first.
 */
internal class TestClient(root: Path) {
    private val tls = TestTls(root)

    init {
        tls.identity(NAME, ComponentKind.DAEMON)
    }

    /** The fingerprint of the key of this client. */
    val fingerprint: String get() = tls.fingerprint(NAME)

    /** Enters this client into the trust file of the engine [engineId] below [home], for an engine process that starts later. */
    fun allow(home: Path, engineId: String) {
        TrustStore(CringleHome.engineDir(home, engineId).resolve("trust.json")).add(TrustEntry(fingerprint, NAME, TrustKind.COMPONENT))
    }

    /** A channel to the running [engine], which trusts this client from now on. */
    fun channel(engine: Engine): ManagedChannel {
        engine.trustStore.add(TrustEntry(fingerprint, NAME, TrustKind.COMPONENT))
        return channel("127.0.0.1", engine.managementPort, engine.identity.publicKeyFingerprint)
    }

    /** A channel to the running [engine] that does NOT enter this client into the trust store of the engine. */
    fun channelWithoutTrustEntry(engine: Engine): ManagedChannel =
        channel("127.0.0.1", engine.managementPort, engine.identity.publicKeyFingerprint)

    /** A channel to the engine process on [port], whose trust file [allow] has written; its key is taken from the handshake. */
    fun channel(port: Int): ManagedChannel = channel("127.0.0.1", port, TlsHelper.probeServerFingerprint("127.0.0.1", port))

    private fun channel(host: String, port: Int, engineFingerprint: String): ManagedChannel {
        tls.trustStore(NAME).add(TrustEntry(engineFingerprint, "engine", TrustKind.ENGINE))
        return NettyChannelBuilder.forAddress(host, port).sslContext(tls.clientSsl(NAME)).build()
    }

    private companion object {
        const val NAME = "client"
    }
}
