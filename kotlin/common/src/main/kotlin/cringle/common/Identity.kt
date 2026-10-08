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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory

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
    @Volatile
    public var certificate: X509Certificate = initialCertificate
        private set

    /** The fingerprint that identifies this component; it stays the same for the life of the key pair. */
    public val publicKeyFingerprint: String get() = PublicKeyFingerprint.of(keyPair.public)

    /** SHA-256 of the certificate encoding, lowercase hex. Changes with every [renew]; it is not an identity. */
    public val certificateFingerprint: String
        get() = PublicKeyFingerprint.hex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    /** Issues a new certificate with the same key pair and subject, and stores it. The identity stays the same. */
    @JvmOverloads
    public fun renew(validity: Duration = VALIDITY, clock: Clock = Clock.systemUTC()) {
        certificate = issue(commonName, keyPair, validity, clock.instant())
        write(dir.resolve(CERT_FILE), pem("CERTIFICATE", certificate.encoded), secret = false)
    }

    /** How long the current certificate is still valid at [clock]; negative if it has ended. */
    public fun remaining(clock: Clock = Clock.systemUTC()): Duration = Duration.between(clock.instant(), certificate.notAfter.toInstant())

    /**
     * Renews the certificate (same key pair, so the fingerprint and every trust entry stay valid) if it ends within [threshold] or has ended, and says whether
     * it did. An ended certificate is logged as a warning. A running server keeps the certificate it was built with: the new one is used from the next start.
     */
    public fun renewIfDue(threshold: Duration = RENEWAL_THRESHOLD, validity: Duration = VALIDITY, clock: Clock = Clock.systemUTC()): Boolean {
        val left = remaining(clock)
        if (left > threshold) return false
        if (left.isNegative) {
            log.warn("the certificate of {} ended {} ago; issuing a new one", commonName, left.negated())
        } else {
            log.info("the certificate of {} ends in {}; issuing a new one", commonName, left)
        }
        renew(validity, clock)
        return true
    }

    public companion object {
        /** Certificate validity, `[Zu bestätigen]`; renewal triggers are an open point in the architecture. */
        public val VALIDITY: Duration = Duration.ofDays(3650)

        /** A certificate that ends within this time is renewed (`[Zu bestätigen]`). */
        public val RENEWAL_THRESHOLD: Duration = Duration.ofDays(30)

        private val log = LoggerFactory.getLogger("cringle.common.identity")
        private const val KEY_FILE = "identity.key"
        private const val PUB_FILE = "identity.pub"
        private const val CERT_FILE = "identity.crt"

        /**
         * Loads the identity from `<home>/certs`, creating key pair and certificate on first use. [commonName] is the
         * subject (`CN=<commonName>`), for a new component [ComponentKind.commonName]. [validity] only applies to a
         * certificate that is issued now. An existing certificate that ends within [RENEWAL_THRESHOLD] (or has ended) is renewed when [renew] is set; a
         * certificate that is created now is not (tests ask for expired ones).
         */
        @JvmOverloads
        public fun loadOrCreate(home: Path, commonName: String, validity: Duration = VALIDITY, clock: Clock = Clock.systemUTC(), renew: Boolean = true): Identity {
            val dir = home.resolve("certs")
            Files.createDirectories(dir)
            val keyFile = dir.resolve(KEY_FILE)
            val certFile = dir.resolve(CERT_FILE)
            if (Files.exists(keyFile)) {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(unpem(Files.readString(keyFile))))
                val pub = kf.generatePublic(X509EncodedKeySpec(unpem(Files.readString(dir.resolve(PUB_FILE)))))
                val pair = KeyPair(pub, priv)
                val existing = Files.exists(certFile)
                val cert = if (existing) parse(unpem(Files.readString(certFile))) else issue(commonName, pair, validity, clock.instant())
                return Identity(dir, commonName, pair, cert).also {
                    if (!existing) write(certFile, pem("CERTIFICATE", cert.encoded), secret = false) else if (renew) it.renewIfDue(validity = validity, clock = clock)
                }
            }
            val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = gen.generateKeyPair()
            val cert = issue(commonName, pair, validity, clock.instant())
            write(dir.resolve(PUB_FILE), pem("PUBLIC KEY", pair.public.encoded), secret = false)
            write(certFile, pem("CERTIFICATE", cert.encoded), secret = false)
            write(keyFile, pem("PRIVATE KEY", pair.private.encoded), secret = true) // key last: its presence marks a complete identity
            return Identity(dir, commonName, pair, cert)
        }

        private fun issue(commonName: String, pair: KeyPair, validity: Duration, now: Instant): X509Certificate {
            val subject = X500Name("CN=$commonName")
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
