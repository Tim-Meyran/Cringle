// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
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
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** The kinds of components that have an [Identity]; the kind is part of the subject name (`CN=<prefix>:<id>`). */
public enum class ComponentKind(public val prefix: String) {
    ENGINE("engine"),
    ROUTER("router"),
    DAEMON("daemon"),
    MANAGEMENT("management"),
    REPOSITORY("repository"),
    ;

    /** The subject common name of the component [id] of this kind, for example `router:r1`. */
    public fun commonName(id: String): String = "$prefix:$id"
}

/** The identity anchor of a public key: SHA-256 of its `SubjectPublicKeyInfo` as lowercase hex (Architecture 5.2). */
public object PublicKeyFingerprint {
    /** Matches what [of] returns. */
    public val pattern: Regex = Regex("[0-9a-f]{64}")

    /** The fingerprint of [key]. */
    public fun of(key: PublicKey): String = hex(MessageDigest.getInstance("SHA-256").digest(key.encoded))

    /** The fingerprint of the public key in [certificate]; it does not change when the certificate is renewed. */
    public fun of(certificate: X509Certificate): String = of(certificate.publicKey)

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * The stable identity of a component: a persistent EC P-256 key pair and a self-signed certificate over it
 * (Architecture chapter 5). Trust is given to the [publicKeyFingerprint], so [renew] can issue a new certificate for
 * the same key pair without breaking any trust. The private key is stored so that only its owner can read it.
 *
 * Files in `<home>/certs`: `identity.key` (private key, PKCS#8), `identity.pub` (public key), `identity.crt`.
 */
public class Identity private constructor(
    private val dir: Path,
    private val commonName: String,
    /** The persistent key pair. */
    public val keyPair: KeyPair,
    initialCertificate: X509Certificate,
) {
    /** The current certificate. */
    public var certificate: X509Certificate = initialCertificate
        private set

    /** The fingerprint that identifies this component; it stays the same for the life of the key pair. */
    public val publicKeyFingerprint: String get() = PublicKeyFingerprint.of(keyPair.public)

    /** SHA-256 of the certificate encoding, lowercase hex. Changes with every [renew]; it is not an identity. */
    public val certificateFingerprint: String
        get() = PublicKeyFingerprint.hex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    /** Issues a new certificate with the same key pair and subject, and stores it. The identity stays the same. */
    public fun renew(validity: Duration = VALIDITY) {
        certificate = issue(commonName, keyPair, validity)
        write(dir.resolve(CERT_FILE), pem("CERTIFICATE", certificate.encoded), secret = false)
    }

    public companion object {
        /** Certificate validity, `[Zu bestätigen]`; renewal triggers are an open point in the architecture. */
        public val VALIDITY: Duration = Duration.ofDays(3650)
        private const val KEY_FILE = "identity.key"
        private const val PUB_FILE = "identity.pub"
        private const val CERT_FILE = "identity.crt"

        /**
         * Loads the identity from `<home>/certs`, creating key pair and certificate on first use. [commonName] is the
         * subject (`CN=<commonName>`), for a new component [ComponentKind.commonName]. [validity] only applies to a
         * certificate that is issued now.
         */
        public fun loadOrCreate(home: Path, commonName: String, validity: Duration = VALIDITY): Identity {
            val dir = home.resolve("certs")
            Files.createDirectories(dir)
            val keyFile = dir.resolve(KEY_FILE)
            val certFile = dir.resolve(CERT_FILE)
            if (Files.exists(keyFile)) {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(unpem(Files.readString(keyFile))))
                val pub = kf.generatePublic(X509EncodedKeySpec(unpem(Files.readString(dir.resolve(PUB_FILE)))))
                val pair = KeyPair(pub, priv)
                val cert = if (Files.exists(certFile)) parse(unpem(Files.readString(certFile))) else issue(commonName, pair, validity)
                return Identity(dir, commonName, pair, cert).also {
                    if (!Files.exists(certFile)) write(certFile, pem("CERTIFICATE", cert.encoded), secret = false)
                }
            }
            val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = gen.generateKeyPair()
            val cert = issue(commonName, pair, validity)
            write(dir.resolve(PUB_FILE), pem("PUBLIC KEY", pair.public.encoded), secret = false)
            write(certFile, pem("CERTIFICATE", cert.encoded), secret = false)
            write(keyFile, pem("PRIVATE KEY", pair.private.encoded), secret = true) // key last: its presence marks a complete identity
            return Identity(dir, commonName, pair, cert)
        }

        private fun issue(commonName: String, pair: KeyPair, validity: Duration): X509Certificate {
            val subject = X500Name("CN=$commonName")
            val now = Instant.now()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
                Date.from(now.minusSeconds(60)),
                Date.from(now.plus(validity)),
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
            // a secret gets its owner-only rights when the file is created, not afterwards
            if (secret) return OwnerOnlyFiles.writeAtomically(file, content)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.deleteIfExists(tmp)
            Files.writeString(tmp, content)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
