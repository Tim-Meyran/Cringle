// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import com.google.protobuf.ByteString
import cringle.common.TlsHelper
import cringle.packaging.PackageHash
import cringle.packaging.PackageInfo
import cringle.packaging.PackageKind
import cringle.packaging.PackageSource
import cringle.packaging.Version
import cringle.repository.v1.DownloadRequest
import cringle.repository.v1.GetPackageRequest
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.ListVersionsRequest
import cringle.repository.v1.PublishHeader
import cringle.repository.v1.PublishRequest
import cringle.repository.v1.RepositoryServiceGrpcKt
import cringle.repository.v1.SetPluginTrustRequest
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/** Thrown when the repository refuses a call or cannot be reached; [status] is the gRPC status. */
public class RepositoryClientException(public val status: Status.Code, message: String) : RuntimeException(message)

/**
 * A client of a repository. It is a [PackageSource] for the resolver (the `versions` and `info` calls block) and
 * downloads packages with hash verification: a file whose SHA-256 differs from the published hash is never kept.
 * [token] is sent as `Bearer` credentials when the repository requires authentication. The channel is mutual TLS and only a server
 * whose key is in the trust store of [tls] is accepted; there is no plaintext mode.
 */
public class RepositoryClient(address: String, private val token: String? = null, tls: RepositoryTls) : PackageSource, AutoCloseable {
    private val channel: ManagedChannel = NettyChannelBuilder.forTarget(address)
        .sslContext(TlsHelper.channelCredentials(tls.identity, tls.trustStore))
        .maxInboundMessageSize(CHUNK_BYTES * 4)
        .build()
    private val stub = RepositoryServiceGrpcKt.RepositoryServiceCoroutineStub(channel).let { s ->
        if (token == null) s else s.withCallCredentials(object : CallCredentials() {
            override fun applyRequestMetadata(info: RequestInfo, executor: Executor, applier: MetadataApplier) {
                applier.apply(Metadata().also { it.put(cringle.router.users.AuthInterceptor.AUTHORIZATION, "Bearer $token") })
            }
        })
    }

    private suspend fun <T> call(body: suspend () -> T): T = try {
        body()
    } catch (e: StatusException) {
        throw RepositoryClientException(e.status.code, e.status.description ?: e.status.code.name)
    }

    private fun entry(m: cringle.repository.v1.PackageMetadata) = PackageEntry(
        if (m.kind == cringle.repository.v1.PackageKind.PACKAGE_KIND_PLUGIN) PackageKind.PLUGIN else PackageKind.PROJECT,
        m.name, m.version, m.sha256, m.sizeBytes, m.dependenciesMap, Instant.ofEpochSecond(m.publishedAt.seconds, m.publishedAt.nanos.toLong()),
        when (m.trust) {
            cringle.repository.v1.PluginTrust.PLUGIN_TRUST_TRUSTED -> PluginTrust.TRUSTED
            cringle.repository.v1.PluginTrust.PLUGIN_TRUST_UNTRUSTED -> PluginTrust.UNTRUSTED
            else -> null
        },
    )

    /** Publishes the package file [file]; the hash is sent along so that transfer errors are detected. */
    public suspend fun publish(file: Path): PackageEntry = call {
        val hash = PackageHash.sha256(file)
        val requests = flow {
            emit(PublishRequest.newBuilder().setHeader(PublishHeader.newBuilder().setExpectedSha256(hash)).build())
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    emit(PublishRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, n)).build())
                }
            }
        }
        entry(stub.publishPackage(requests).metadata)
    }

    /** All versions of all packages, optionally only of [kind]. */
    public suspend fun list(kind: PackageKind? = null): List<PackageEntry> = call {
        val k = when (kind) {
            PackageKind.PLUGIN -> cringle.repository.v1.PackageKind.PACKAGE_KIND_PLUGIN
            PackageKind.PROJECT -> cringle.repository.v1.PackageKind.PACKAGE_KIND_PROJECT
            null -> cringle.repository.v1.PackageKind.PACKAGE_KIND_UNSPECIFIED
        }
        stub.listPackages(ListPackagesRequest.newBuilder().setKind(k).build()).packagesList.map(::entry)
    }

    /** The versions of [name]. */
    public suspend fun listVersions(name: String): List<PackageEntry> = call {
        stub.listVersions(ListVersionsRequest.newBuilder().setName(name).build()).versionsList.map(::entry)
    }

    /** The metadata of one version. */
    public suspend fun get(name: String, version: String): PackageEntry = call {
        entry(stub.getPackage(GetPackageRequest.newBuilder().setName(name).setVersion(version).build()))
    }

    /** Sets the trust status of a plugin. */
    public suspend fun setPluginTrust(name: String, trust: PluginTrust): List<PackageEntry> = call {
        val t = if (trust == PluginTrust.TRUSTED) cringle.repository.v1.PluginTrust.PLUGIN_TRUST_TRUSTED else cringle.repository.v1.PluginTrust.PLUGIN_TRUST_UNTRUSTED
        stub.setPluginTrust(SetPluginTrustRequest.newBuilder().setName(name).setTrust(t).build()).packagesList.map(::entry)
    }

    /**
     * Downloads [name]@[version] to [target] and checks the hash. If the hash differs the file is removed and
     * [cringle.packaging.PackageHashMismatchException] is thrown.
     */
    public suspend fun download(name: String, version: String, target: Path): PackageEntry = call {
        val tmp = target.resolveSibling(target.fileName.toString() + ".part")
        Files.createDirectories(target.toAbsolutePath().parent)
        var metadata: PackageEntry? = null
        try {
            Files.newOutputStream(tmp).use { out ->
                stub.downloadPackage(DownloadRequest.newBuilder().setName(name).setVersion(version).build()).collect { r ->
                    if (r.hasMetadata()) metadata = entry(r.metadata) else r.chunk.writeTo(out)
                }
            }
            val expected = (metadata ?: throw RepositoryClientException(Status.Code.DATA_LOSS, "the repository sent no metadata")).sha256
            PackageHash.verify(tmp, expected)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            metadata!!
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun versions(name: String): List<Version> = runBlocking { listVersions(name) }.map { Version.parse(it.version) }

    override fun info(name: String, version: Version): PackageInfo {
        val e = runBlocking { get(name, version.toString()) }
        return PackageInfo(name, version, e.dependencies, e.sha256)
    }

    override fun close() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }
}
