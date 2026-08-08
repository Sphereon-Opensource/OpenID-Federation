package com.sphereon.openid.fed.services.clientauth

import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.jose.JwkSet
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.JwsUtils
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.di.session.SessionScope
import com.sphereon.core.api.IdkResult
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.UnauthorizedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwk
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * §8.8 private_key_jwt verification using federation participant resolution (not OAuth ClientRegistry).
 *
 * Crypto path mirrors IDK VerifyClientAuthenticationCommandImpl.verifyJwtAssertion for PrivateKeyJwt.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyFederationClientAuthentication>())
class VerifyFederationClientAuthenticationImpl(
    private val entityResolver: FederationParticipatingEntityResolver,
    private val jwtService: JwtService,
    private val jtiStore: FederationClientAssertionJtiStore,
) : VerifyFederationClientAuthentication {

    override suspend fun verify(
        args: VerifyFederationClientAuthenticationArgs,
    ): FederationResult<VerifiedFederationClientAuthentication> {
        val header = parseJwtHeader(args.assertionJwt)
            ?: return federationErr(UnauthorizedError("JWT assertion header is not valid JSON"))

        val alg = header["alg"]?.jsonPrimitive?.content
            ?: return federationErr(UnauthorizedError("JWT assertion header missing 'alg'"))
        if (alg == "none" || alg in HMAC_ALGS) {
            return federationErr(
                UnauthorizedError("private_key_jwt must use an asymmetric signing alg; got '$alg'")
            )
        }
        if (args.allowedSigningAlgs.isNotEmpty() && alg !in args.allowedSigningAlgs) {
            return federationErr(
                UnauthorizedError(
                    "JWT assertion alg '$alg' is not permitted (supported: ${args.allowedSigningAlgs.joinToString()})"
                )
            )
        }

        val kid = header["kid"]?.jsonPrimitive?.content
            ?: return federationErr(UnauthorizedError("private_key_jwt assertion header missing 'kid'"))

        val entityResult = entityResolver.resolve(
            ResolveParticipatingEntityArgs(
                clientEntityId = args.clientEntityId,
                hostTenantId = args.hostTenantId,
                hostEntityId = args.hostEntityId,
                policy = args.membershipPolicy,
                trustAnchors = args.trustAnchors,
            )
        )
        if (entityResult.isErr) {
            return federationErr(entityResult.error)
        }
        val entity = entityResult.value

        val matched = entity.federationEntityKeys.firstOrNull { it.kid == kid }
            ?: return federationErr(
                UnauthorizedError(
                    "private_key_jwt kid='$kid' does not match any Federation Entity Key of client '${entity.entityId}'"
                )
            )

        val trustedJwks = Json.encodeToJsonElement(
            JwkSet.serializer(),
            JwkSet(keys = arrayOf(toCryptoJwk(matched))),
        ).jsonObject

        val verifyResult = jwtService.verifyJws(
            VerifyJwsArgs(jws = JwsCompact(args.assertionJwt), trustedJwks = trustedJwks)
        )
        if (verifyResult.isErr || !verifyResult.value.isValid) {
            val detail = verifyResult.fold(
                success = { it.errorMessages.joinToString().ifBlank { "signature invalid" } },
                failure = { it.message.defaultMessage },
            )
            return federationErr(UnauthorizedError("JWT assertion signature verification failed: $detail"))
        }

        val claims = verifyResult.value.parsedPayload
        val claimErr = validateAssertionClaims(claims, args.clientEntityId, args.audienceEntityId)
        if (claimErr != null) {
            return federationErr(UnauthorizedError(claimErr))
        }

        val expSeconds = claims["exp"]!!.jsonPrimitive.long
        val jti = claims["jti"]!!.jsonPrimitive.content
        if (!jtiStore.recordIfNew(args.clientEntityId, jti, expSeconds)) {
            return federationErr(UnauthorizedError("JWT assertion 'jti' has already been used"))
        }

        return IdkResult.ok(
            VerifiedFederationClientAuthentication(
                clientEntityId = entity.entityId,
                entity = entity,
                membershipPolicy = args.membershipPolicy,
            )
        )
    }

    private fun validateAssertionClaims(
        claims: JsonObject,
        clientId: String,
        audienceEntityId: String,
    ): String? {
        val iss = claims["iss"]?.jsonPrimitive?.content
        val sub = claims["sub"]?.jsonPrimitive?.content
        if (iss == null || sub == null) return "JWT assertion missing 'iss' or 'sub'"
        if (iss != clientId || sub != clientId) {
            return "JWT assertion iss/sub must both equal client Entity Identifier; got iss='$iss' sub='$sub' client='$clientId'"
        }

        val audValues = claims["aud"]?.let { aud ->
            when (aud) {
                is JsonArray -> aud.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> listOfNotNull(aud.jsonPrimitive.contentOrNull)
            }
        } ?: emptyList()
        if (audValues.isEmpty()) return "JWT assertion missing 'aud'"
        if (audValues.size != 1 || audValues.single() != audienceEntityId) {
            return "JWT assertion 'aud' must be exactly the endpoint Entity Identifier '$audienceEntityId' (got: ${audValues.joinToString()})"
        }

        val expSeconds = claims["exp"]?.jsonPrimitive?.long
            ?: return "JWT assertion missing 'exp'"
        val now = Clock.System.now()
        if (Instant.fromEpochSeconds(expSeconds) <= now) return "JWT assertion has expired"

        val iatSeconds = claims["iat"]?.jsonPrimitive?.long
        if (iatSeconds != null) {
            val skew = now.epochSeconds - iatSeconds
            if (kotlin.math.abs(skew) > ASSERTION_IAT_SKEW_SECONDS) {
                return "JWT assertion 'iat' is outside the ±${ASSERTION_IAT_SKEW_SECONDS}s window"
            }
        }

        val jti = claims["jti"]?.jsonPrimitive?.content
        if (jti.isNullOrBlank()) return "JWT assertion missing 'jti'"
        return null
    }

    private fun toCryptoJwk(jwk: Jwk): CryptoJwk {
        val jwkJson = Json.encodeToString(Jwk.serializer(), jwk)
        return cryptoJsonSerializer.decodeFromString(CryptoJwk.serializer(), jwkJson)
    }

    private fun parseJwtHeader(jwt: String): JsonObject? =
        try {
            val general = JwsUtils.compactToGeneral(JwsCompact(jwt))
            val protectedB64 = general.signatures.firstOrNull()?.protected ?: return null
            JwsUtils.decodeBase64UrlToJson(protectedB64)
        } catch (_: Exception) {
            null
        }

    companion object {
        private val HMAC_ALGS = setOf("HS256", "HS384", "HS512")
        private const val ASSERTION_IAT_SKEW_SECONDS = 300L
    }
}
