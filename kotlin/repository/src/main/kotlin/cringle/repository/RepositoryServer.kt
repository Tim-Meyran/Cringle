// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import cringle.common.TlsHelper
import cringle.packaging.PackageKind
import cringle.repository.v1.DownloadRequest
import cringle.repository.v1.DownloadResponse
import cringle.repository.v1.GetPackageRequest
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.ListPackagesResponse
import cringle.repository.v1.ListVersionsRequest
import cringle.repository.v1.ListVersionsResponse
import cringle.repository.v1.PackageMetadata
import cringle.repository.v1.PackageMetadataList
import cringle.repository.v1.PublishRequest
import cringle.repository.v1.PublishResponse
import cringle.repository.v1.RepositoryServiceGrpcKt
import cringle.repository.v1.SetPluginTrustRequest
import cringle.router.users.AuthInterceptor
import cringle.router.users.Permission
import cringle.router.users.UserManager
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Size of the chunks in which packages travel over gRPC. */
internal const val CHUNK_BYTES: Int = 256 * 1024

internal fun toProto(e: PackageEntry): PackageMetadata = PackageMetadata.newBuilder()
    .setKind(if (e.kind == PackageKind.PLUGIN) cringle.repository.v1.PackageKind.PACKAGE_KIND_PLUGIN else cringle.repository.v1.PackageKind.PACKAGE_KIND_PROJECT)
    .setName(e.name).setVersion(e.version).setSha256(e.sha256).setSizeBytes(e.sizeBytes)
    .putAllDependencies(e.dependencies)
    .setPublishedAt(Timestamp.newBuilder().setSeconds(e.publishedAt.epochSecond).setNanos(e.publishedAt.nano))
    .also { b ->
        e.trust?.let {
            b.setTrust(
                if (it == PluginTrust.TRUSTED) cringle.repository.v1.PluginTrust.PLUGIN_TRUST_TRUSTED else cringle.repository.v1.PluginTrust.PLUGIN_TRUST_UNTRUSTED,
            )
        }
    }
    .build()

/**
 * The repository as a gRPC server on the loopback interface. With [tls] it speaks mutual TLS and refuses a client whose key is
 * not in the trust store before any token is looked at; without it, it speaks plaintext (a transition until the fallbacks
 * go away, #39). When
 * [users] is given, every call needs a token: reading needs the `READ` right, publishing `OPERATE`, changing plugin
 * trust `ADMINISTER`.
 */
