// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * The stable identity of an engine: a persistent EC P-256 key pair and a self-signed certificate over it
 * (Architecture chapter 5). The private key never leaves the engine and is stored owner-only where the file
 * system supports POSIX permissions. [renew] issues a new certificate for the same key pair.
 */
public class EngineIdentity private constructor(
    private val dir: Path,
    private val engineId: String,
    /** The persistent key pair. */
    public val keyPair: KeyPair,
    initialCertificate: X509Certificate,
) {
    /** The current certificate. */
    public var certificate: X509Certificate = initialCertificate
        private set

    /** SHA-256 of the certificate encoding, lowercase hex. */
    public val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /** Issues a new certificate with the same key pair and subject, and stores it. The identity stays the same. */
    public fun renew() {
        certificate = issue(engineId, keyPair)
        write(dir.resolve(CERT_FILE), pem("CERTIFICATE", certificate.encoded), secret = false)
    }

    public companion object {
        /** Certificate validity, `[Zu bestätigen]`; renewal triggers are an open point in the architecture. */
        public val VALIDITY: Duration = Duration.ofDays(3650)
        private const val KEY_FILE = "identity.key"
        private const val PUB_FILE = "identity.pub"
        private const val CERT_FILE = "identity.crt"

        /** Loads the identity from `<engineDir>/certs`, creating key pair and certificate on first use. */
        public fun loadOrCreate(engineDir: Path, engineId: String): EngineIdentity {
            val dir = engineDir.resolve("certs")
            Files.createDirectories(dir)
            val keyFile = dir.resolve(KEY_FILE)
            val certFile = dir.resolve(CERT_FILE)
            if (Files.exists(keyFile)) {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(unpem(Files.readString(keyFile))))
                val pub = kf.generatePublic(X509EncodedKeySpec(unpem(Files.readString(dir.resolve(PUB_FILE)))))
                val pair = KeyPair(pub, priv)
                val cert = if (Files.exists(certFile)) parse(unpem(Files.readString(certFile))) else issue(engineId, pair)
                return EngineIdentity(dir, engineId, pair, cert).also {
                    if (!Files.exists(certFile)) write(certFile, pem("CERTIFICATE", cert.encoded), secret = false)
                }
            }
            val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = gen.generateKeyPair()
            val cert = issue(engineId, pair)
            write(dir.resolve(PUB_FILE), pem("PUBLIC KEY", pair.public.encoded), secret = false)
            write(certFile, pem("CERTIFICATE", cert.encoded), secret = false)
            write(keyFile, pem("PRIVATE KEY", pair.private.encoded), secret = true) // key last: its presence marks a complete identity
            return EngineIdentity(dir, engineId, pair, cert)
        }

        private fun issue(engineId: String, pair: KeyPair): X509Certificate {
            val subject = X500Name("CN=$engineId")
            val now = Instant.now()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
                Date.from(now.minusSeconds(60)),
                Date.from(now.plus(VALIDITY)),
                subject,
                pair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
            return JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(builder.build(signer))
        }

        private fun parse(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        private fun pem(type: String, der: ByteArray): String =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
                "\n-----END $type-----\n"

        private fun unpem(text: String): ByteArray =
            Base64.getMimeDecoder().decode(text.lines().filterNot { it.startsWith("-----") }.joinToString(""))

        private fun write(file: Path, content: String, secret: Boolean) {
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.deleteIfExists(tmp)
            if (secret && file.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                Files.createFile(tmp)
                if (secret) {
                    val f = tmp.toFile()
                    f.setReadable(false, false)
                    f.setWritable(false, false)
                    f.setReadable(true, true)
                    f.setWritable(true, true)
                }
            }
            Files.writeString(tmp, content, StandardOpenOption.WRITE)
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
