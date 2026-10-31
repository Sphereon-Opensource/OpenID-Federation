package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.encodeToHex
import com.sphereon.core.api.http.util.validateHttpsUrlSyntax
import com.sphereon.crypto.core.generic.DigestAlg
import com.sphereon.crypto.core.generic.hash
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.httpResolver.FederationHttpFetchException
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.TrustMarkOwner
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationOutcome
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationFailureCode
import com.sphereon.openid.fed.openapi.models.TrustMarkEvidenceTrustLevel
import com.sphereon.openid.fed.openapi.models.VerifiedTrustMarkEvidence
import io.ktor.http.Parameters
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal suspend fun verifyStrictStatus(
    context: FederationContext,
    onStatusFailure: (Exception) -> Unit,
    compactMark: String,
    type: String,
    issuer: String,
    subject: String,
    authorityAnchor: String,
    configuration: EntityConfigurationStatement,
    mark: Jwt,
    now: Long,
    policy: TrustMarkStatusPolicy,
    owner: TrustMarkOwner?,
): IdkResult<TrustMarkValidationResponse, FederationError> {
    val metadata = configuration.metadata?.get("federation_entity") as? JsonObject
    val advertisedValue = metadata?.get("federation_trust_mark_status_endpoint")
        ?: return strictFailure(TrustMarkValidationFailureCode.STATUS_ENDPOINT_MISSING)
    val advertised = advertisedValue as? JsonPrimitive ?: return strictFailure(TrustMarkValidationFailureCode.STATUS_ENDPOINT_INVALID)
    if (!advertised.isString || advertised.content.isBlank()) {
        return strictFailure(TrustMarkValidationFailureCode.STATUS_ENDPOINT_INVALID)
    }
    val endpoint = advertised.content
    if (validateHttpsUrlSyntax(endpoint).isErr) {
        return strictFailure(TrustMarkValidationFailureCode.STATUS_ENDPOINT_INVALID)
    }
    val statusJwt = try {
        context.httpResolver.postJwt(endpoint, Parameters.build { append("trust_mark", compactMark) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: FederationHttpFetchException) {
        onStatusFailure(e)
        return strictFailure(TrustMarkValidationFailureCode.STATUS_UNAVAILABLE, TrustMarkValidationOutcome.UNAVAILABLE)
    }
    val status = when (val checked = checkSignedStatus(context, statusJwt, configuration, issuer, compactMark, now, policy)) {
        is StatusCheck.Invalid -> return strictFailure(checked.code)
        is StatusCheck.Active -> checked
    }
    val validUntil = requireNotNull(status.validUntil) { "Strict status must have a freshness deadline" }
    val evidence = verifiedEvidence(compactMark, type, issuer, subject, authorityAnchor, mark, now, status.issuedAt, validUntil, owner)
    return IdkResult.ok(TrustMarkValidationResponse(
        isValid = true,
        outcome = TrustMarkValidationOutcome.VALID,
        verifiedEvidence = evidence,
    ))
}

internal sealed interface StatusCheck {
    data class Active(val validUntil: Long?, val issuedAt: Double) : StatusCheck
    data class Invalid(val code: TrustMarkValidationFailureCode, val legacyReason: String? = null) : StatusCheck
}

internal suspend fun checkSignedStatus(
    context: FederationContext,
    statusJwt: String,
    configuration: EntityConfigurationStatement,
    issuer: String,
    compactMark: String,
    now: Long,
    policy: TrustMarkStatusPolicy?,
): StatusCheck {
    val status = try {
        decodeJWTComponents(statusJwt)
    } catch (e: Exception) {
        if (policy == null) {
            throw e
        }
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_MALFORMED)
    }
    if (status.header.typ != "trust-mark-status-response+jwt" || status.header.kid.isNullOrBlank() ||
        (policy != null && (status.header.alg.isBlank() || status.header.alg.equals("none", ignoreCase = true)))) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_MALFORMED,
            "Trust Mark status response has invalid type or key identifier")
    }
    val key = configuration.jwks.propertyKeys.orEmpty().singleOrNull { it.kid == status.header.kid }
        ?: return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_SIGNATURE_INVALID,
            "Trust Mark status response signing key is not an issuer Federation Entity Key")
    if (!context.jwtService.verifyJwtSignature(statusJwt, key)) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_SIGNATURE_INVALID,
            "Trust Mark status response signature is invalid")
    }
    fun text(name: String): String? = (status.payload[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text("iss") != issuer) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_ISSUER_MISMATCH,
            "Trust Mark status response does not match issuer and queried mark")
    }
    if (text("trust_mark") != compactMark) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_MARK_MISMATCH,
            "Trust Mark status response does not match issuer and queried mark")
    }
    val issuedAt = TrustMarkValidation.numericDate(status.payload["iat"])
        ?: return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_ISSUED_AT_MISSING,
            "Trust Mark status response has invalid issued-at time")
    if (policy != null && (issuedAt < 0 || issuedAt > (MAX_SAFE_EPOCH_SECONDS - policy.maximumAgeSeconds).toDouble())) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_MALFORMED)
    }
    if (issuedAt > now.toDouble() + (policy?.allowedClockSkewSeconds ?: 5L)) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_ISSUED_IN_FUTURE,
            "Trust Mark status response has invalid issued-at time")
    }
    if (policy != null && now.toDouble() - issuedAt > policy.maximumAgeSeconds) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_STALE)
    }
    val expiry = status.payload["exp"]?.let {
        TrustMarkValidation.numericDate(it) ?: return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_MALFORMED,
            "Trust Mark status response is expired or has invalid expiry")
    }
    if (policy != null && expiry != null && (expiry < 0 || expiry > MAX_SAFE_EPOCH_SECONDS.toDouble())) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_MALFORMED)
    }
    if (expiry != null && expiry <= now.toDouble()) {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_EXPIRED,
            "Trust Mark status response is expired or has invalid expiry")
    }
    if (text("status") != "active") {
        return StatusCheck.Invalid(TrustMarkValidationFailureCode.STATUS_NOT_ACTIVE,
            "Advertised issuer status does not establish an active Trust Mark")
    }
    val validUntil = policy?.let {
        floor(minOf(issuedAt + it.maximumAgeSeconds, expiry ?: Double.POSITIVE_INFINITY)).toLong()
    }
    return StatusCheck.Active(validUntil, issuedAt)
}

private fun verifiedEvidence(
    compactMark: String,
    type: String,
    issuer: String,
    subject: String,
    authorityAnchor: String,
    mark: Jwt,
    now: Long,
    statusIssuedAt: Double,
    validUntil: Long,
    owner: TrustMarkOwner?,
): VerifiedTrustMarkEvidence {
    val markExpiry = mark.payload["exp"]?.let { TrustMarkValidation.numericDate(it)?.let { value -> floor(value).toLong() } }
    val delegation = (mark.payload["delegation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return VerifiedTrustMarkEvidence(
        trustLevel = TrustMarkEvidenceTrustLevel.VERIFIED,
        issuer = issuer,
        subject = subject,
        markType = type,
        criteriaReference = (mark.payload["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        markDigest = digest(compactMark),
        authorityAnchor = authorityAnchor,
        delegationOwner = if (delegation != null) owner?.sub else null,
        delegationDelegate = if (delegation != null) issuer else null,
        delegationDigest = delegation?.let(::digest),
        checkedAt = now,
        expiresAt = markExpiry,
        statusCheckedAt = now,
        statusIssuedAt = statusIssuedAt,
        statusValidUntil = validUntil,
    )
}

private fun digest(value: String): String = hash(value.encodeToByteArray(), DigestAlg.SHA256).encodeToHex()
