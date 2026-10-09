package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.client.command.trustChain.TrustAnchorKeyResolver
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainArgs
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A compact JWS split into its decoded protected header and payload; the signature is checked separately. */
internal data class CompactJws(val compact: String, val header: JsonObject, val payload: JsonObject)

private val registrationJson = Json { ignoreUnknownKeys = true }

private val NON_ASYMMETRIC_ALGS = setOf("none", "HS256", "HS384", "HS512")

@OptIn(ExperimentalEncodingApi::class)
internal fun parseCompactJws(compact: String): CompactJws? = try {
    val parts = compact.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() }) {
        null
    } else {
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        CompactJws(
            compact = compact,
            header = registrationJson.parseToJsonElement(decoder.decode(parts[0]).decodeToString()).jsonObject,
            payload = registrationJson.parseToJsonElement(decoder.decode(parts[1]).decodeToString()).jsonObject,
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** A JSON number of seconds since the epoch, rounded down. */
internal fun JsonObject.epochSeconds(name: String): Long? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    if (value.isNaN() || value.isInfinite()) return null
    return kotlin.math.floor(value).toLong()
}

internal fun JsonElement?.stringList(): List<String>? {
    val array = this as? JsonArray ?: return null
    return array.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: return null
    }
}

/** `aud` as a single string or a one-element array whose value is exactly [expected]. */
internal fun JsonObject.hasSoleAudience(expected: String): Boolean = when (val aud = this["aud"]) {
    is JsonPrimitive -> aud.isString && aud.content == expected
    is JsonArray -> aud.stringList() == listOf(expected)
    else -> false
}

internal fun asymmetricAlgError(alg: String?): String? = when {
    alg.isNullOrBlank() -> "JWS header is missing 'alg'"
    alg in NON_ASYMMETRIC_ALGS -> "JWS alg '$alg' is not an asymmetric signing algorithm"
    else -> null
}

internal fun signingAlgError(alg: String?, allowed: List<String>): String? = asymmetricAlgError(alg)
    ?: if (alg !in allowed) "JWS alg '$alg' is not accepted (accepted: ${allowed.joinToString()})" else null

