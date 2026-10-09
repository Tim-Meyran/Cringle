// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Public keys as text (PEM): what an administrator copies from one registry to another. */
public object PublicKeyPem {
    private val BODY = Regex("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----")

    /** The PEM text of [key] (`PUBLIC KEY`, X.509 encoding). */
    public fun encode(key: PublicKey): String =
        "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) + "\n-----END PUBLIC KEY-----\n"

    /** The key in [text]: a `PUBLIC KEY` block or the certificate of a registry (`CERTIFICATE`). Throws [IllegalArgumentException] if it is neither. */
    public fun parse(text: String): PublicKey {
        val match = BODY.find(text) ?: throw IllegalArgumentException("no PEM block (-----BEGIN PUBLIC KEY----- or -----BEGIN CERTIFICATE-----) found")
        val der = try {
            Base64.getMimeDecoder().decode(match.groupValues[2].trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("the PEM block is not valid base64")
        }
        return try {
            when (match.groupValues[1]) {
                "PUBLIC KEY" -> KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
                "CERTIFICATE" -> CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)).publicKey
                else -> throw IllegalArgumentException("the PEM block is a ${match.groupValues[1]}, not a PUBLIC KEY or a CERTIFICATE")
            }
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("not an EC public key or certificate: ${e.message}")
        }
    }
}
