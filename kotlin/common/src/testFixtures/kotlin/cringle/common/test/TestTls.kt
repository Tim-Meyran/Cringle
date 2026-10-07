// SPDX-License-Identifier: Apache-2.0

package cringle.common.test

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import java.nio.file.Path

/**
 * TLS for tests: identities and trust stores of named peers, kept in folders below [root], so that a test needs no
 * boilerplate to start a server or a client with mutual TLS. Nothing is ever written to the real Cringle home: [root]
 * is the temporary folder the test hands in (for example JUnit's `@TempDir`).
 *
 * Every peer has its own folder `<root>/<name>` with its identity (`certs/`) and its trust store (`trust.json`). A peer
 * trusts only what [trust] or [trustAll] put into its trust store, as in production.
 */
public class TestTls(private val root: Path) {
    private val identities = LinkedHashMap<String, Identity>()
    private val stores = LinkedHashMap<String, TrustStore>()
    private val kinds = LinkedHashMap<String, TrustKind>()

    /** The folder of the peer [name]; the Cringle home for a component that is started with it. */
    public fun home(name: String): Path {
        require(NAME.matches(name)) { "a peer name is one folder name of letters, digits, '-' and '_': '$name'" }
        return root.resolve(name)
    }

    /**
     * The identity of the peer [name]; it is created on first use with the subject `<kind prefix>:<name>` and the same
     * one is returned afterwards (a later [kind] is ignored).
     */
    public fun identity(name: String, kind: ComponentKind = ComponentKind.ENGINE): Identity =
        identities.getOrPut(name) { Identity.loadOrCreate(home(name), kind.commonName(name)) }

    /** The trust store of the peer [name]; it is empty until [trust] or [trustAll] adds a peer. */
    public fun trustStore(name: String): TrustStore = stores.getOrPut(name) { TrustStore(home(name).resolve("trust.json")) }

    /** The fingerprint of the key of the peer [name]. */
    public fun fingerprint(name: String): String = identity(name).publicKeyFingerprint

    /** Makes the peer [truster] trust the peer [trusted] as [kind]. */
    public fun trust(truster: String, trusted: String, kind: TrustKind = TrustKind.COMPONENT) {
        trustStore(truster).add(TrustEntry(fingerprint(trusted), trusted, kind))
        kinds[trusted] = kind
    }

    /** Makes every peer that exists now trust every other one as [kind]. */
    public fun trustAll(kind: TrustKind = TrustKind.COMPONENT) {
        val names = identities.keys.toList()
        for (truster in names) for (trusted in names) if (truster != trusted) trust(truster, trusted, kind)
    }

    /** The context of a server for the peer [name]: its identity, and only clients its trust store trusts. */
    public fun serverSsl(name: String): SslContext = TlsHelper.serverCredentials(identity(name), trustStore(name))

    /** The context of a client for the peer [name]: its identity, and only servers its trust store trusts. */
    public fun clientSsl(name: String): SslContext = TlsHelper.channelCredentials(identity(name), trustStore(name))

    /** A channel of the peer [name] to [host]:[port]; the caller shuts it down. */
    public fun clientChannel(name: String, host: String, port: Int): ManagedChannel =
        NettyChannelBuilder.forAddress(host, port).sslContext(clientSsl(name)).build()

    private companion object {
        val NAME = Regex("[A-Za-z0-9_-]+")
    }
}
