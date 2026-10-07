// SPDX-License-Identifier: Apache-2.0

package cringle.management.test

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.test.TestTls
import cringle.daemon.Daemon
import cringle.management.ManagementCore
import cringle.management.ManagementServer
import cringle.management.ManagementStore
import cringle.repository.PackageRepository
import cringle.repository.RepositoryServer
import cringle.repository.RepositoryTls
import cringle.router.RouterServer
import cringle.router.RouterTls
import cringle.router.users.UserManager
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The mutual-TLS wiring of the management server for tests (#61): it has an identity and a trust store of its own, and
 * [trust], [startRepository] and [startRouter] make it, the daemon, the engines, the repository and the router trust each
 * other the way an operator would, with the fingerprints. Everything lives below [root]; the real Cringle home is never
 * touched.
 *
 * Call [trust] for a daemon before the first engine of that daemon starts: the daemon writes the entries of its
 * `COMPONENT` peers (this management server, a repository) into the trust file of an engine when it starts it.
 */
public class ManagementTls(private val root: Path) {
    private val tls = TestTls(root)

    /** The identity the management server presents. */
    public val identity: Identity = tls.identity(NAME, ComponentKind.MANAGEMENT)

    /** The peers the management server talks to. */
    public val trustStore: TrustStore = tls.trustStore(NAME)

    private val repositories = ArrayList<TrustStore>()

    /** A [ManagementCore] with this identity and trust store. */
    public fun core(
        store: ManagementStore,
        defaultRepository: String? = null,
        repositoryToken: String? = null,
        routerAddress: String? = null,
    ): ManagementCore = ManagementCore(store, identity, trustStore, defaultRepository, repositoryToken, routerAddress)

    /**
     * Makes [daemon] and this management server trust each other, and makes the management server and every repository
     * started by [startRepository] trust every engine this daemon has and will create.
     */
    public fun trust(daemon: Daemon) {
        daemon.trustStore.add(TrustEntry(identity.publicKeyFingerprint, NAME, TrustKind.COMPONENT))
        trustStore.add(TrustEntry(daemon.identityFingerprint, "daemon", TrustKind.COMPONENT))
        fun engines(entries: List<TrustEntry>) = entries.filter { it.kind == TrustKind.ENGINE }.forEach { engine ->
            trustStore.add(engine.copy(origin = null))
            repositories.forEach { it.add(engine.copy(origin = null)) }
        }
        engines(daemon.trustStore.list())
        daemon.trustStore.onChange(::engines)
        daemons += daemon
    }

    private val daemons = ArrayList<Daemon>()

    /** The fingerprint of the key of the repository that [startRepository] started as number [index] (from 0). */
    public fun repositoryFingerprint(index: Int): String = tls.fingerprint("repository-$index")

    /**
     * Starts a repository over [repository] with mutual TLS. The management server trusts it, it trusts the management
     * server and the engines of the daemons given to [trust], and the engines are told to trust it (it is a `COMPONENT` of
     * every daemon given to [trust], which is why [trust] comes first and the engines start afterwards).
     */
    public fun startRepository(repository: PackageRepository, users: UserManager? = null): RepositoryServer {
        val name = "repository-${repositories.size}"
        val identity = tls.identity(name, ComponentKind.REPOSITORY)
        val store = tls.trustStore(name)
        store.add(TrustEntry(this.identity.publicKeyFingerprint, NAME, TrustKind.COMPONENT))
        daemons.forEach { daemon -> daemon.trustStore.list().filter { it.kind == TrustKind.ENGINE }.forEach { store.add(it.copy(origin = null)) } }
        repositories += store
        trustStore.add(TrustEntry(identity.publicKeyFingerprint, name, TrustKind.COMPONENT))
        daemons.forEach { it.trustStore.add(TrustEntry(identity.publicKeyFingerprint, name, TrustKind.COMPONENT)) }
        return RepositoryServer(repository, users = users, tls = RepositoryTls(identity, store)).start()
    }

    /**
     * Starts a router over [registryFile] with mutual TLS; it trusts the management server. With [trustedByManagement] the
     * management server trusts the router as well (the router of the management server); a router that is only a remote
     * router, which an operator adds with `cringle trust add`, is not.
     */
    public fun startRouter(registryFile: Path, trustedByManagement: Boolean = true): RouterServer {
        val name = "router-${registryFile.hashCode()}"
        val identity = tls.identity(name, ComponentKind.ROUTER)
        val store = tls.trustStore(name)
        store.add(TrustEntry(this.identity.publicKeyFingerprint, NAME, TrustKind.COMPONENT))
        if (trustedByManagement) trustStore.add(TrustEntry(identity.publicKeyFingerprint, name, TrustKind.ROUTER))
        return RouterServer(registryFile, tls = RouterTls(identity, store)).also { it.start() }
    }

    public companion object {
        private const val NAME = "management"

        /**
         * A channel to [server] the way the CLI opens it: TLS 1.3, no client certificate, pinned to the fingerprint of the key
         * of the server. The caller shuts it down.
         */
        public fun channelTo(server: ManagementServer): ManagedChannel {
            val pinned = TrustStore(Files.createTempDirectory("cringle-mgmt-client").resolve("trust.json"))
            pinned.add(TrustEntry(server.core.identity.publicKeyFingerprint, "management-server", TrustKind.SERVER))
            return NettyChannelBuilder.forAddress("127.0.0.1", server.port).sslContext(TlsHelper.channelCredentials(null, pinned)).build()
        }
    }
}
