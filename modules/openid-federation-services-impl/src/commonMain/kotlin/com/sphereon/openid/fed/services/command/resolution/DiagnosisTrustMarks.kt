package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.openid.fed.client.command.trustMark.TrustMarkFederationPolicy
import com.sphereon.openid.fed.client.command.trustMark.TrustMarkValidation
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.mapper.InvalidJwtException
import com.sphereon.openid.fed.client.mapper.JwtDecodingException
import com.sphereon.openid.fed.core.error.TrustMarkExpiredError
import com.sphereon.openid.fed.httpResolver.FederationHttpFetchException
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import io.ktor.http.Parameters
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Trust Mark evidence is independent of the subject's chain outcome. */
internal class DiagnosisTrustMarks(
    private val context: FederationContext,
    private val args: DiagnoseResolutionArgs,
    private val resolver: DiagnosisPathResolver,
    private val evaluator: DiagnosisEvaluator,
    private val verifyDelegation: VerifyTrustMarkDelegationCommand,
    private val now: Long,
) {
    private val issuerPaths = mutableMapOf<String, EvaluatedDiagnosisPath>()

    suspend fun diagnose(subjectClaims: JsonObject?, anchor: DiagnosisStatement): List<DiagnosedTrustMark> {
        val marks = subjectClaims?.get("trust_marks") as? JsonArray ?: return emptyList()
        val anchorConfig = anchor.decoded?.let {
            try { context.json.decodeFromJsonElement(EntityConfigurationStatement.serializer(), it.payload) }
            catch (_: SerializationException) { null }
            catch (_: IllegalArgumentException) { null }
        }
            ?.takeIf { it.iss == args.trustAnchor && it.sub == args.trustAnchor }
        return marks.map { entry ->
            val declaration = entry as? JsonObject
            val jwt = declaration?.string("trust_mark")
            val mark = jwt?.let { decode(it) }
            if (jwt == null || mark == null) {
                DiagnosedTrustMark(declaration?.string("trust_mark_type").orEmpty(), "", null, null,
                    false, false, null, false, TrustMarkStatus.INVALID, TrustMarkStatusSource.SIGNATURE_ONLY, jwt)
            } else diagnoseMark(jwt, mark, declaration?.string("trust_mark_type"), anchorConfig)
        }
    }

    private suspend fun diagnoseMark(jwt: String, mark: Jwt, declaredType: String?,
        anchor: EntityConfigurationStatement?): DiagnosedTrustMark {
        val type = mark.payload.string("trust_mark_type").orEmpty()
        val issuer = mark.payload.string("iss").orEmpty()
        val expiry = mark.payload.epoch("exp")
        val configuration = if (issuer.isNotBlank()) resolver.configuration(issuer) else null
        val signature = if (configuration?.decoded != null) verifyDiagnosticSignature(
            context, jwt, resolver.keys(configuration.claims),
        ).signatureValid else false
        val issuerChain = if (issuer.isNotBlank()) {
            issuerPaths[issuer] ?: evaluator.select(evaluator.candidates(issuer)).also { issuerPaths[issuer] = it }
        } else null
        val issuerChainValid = issuerChain?.chainValid == true
        val recognition = anchor?.let { TrustMarkFederationPolicy.recognize(type, it) }
        val accepted = anchor != null && recognition != null &&
            TrustMarkFederationPolicy.isIssuerAuthorized(recognition, type, issuer, anchor)
        val hasOwner = anchor?.trustMarkOwners?.containsKey(type) == true
        val delegation = mark.payload.string("delegation")
        val delegationValid = when {
            !hasOwner && mark.payload["delegation"] == null -> null
            anchor == null || delegation == null -> false
            else -> {
                val result = verifyDelegation.execute(VerifyTrustMarkDelegationArgs(delegation, type, issuer, anchor, now))
                result.isOk && result.value.isValid
            }
        }
        val localError = localError(mark, type)
        val locallyValid = signature && issuerChainValid && delegationValid != false &&
            localError == null && type.isNotBlank() && declaredType == type && mark.payload.string("sub") == args.subject
        var status = when {
            localError is TrustMarkExpiredError -> TrustMarkStatus.EXPIRED
            !locallyValid -> TrustMarkStatus.INVALID
            expiry != null && expiry <= now -> TrustMarkStatus.EXPIRED
            else -> TrustMarkStatus.ACTIVE
        }
        var source = TrustMarkStatusSource.SIGNATURE_ONLY
        if (args.checkTrustMarkStatus && locallyValid && accepted && status != TrustMarkStatus.EXPIRED) {
            val endpoint = ((configuration?.claims?.get("metadata") as? JsonObject)?.get("federation_entity") as? JsonObject)
                ?.string("federation_trust_mark_status_endpoint")
            if (!endpoint.isNullOrBlank()) {
                if (resolver.reserveNetworkFetch()) {
                    source = TrustMarkStatusSource.STATUS_ENDPOINT
                    status = status(endpoint, jwt, issuer, configuration)
                } else {
                    status = TrustMarkStatus.UNKNOWN
                }
            } else if (locallyValid) status = TrustMarkStatus.UNKNOWN
        }
        return DiagnosedTrustMark(type, issuer, mark.payload.epoch("iat"), expiry, signature, issuerChainValid,
            delegationValid, accepted, status, source, jwt)
    }

    private fun localError(mark: Jwt, type: String) = try {
        TrustMarkValidation.headerError(mark) ?: TrustMarkValidation.subjectError(mark, type, args.subject) ?:
            TrustMarkValidation.timeError(mark, type, now)
    } catch (_: IllegalArgumentException) {
        com.sphereon.openid.fed.core.error.TrustMarkInvalidError(type, "Malformed Trust Mark claims")
    }

    private suspend fun status(endpoint: String, mark: String, issuer: String, configuration: DiagnosisStatement?): TrustMarkStatus {
        val compact = try {
            context.httpResolver.postJwt(endpoint, Parameters.build { append("trust_mark", mark) })
        } catch (_: FederationHttpFetchException) {
            return TrustMarkStatus.UNKNOWN
        }
        val response = decode(compact) ?: return TrustMarkStatus.UNKNOWN
        val claims = response.payload
        val iat = claims.epoch("iat")
        if (response.header.typ != "trust-mark-status-response+jwt" || response.header.alg.equals("none", true) ||
            claims.string("iss") != issuer || claims.string("trust_mark") != mark || iat == null || iat > now + 5 ||
            (claims.containsKey("exp") && (claims.epoch("exp") == null || requireNotNull(claims.epoch("exp")) <= now))) return TrustMarkStatus.UNKNOWN
        if (!verifyDiagnosticSignature(context, compact, resolver.keys(configuration?.claims)).signatureValid) {
            return TrustMarkStatus.UNKNOWN
        }
        return when (claims.string("status")) {
            "active" -> TrustMarkStatus.ACTIVE
            "expired" -> TrustMarkStatus.EXPIRED
            "revoked" -> TrustMarkStatus.REVOKED
            "invalid" -> TrustMarkStatus.INVALID
            else -> TrustMarkStatus.UNKNOWN
        }
    }

    private fun decode(jwt: String): Jwt? = try {
        decodeJWTComponents(jwt)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: InvalidJwtException) {
        null
    } catch (_: JwtDecodingException) {
        null
    }
}
