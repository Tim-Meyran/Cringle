// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.ComponentKind
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.test.TestTls
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path

/** A caller of the daemon and engine APIs of a daemon from an unpacked archive, over mutual TLS (#60). */
internal class DistributionTestClient(root: Path) {
    private val tls = TestTls(root)

    init {
        tls.identity(NAME, ComponentKind.MANAGEMENT)
    }

    /** Enters this client into the trust store of the daemon of [cringleHome]; the daemon reads it when it starts. */
    fun trustInDaemonHome(cringleHome: Path) {
        TrustStore(cringleHome.resolve("daemon").resolve("trust.json")).add(TrustEntry(tls.fingerprint(NAME), NAME, TrustKind.COMPONENT))
    }

    /** A channel to the daemon or engine on [port]; the key of the server is taken from the handshake. */
    fun channel(port: Int): ManagedChannel {
        tls.trustStore(NAME).add(TrustEntry(TlsHelper.probeServerFingerprint("127.0.0.1", port), "server-$port", TrustKind.COMPONENT))
        return NettyChannelBuilder.forAddress("127.0.0.1", port).sslContext(tls.clientSsl(NAME)).build()
    }

    private companion object {
        const val NAME = "client"
    }
}
