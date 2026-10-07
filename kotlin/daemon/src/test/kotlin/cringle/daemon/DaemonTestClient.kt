// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.ComponentKind
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.test.TestTls
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path

/**
 * A caller of the daemon API and of the engine API over mutual TLS (#60), like the management server: the daemon has to
 * trust it as `COMPONENT` before it connects, and the daemon writes the same entry into the trust file of every engine it
 * starts afterwards, so enter it before the first engine starts.
 */
internal class DaemonTestClient(root: Path) {
    private val tls = TestTls(root)

    init {
        tls.identity(NAME, ComponentKind.MANAGEMENT)
    }

    /** The fingerprint of the key of this client. */
    val fingerprint: String get() = tls.fingerprint(NAME)

    /** Makes [daemon] (and the engines it starts from now on) trust this client. */
    fun trustedBy(daemon: Daemon) {
        daemon.trustStore.add(TrustEntry(fingerprint, NAME, TrustKind.COMPONENT))
    }

    /** A channel to [daemon], whose key this client trusts; the daemon is made to trust this client, too. */
    fun channel(daemon: Daemon): ManagedChannel {
        trustedBy(daemon)
        tls.trustStore(NAME).add(TrustEntry(daemon.identityFingerprint, "daemon", TrustKind.COMPONENT))
        return NettyChannelBuilder.forAddress("127.0.0.1", daemon.port).sslContext(tls.clientSsl(NAME)).build()
    }

    /** A channel to [daemon] that does NOT make the daemon trust this client. */
    fun channelWithoutTrustEntry(daemon: Daemon): ManagedChannel {
        tls.trustStore(NAME).add(TrustEntry(daemon.identityFingerprint, "daemon", TrustKind.COMPONENT))
        return NettyChannelBuilder.forAddress("127.0.0.1", daemon.port).sslContext(tls.clientSsl(NAME)).build()
    }

    /** A channel to the engine on [port]; its key is taken from the handshake, the engine has to trust this client. */
    fun engineChannel(port: Int): ManagedChannel {
        tls.trustStore(NAME).add(TrustEntry(TlsHelper.probeServerFingerprint("127.0.0.1", port), "engine", TrustKind.ENGINE))
        return NettyChannelBuilder.forAddress("127.0.0.1", port).sslContext(tls.clientSsl(NAME)).build()
    }

    private companion object {
        const val NAME = "client"
    }
}