/** Decodes the JWKs in a `keys` array; a key without `kid` cannot be selected and is not returned. */
internal fun decodeKeys(keys: JsonElement?): List<Jwk> = (keys as? JsonArray).orEmpty().mapNotNull { element ->
    try {
        registrationJson.decodeFromJsonElement<Jwk>(element)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

internal fun JsonObject.jwksKeys(): List<Jwk> = decodeKeys((this["jwks"] as? JsonObject)?.get("keys"))

internal suspend fun JwtService.verifiesWith(compact: String, key: Jwk): Boolean = try {
    verifyJwtSignature(compact, key)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** A Trust Chain that verified against an accepted Trust Anchor's out-of-band keys. */
internal data class VerifiedRegistrationChain(
    val chain: List<String>,
    val payloads: List<JsonObject>,
    val trustAnchor: String,
    val validUntilEpochSeconds: Long,
    /** The subject's Resolved Metadata under the chain's policies (OpenID Federation 1.1 §6.1.4, §10.2). */
    val metadata: JsonObject,
) {
    val immediateSuperior: String get() = payloads[1].text("iss")!!
    val immediateSuperiorKeys: List<Jwk> get() = payloads[1].jwksKeys()
}

/**
 * Trust Chain resolution and verification (OpenID Federation 1.1 §10). Only the caller's Trust Anchors are accepted,
 * each with the keys pinned for it or else the keys it publishes itself. A chain is valid only when the subject's
 * metadata also resolves under the chain's policies (§10.2, §6.1.4).
 */
internal class RegistrationTrustChains(
    private val resolveTrustChain: ResolveTrustChainCommand,
    private val verifyTrustChain: VerifyTrustChainCommand,
    private val trustAnchorKeys: TrustAnchorKeyResolver,
) {
    suspend fun resolve(
        subject: String,
        trustAnchors: List<RegistrationTrustAnchor>,
        startingAuthorityHints: List<String>?,
    ): IdkResult<VerifiedRegistrationChain, FederationError> {
        if (trustAnchors.isEmpty()) {
            return federationErr(TrustChainValidationFailedError(subject, "At least one accepted Trust Anchor is required"))
        }
        trustAnchors.firstOrNull { it.pinnedKeys?.isEmpty() == true }?.let { anchor ->
            return federationErr(InvalidTrustAnchorError(anchor.entityIdentifier, "Pinned keys must not be empty"))
        }
        val resolved = resolveTrustChain.execute(
            ResolveTrustChainArgs(
                entityIdentifier = subject,
                trustAnchors = trustAnchors.map { it.entityIdentifier }.toTypedArray(),
                startingAuthorityHints = startingAuthorityHints,
                trustAnchorKeys = trustAnchors.mapNotNull { anchor -> anchor.pinnedKeys?.let { anchor.entityIdentifier to it } }.toMap(),
            )
        )
        if (resolved.isErr) return federationErr(resolved.error)
        return verify(subject, resolved.value.trustChain, trustAnchors)
    }

    suspend fun verify(
        subject: String,
        chain: List<String>,
        trustAnchors: List<RegistrationTrustAnchor>,
    ): IdkResult<VerifiedRegistrationChain, FederationError> {
        fun invalid(reason: String) = federationErr<VerifiedRegistrationChain>(TrustChainValidationFailedError(subject, reason))

        if (chain.size < 2) return invalid("A Trust Chain from the Entity through an Immediate Superior is required")
        val payloads = try {
            chain.map { decodeJWTComponents(it).payload }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return invalid("Trust Chain statement cannot be decoded: ${e.message}")
        }
        if (payloads[0].text("iss") != subject || payloads[0].text("sub") != subject) {
            return invalid("Trust Chain does not start with the Entity Configuration of $subject")
        }
        val trustAnchorId = payloads.last().text("iss") ?: return invalid("Trust Chain has no Trust Anchor issuer")
        val anchor = trustAnchors.firstOrNull { it.entityIdentifier == trustAnchorId }
            ?: return federationErr(InvalidTrustAnchorError(trustAnchorId, "Not an accepted Trust Anchor"))
        val now = getCurrentEpochTimeSeconds()
        val anchorKeys = anchorKeys(anchor, now)
        if (anchorKeys.isErr) return federationErr(anchorKeys.error)
        val verified = verifyTrustChain.verifyTrustChain(
            trustChain = chain.toTypedArray(),
            trustAnchor = trustAnchorId,
            currentTime = now,
            trustAnchorPublicKeys = anchorKeys.value,
        )
        if (verified.isErr) return federationErr(verified.error)
        if (!verified.value.isValid) return invalid(verified.value.errorMessage ?: "Trust Chain verification returned invalid")
        val expiries = payloads.map {
            EntityStatementValidation.conservativeExpiryEpochSeconds(it["exp"]) ?: return invalid("Trust Chain statement has an invalid exp")
        }
        val validUntil = expiries.min()
        if (validUntil <= now) return invalid("Trust Chain has expired")
        val metadata = resolveRegistrationMetadata(subject, payloads[0], payloads.drop(1))
        if (metadata.isErr) return federationErr(metadata.error)
        return IdkResult.ok(VerifiedRegistrationChain(chain, payloads, trustAnchorId, validUntil, metadata.value))
    }

    /** The anchor's keys: pinned keys when configured, otherwise the keys of its own published Entity Configuration. */
    private suspend fun anchorKeys(anchor: RegistrationTrustAnchor, now: Long): IdkResult<List<Jwk>, FederationError> {
        anchor.pinnedKeys?.let { pinned ->
            if (pinned.isEmpty()) return federationErr(InvalidTrustAnchorError(anchor.entityIdentifier, "Pinned keys must not be empty"))
            return IdkResult.ok(pinned)
        }
        return trustAnchorKeys.publishedKeys(anchor.entityIdentifier, now)
    }
}

/** Resolved Metadata of [leafPayload] under the policies of the Subordinate Statements in [superiorPayloads]. */
internal fun resolveRegistrationMetadata(
    entityId: String,
    leafPayload: JsonObject,
    superiorPayloads: List<JsonObject>,
): IdkResult<JsonObject, FederationError> {
    val resolved = MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(leafPayload) + superiorPayloads)
    if (!resolved.isValid) {
        return federationErr(InvalidMetadataError(entityId, resolved.errors.joinToString("; ")))
    }
    return IdkResult.ok(resolved.metadata)
}

/**
 * The keys an Entity publishes in its metadata for one Entity Type (OpenID Federation 1.1 §5.2.1): `jwks` by value,
 * `signed_jwks_uri` verified with a Federation Entity Key from [federationEntityKeys], and `jwks_uri`. A `kid` that
 * appears with different key material in two sources is ambiguous and selects nothing.
 */
internal class EntityTypeKeyResolver(
    private val context: FederationContext,
    private val jwtService: JwtService,
) {
    suspend fun signingKey(
        entityId: String,
        entityTypeMetadata: JsonObject,
        federationEntityKeys: List<Jwk>,
        kid: String,
    ): IdkResult<Jwk, FederationError> {
        val keys = signingKeys(entityId, entityTypeMetadata, federationEntityKeys)
        if (keys.isErr) return federationErr(keys.error)
        return keys.value.firstOrNull { it.kid == kid }?.let { IdkResult.ok(it) }
            ?: federationErr(InvalidMetadataError(entityId, "No signing key with kid '$kid' in the published keys"))
    }

    /** Every published signing key (no `use`, or `use` = `sig`); a `kid` with two different keys is refused. */
    suspend fun signingKeys(
        entityId: String,
        entityTypeMetadata: JsonObject,
        federationEntityKeys: List<Jwk>,
    ): IdkResult<List<Jwk>, FederationError> {
        fun rejected(reason: String) = federationErr<List<Jwk>>(InvalidMetadataError(entityId, reason))

        val sources = buildList {
            entityTypeMetadata["jwks"]?.let { jwks ->
                val keys = (jwks as? JsonObject)?.get("keys") ?: return rejected("jwks is not a JWK Set")
                add(decodeKeys(keys))
            }
            entityTypeMetadata.text("signed_jwks_uri")?.let { uri ->
                if (!uri.startsWith("https://")) return rejected("signed_jwks_uri must use https")
                val signed = fetch(uri) ?: return rejected("signed_jwks_uri could not be retrieved")
                val jws = parseCompactJws(signed) ?: return rejected("signed_jwks_uri did not return a JWS")
                if (jws.header.text("typ") != "jwk-set+jwt") return rejected("Signed JWK Set typ must be jwk-set+jwt")
                val signingKid = jws.header.text("kid") ?: return rejected("Signed JWK Set has no kid")
                val federationKey = federationEntityKeys.firstOrNull { it.kid == signingKid }
                    ?: return rejected("Signed JWK Set is not signed with a Federation Entity Key")
                if (!jwtService.verifiesWith(signed, federationKey)) return rejected("Signed JWK Set signature is invalid")
                if (jws.payload.text("iss") != entityId || jws.payload.text("sub") != entityId) {
                    return rejected("Signed JWK Set iss and sub must be $entityId")
                }
                jws.payload.epochSeconds("exp")?.let { exp ->
                    if (exp <= getCurrentEpochTimeSeconds()) return rejected("Signed JWK Set has expired")
                }
                add(decodeKeys(jws.payload["keys"]))
            }
            entityTypeMetadata.text("jwks_uri")?.let { uri ->
                if (!uri.startsWith("https://")) return rejected("jwks_uri must use https")
                val document = fetch(uri) ?: return rejected("jwks_uri could not be retrieved")
                val keys = try {
                    registrationJson.parseToJsonElement(document).jsonObject["keys"]
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return rejected("jwks_uri did not return a JWK Set")
                }
                add(decodeKeys(keys))
            }
        }
        if (sources.isEmpty()) return rejected("No jwks, signed_jwks_uri or jwks_uri in the metadata")
        val signing = sources.flatten().filter { it.use == null || it.use == "sig" }.distinct()
        signing.groupBy { it.kid }.entries.firstOrNull { it.value.size > 1 }?.let {
            return rejected("kid '${it.key}' identifies different keys in the published key sources")
        }
        if (signing.isEmpty()) return rejected("No signing keys in the published keys")
        return IdkResult.ok(signing)
    }

    private suspend fun fetch(uri: String): String? = try {
        context.httpResolver.get(uri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

internal fun JsonObject.entityTypeObject(entityType: String): JsonObject? = this[entityType] as? JsonObject

internal fun List<String>.toJsonArray(): JsonArray = JsonArray(map { JsonPrimitive(it) })