public class RepositoryServer(
    public val repository: PackageRepository,
    port: Int = 0,
    users: UserManager? = null,
    private val tempDir: Path = Files.createTempDirectory("cringle-repository-upload"),
    tls: RepositoryTls? = null,
) {
    private val service = Service()
    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        .maxInboundMessageSize(CHUNK_BYTES * 4)
        .also { if (tls != null) it.sslContext(TlsHelper.serverCredentials(tls.identity, tls.trustStore)) }
        .addService(
            service.bindService().let { definition ->
                if (users == null) definition else ServerInterceptors.intercept(definition, AuthInterceptor(users, REQUIRED_PERMISSIONS))
            },
        )
        .build()

    /** The port of the server; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts the server. */
    public fun start(): RepositoryServer {
        server.start()
        return this
    }

    /** Stops the server. */
    public fun stop() {
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow()
    }

    private inner class Service : RepositoryServiceGrpcKt.RepositoryServiceCoroutineImplBase() {
        private suspend fun <T> call(body: () -> T): T = try {
            withContext(Dispatchers.IO) { body() }
        } catch (e: RepositoryException) {
            throw when (e.error) {
                RepositoryError.INVALID -> Status.INVALID_ARGUMENT
                RepositoryError.ALREADY_EXISTS -> Status.ALREADY_EXISTS
                RepositoryError.NOT_FOUND -> Status.NOT_FOUND
                RepositoryError.TOO_LARGE -> Status.RESOURCE_EXHAUSTED
                RepositoryError.HASH_MISMATCH -> Status.DATA_LOSS
            }.withDescription(e.message).asException()
        }

        override suspend fun publishPackage(requests: Flow<PublishRequest>): PublishResponse {
            val upload = withContext(Dispatchers.IO) { Files.createTempFile(tempDir, "upload", ".cringle") }
            try {
                var expected: String? = null
                var received = 0L
                var out: OutputStream? = withContext(Dispatchers.IO) { Files.newOutputStream(upload) }
                try {
                    requests.collect { r ->
                        when (r.payloadCase) {
                            PublishRequest.PayloadCase.HEADER -> expected = r.header.expectedSha256.takeIf { it.isNotEmpty() }
                            PublishRequest.PayloadCase.CHUNK -> {
                                received += r.chunk.size()
                                if (received > repository.maxPackageBytes) {
                                    throw Status.RESOURCE_EXHAUSTED.withDescription("package is larger than ${repository.maxPackageBytes} bytes").asException()
                                }
                                withContext(Dispatchers.IO) { r.chunk.writeTo(out!!) }
                            }
                            else -> throw Status.INVALID_ARGUMENT.withDescription("empty publish message").asException()
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) { out?.close() }
                    out = null
                }
                val entry = call { repository.publish(upload, expected) }
                return PublishResponse.newBuilder().setMetadata(toProto(entry)).build()
            } finally {
                withContext(Dispatchers.IO) { Files.deleteIfExists(upload) }
            }
        }

        override suspend fun listPackages(request: ListPackagesRequest): ListPackagesResponse {
            val kind = when (request.kind) {
                cringle.repository.v1.PackageKind.PACKAGE_KIND_PLUGIN -> PackageKind.PLUGIN
                cringle.repository.v1.PackageKind.PACKAGE_KIND_PROJECT -> PackageKind.PROJECT
                else -> null
            }
            return ListPackagesResponse.newBuilder().addAllPackages(call { repository.list(kind) }.map(::toProto)).build()
        }

        override suspend fun listVersions(request: ListVersionsRequest): ListVersionsResponse =
            ListVersionsResponse.newBuilder().addAllVersions(call { repository.versions(request.name) }.map(::toProto)).build()

        override suspend fun getPackage(request: GetPackageRequest): PackageMetadata = toProto(call { repository.get(request.name, request.version) })

        override suspend fun setPluginTrust(request: SetPluginTrustRequest): PackageMetadataList {
            val trust = when (request.trust) {
                cringle.repository.v1.PluginTrust.PLUGIN_TRUST_TRUSTED -> PluginTrust.TRUSTED
                cringle.repository.v1.PluginTrust.PLUGIN_TRUST_UNTRUSTED -> PluginTrust.UNTRUSTED
                else -> throw Status.INVALID_ARGUMENT.withDescription("trust must be specified").asException()
            }
            return PackageMetadataList.newBuilder().addAllPackages(call { repository.setTrust(request.name, trust) }.map(::toProto)).build()
        }

        override fun downloadPackage(request: DownloadRequest): Flow<DownloadResponse> = flow {
            val (entry, file) = call { repository.get(request.name, request.version) to repository.file(request.name, request.version) }
            emit(DownloadResponse.newBuilder().setMetadata(toProto(entry)).build())
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    emit(DownloadResponse.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, n)).build())
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    public companion object {
        /** Permission per method for the [AuthInterceptor]. */
        public val REQUIRED_PERMISSIONS: Map<String, Permission> = mapOf(
            "cringle.repository.v1.RepositoryService/PublishPackage" to Permission.OPERATE,
            "cringle.repository.v1.RepositoryService/ListPackages" to Permission.READ,
            "cringle.repository.v1.RepositoryService/ListVersions" to Permission.READ,
            "cringle.repository.v1.RepositoryService/GetPackage" to Permission.READ,
            "cringle.repository.v1.RepositoryService/SetPluginTrust" to Permission.ADMINISTER,
            "cringle.repository.v1.RepositoryService/DownloadPackage" to Permission.READ,
        )
    }
}
