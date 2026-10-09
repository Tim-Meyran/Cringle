// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The content of a federated token: who ([subject]) of which registry ([issuer]) is meant, for how long, and the public key the signature is meant to be checked with. */
public data class FederatedClaims(
    val issuer: String,
    val subject: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    /** X.509 encoding of the public key of the issuing registry. */
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is FederatedClaims && issuer == other.issuer && subject == other.subject &&
        issuedAt == other.issuedAt && expiresAt == other.expiresAt && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = issuer.hashCode() * 31 + subject.hashCode()
}

/** A federated token that was split into its parts; nothing is checked yet, see [FederatedToken.verify]. */
public class ParsedFederatedToken internal constructor(public val claims: FederatedClaims, internal val payload: ByteArray, internal val signature: ByteArray)

/**
 * A token that a registry issues for one of its users so that another registry, which trusts it, accepts the user as `name@registry`
 * (Architecture 6.2): `fed1.<payload>.<signature>`, both parts base64url without padding. The payload is JSON with `iss` (the name of the
 * issuing registry), `sub` (the user), `iat`, `exp` (epoch seconds) and `key` (the public key of the issuer, base64 of its X.509 encoding); the
 * signature is SHA256withECDSA over the payload bytes. The key only travels with the token so that it can be compared with the trusted one:
 * the receiver trusts the fingerprint it stored, never the key in the token.
 */
public object FederatedToken {
    /** The start of every federated token. */
    public const val PREFIX: String = "fed1."

    /** The longest lifetime a federated token may have; there is no revocation, so a token ends by itself. */
    public val MAX_LIFETIME: Duration = Duration.ofDays(30)

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** Issues a token for [user] of [registry], signed with [keyPair] (the identity key of the registry), valid for [ttl]. */
    public fun issue(registry: String, user: String, keyPair: KeyPair, ttl: Duration, clock: Clock = Clock.systemUTC()): String {
        require(!ttl.isNegative && !ttl.isZero) { "the lifetime must be positive" }
        require(ttl <= MAX_LIFETIME) { "the lifetime of a federated token is at most ${MAX_LIFETIME.toDays()} days" }
        val now = clock.instant()
        val payload = buildJsonObject {
            put("iss", registry)
            put("sub", user)
            put("iat", now.epochSecond)
            put("exp", now.plus(ttl).epochSecond)
            put("key", Base64.getEncoder().encodeToString(keyPair.public.encoded))
        }.toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance(ALGORITHM).apply {
            initSign(keyPair.private)
            update(payload)
        }
        return PREFIX + encoder.encodeToString(payload) + "." + encoder.encodeToString(signer.sign())
    }

    /** Splits [text] into its parts; `null` if it is not a well-formed federated token. Nothing about trust or the signature is checked. */
    public fun parse(text: String): ParsedFederatedToken? = try {
        if (!text.startsWith(PREFIX)) {
            null
        } else {
            val parts = text.removePrefix(PREFIX).split('.')
            if (parts.size != 2) {
                null
            } else {
                val decoder = Base64.getUrlDecoder()
                val payload = decoder.decode(parts[0])
                val signature = decoder.decode(parts[1])
                val json = Json.parseToJsonElement(String(payload, Charsets.UTF_8)) as JsonObject
                fun text(key: String) = (json[key] as JsonPrimitive).contentOrNull ?: error("no $key")
                fun seconds(key: String) = Instant.ofEpochSecond((json[key] as JsonPrimitive).long)
                ParsedFederatedToken(
                    FederatedClaims(text("iss"), text("sub"), seconds("iat"), seconds("exp"), Base64.getDecoder().decode(text("key"))),
                    payload,
                    signature,
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Whether the signature of [token] is valid for [publicKey]. */
    public fun verify(token: ParsedFederatedToken, publicKey: PublicKey): Boolean = try {
        Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey)
            update(token.payload)
            verify(token.signature)
        }
    } catch (_: Exception) {
        false
    }

    private const val ALGORITHM = "SHA256withECDSA"
}
