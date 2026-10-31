package com.sphereon.openid.fed.client.test.trustMark

import com.sphereon.openid.fed.client.test.TrustMarkVerificationTestFixture
import com.sphereon.openid.fed.client.services.TrustMarkServiceImpl

import com.sphereon.openid.fed.client.command.trustMark.TrustMarkStatusPolicy
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommandService
import com.sphereon.core.api.IdkResult
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationNetworkError
import com.sphereon.openid.fed.core.error.TrustMarkInvalidError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.security.MessageDigest

class VerifyTrustMarkStrictStatusTest : TrustMarkVerificationTestFixture() {
    @Test
    fun shouldForwardStrictPolicyThroughTheServiceFacade() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId))
        val mark = trustMark(anchorId, anchorKey)
        val service = TrustMarkServiceImpl(command(fetched = anchor))
        val result = service.verifyTrustMark(mark, anchor, now, subjectId, TrustMarkStatusPolicy(60, 5))
        assertTrue(result.isOk, "the service must return the strict validation outcome: $result")
        assertFalse(result.value.isValid)
        val response = json.encodeToJsonElement(result.value).jsonObject
        assertEquals("STATUS_ENDPOINT_MISSING", response["failureCode"]?.jsonPrimitive?.content)
    }
    @Test
    fun shouldPreserveOptionalStatusBehaviorForUnscopedCallers() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val active = statusResponse(anchorKey, mark, issuedAt = now - 1_000)
        val valid = command(fetched = anchor, statusResponse = active, expectedMark = mark)
            .verifyTrustMark(mark, anchor, now, subjectId)
        assertTrue(valid.isOk && valid.value.isValid, "unscoped status has no configured maximum age: $valid")

        val withdrawn = statusResponse(anchorKey, mark, status = "revoked")
        val rejected = command(fetched = anchor, statusResponse = withdrawn, expectedMark = mark)
            .verifyTrustMark(mark, anchor, now, subjectId)
        assertTrue(rejected.isErr, "an advertised withdrawn status must remain rejected: $rejected")
        assertEquals("Advertised issuer status does not establish an active Trust Mark",
            assertIs<TrustMarkInvalidError>(rejected.error).reason)
    }
    @Test
    fun shouldFailClosedWhenAnOlderVerifierDoesNotSupportStrictStatus() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId))
        var permissiveCalls = 0
        val oldVerifier = object : VerifyTrustMarkCommandService {
            override suspend fun verifyTrustMark(
                trustMark: String,
                trustAnchorConfig: EntityConfigurationStatement,
                currentTime: Long?,
                subject: String?,
            ): IdkResult<TrustMarkValidationResponse, FederationError> {
                permissiveCalls++
                return IdkResult.ok(TrustMarkValidationResponse(isValid = true))
            }
        }
        val result = oldVerifier.verifyTrustMark(
            trustMark(anchorId, anchorKey), anchor, now, subjectId, TrustMarkStatusPolicy(60, 5)
        )
        assertTrue(result.isErr)
        assertEquals(0, permissiveCalls, "a strict request must never fall back to permissive validation")
    }
    @Test
    fun shouldRequireAnAdvertisedStatusEndpointForStrictValidation() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId))
        val result = command(fetched = anchor).execute(
            VerifyTrustMarkArgs(
                trustMark = trustMark(anchorId, anchorKey),
                trustAnchorConfig = anchor,
                currentTime = now,
                subject = subjectId,
                statusPolicy = TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 5),
            )
        )

        assertTrue(result.isOk, "strict validation must return a typed outcome: $result")
        assertFalse(result.value.isValid)
        val response = json.encodeToJsonElement(result.value).jsonObject
        assertEquals("INVALID", response["outcome"]?.jsonPrimitive?.content)
        assertEquals("STATUS_ENDPOINT_MISSING", response["failureCode"]?.jsonPrimitive?.content)
        assertTrue(response["verifiedEvidence"] == null || response["verifiedEvidence"] is JsonNull,
            "a missing status endpoint cannot produce verified evidence")
    }

    @Test
    fun shouldRejectAnUnsafeAdvertisedStatusEndpoint() = runTest {
        val anchorKey = key()
        val mark = trustMark(anchorId, anchorKey)
        for (endpoint in listOf("http://anchor.example/status", "https://user@anchor.example/status", "https://anchor.example/status#fragment")) {
            val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true, statusUrl = endpoint)
            assertRejected(strictResult(anchor, mark, response = null), "INVALID", "STATUS_ENDPOINT_INVALID")
        }
    }

    @Test
    fun shouldReturnVerifiedEvidenceForAnActiveSignedStatus() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark))

        assertEquals("true", response["isValid"]?.jsonPrimitive?.content)
        assertEquals("VALID", response["outcome"]?.jsonPrimitive?.content)
        val evidence = response.getValue("verifiedEvidence").jsonObject
        assertEquals("VERIFIED", evidence["trustLevel"]?.jsonPrimitive?.content)
        assertEquals(anchorId, evidence["issuer"]?.jsonPrimitive?.content)
        assertEquals(subjectId, evidence["subject"]?.jsonPrimitive?.content)
        assertEquals(markType, evidence["markType"]?.jsonPrimitive?.content)
        assertEquals(criteriaReference, evidence["criteriaReference"]?.jsonPrimitive?.content)
        assertEquals(anchorId, evidence["authorityAnchor"]?.jsonPrimitive?.content)
        assertEquals(now.toString(), evidence["checkedAt"]?.jsonPrimitive?.content)
        assertEquals((now + 600).toString(), evidence["expiresAt"]?.jsonPrimitive?.content)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(mark.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expectedDigest, evidence["markDigest"]?.jsonPrimitive?.content)
        assertEquals(now.toString(), evidence["statusCheckedAt"]?.jsonPrimitive?.content)
        assertEquals((now - 10).toDouble(), evidence["statusIssuedAt"]?.jsonPrimitive?.double)
        assertEquals((now + 50).toString(), evidence["statusValidUntil"]?.jsonPrimitive?.content)
    }

    @Test
    fun shouldUseTheListedIssuersVerifiedChainKeyForStrictStatus() = runTest {
        val anchorKey = key()
        val issuerKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(issuerId))
        val issuerConfiguration = configurationClaims(issuerId, listOf(issuerKey), buildJsonObject {
            put("metadata", buildJsonObject {
                put("federation_entity", buildJsonObject { put("federation_trust_mark_status_endpoint", statusEndpoint) })
            })
        })
        val issuerStatement = sign(issuerKey, "entity-statement+jwt", issuerConfiguration)
        val mark = trustMark(issuerId, issuerKey)
        val status = statusResponse(issuerKey, mark, issuer = issuerId)
        val response = strictResult(anchor, mark, status, chain = listOf(issuerStatement, "subordinate", "anchor"))
        assertEquals("VALID", response["outcome"]?.jsonPrimitive?.content)
        assertEquals(issuerId, response.getValue("verifiedEvidence").jsonObject["issuer"]?.jsonPrimitive?.content)
    }

    @Test
    fun shouldPreserveIssuerChainNetworkFailure() = runTest {
        val anchorKey = key()
        val issuerKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(issuerId))
        val mark = trustMark(issuerId, issuerKey)
        val failure = FederationNetworkError(issuerId, "connection failed")
        val result = command(fetched = anchor, chainFailure = failure).execute(
            VerifyTrustMarkArgs(mark, anchor, now, subjectId, TrustMarkStatusPolicy(60, 5))
        )
        assertTrue(result.isErr)
        assertSame(failure, result.error)
    }

    @Test
    fun shouldNameAnIssuerChainThatCannotVouchForTheIssuerAsARefusal() = runTest {
        val anchorKey = key()
        val issuerKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(issuerId))
        val mark = trustMark(issuerId, issuerKey)
        for (chain in listOf(emptyList(), listOf("not-a-statement"))) {
            assertRejected(strictResult(anchor, mark, null, chain = chain), "INVALID", "MARK_ISSUER_CHAIN_INVALID")
        }
    }

    @Test
    fun shouldRetainVerifiedDelegationIdentity() = runTest {
        val anchorKey = key()
        val ownerKey = key()
        val anchor = ownedMarkAnchor(anchorKey, ownerKey)
        val delegation = delegation(ownerKey)
        val mark = trustMark(anchorId, anchorKey, delegation = delegation)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark))

        assertEquals("VALID", response["outcome"]?.jsonPrimitive?.content)
        val evidence = response.getValue("verifiedEvidence").jsonObject
        assertEquals(ownerId, evidence["delegationOwner"]?.jsonPrimitive?.content)
        assertEquals(anchorId, evidence["delegationDelegate"]?.jsonPrimitive?.content)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(delegation.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expectedDigest, evidence["delegationDigest"]?.jsonPrimitive?.content)
    }

    private suspend fun assertInvalidDelegation(create: suspend (Key, Key) -> String) {
        val anchorKey = key()
        val ownerKey = key()
        val otherKey = key()
        val anchor = ownedMarkAnchor(anchorKey, ownerKey)
        val mark = trustMark(anchorId, anchorKey, delegation = create(ownerKey, otherKey))
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark)), "INVALID", "DELEGATION_INVALID")
    }

    @Test
    fun shouldRejectDelegationWithTheWrongOwner() = runTest {
        assertInvalidDelegation { owner, _ -> delegation(owner, owner = "https://other-owner.example") }
    }

    @Test
    fun shouldRejectDelegationWithTheWrongDelegate() = runTest {
        assertInvalidDelegation { owner, _ -> delegation(owner, delegate = "https://other-delegate.example") }
    }

    @Test
    fun shouldRejectDelegationWithTheWrongType() = runTest {
        assertInvalidDelegation { owner, _ -> delegation(owner, type = "https://other.example/mark") }
    }

    @Test
    fun shouldRejectDelegationWithTheWrongSignature() = runTest {
        assertInvalidDelegation { _, other -> delegation(other) }
    }

    @Test
    fun shouldRejectAStatusResponseForAnotherMark() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, "$mark.other"))
        assertRejected(response, "INVALID", "STATUS_MARK_MISMATCH")
    }

    @Test
    fun shouldRejectAStatusResponseFromAnotherIssuer() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark, issuer = "https://other.example"))
        assertRejected(response, "INVALID", "STATUS_ISSUER_MISMATCH")
    }

    @Test
    fun shouldRejectAStatusResponseWithoutAnIssuedAtTime() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark, issuedAt = null))
        assertRejected(response, "INVALID", "STATUS_ISSUED_AT_MISSING")
    }

    @Test
    fun shouldRejectAMalformedStatusResponse() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        assertRejected(strictResult(anchor, mark, "not-a-jwt"), "INVALID", "STATUS_MALFORMED")
    }

    @Test
    fun shouldRejectAMalformedMarkWithoutClaimingVerifiedEvidence() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        assertRejected(strictResult(anchor, "not-a-jwt", response = null), "INVALID", "MARK_MALFORMED")
    }

    @Test
    fun shouldRejectAnOutOfRangeValidationTime() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val result = command(fetched = anchor).execute(
            VerifyTrustMarkArgs(mark, anchor, -1, subjectId, TrustMarkStatusPolicy(60, 5))
        )
        assertTrue(result.isOk)
        assertRejected(json.encodeToJsonElement(result.value).jsonObject, "INVALID", "VALIDATION_TIME_INVALID")
    }

    @Test
    fun shouldRejectAStaleSignedStatus() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark, issuedAt = now - 66))
        assertRejected(response, "INVALID", "STATUS_STALE")
    }

    @Test
    fun shouldAcceptAStatusAtTheFreshnessBoundary() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark, issuedAt = now - 60))
        assertEquals("VALID", response["outcome"]?.jsonPrimitive?.content)
    }

    @Test
    fun shouldNotExtendStatusValidityBeyondItsSignedExpiry() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark, expiry = now + 20))
        assertEquals("VALID", response["outcome"]?.jsonPrimitive?.content)
        val evidence = response.getValue("verifiedEvidence").jsonObject
        assertEquals((now + 20).toString(), evidence["statusValidUntil"]?.jsonPrimitive?.content)
    }

    @Test
    fun shouldRejectAStatusSignedByAnUntrustedKey() = runTest {
        val anchorKey = key()
        val otherKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(otherKey, mark))
        assertRejected(response, "INVALID", "STATUS_SIGNATURE_INVALID")
    }

    @Test
    fun shouldRejectAMarkSignedByAnUntrustedKey() = runTest {
        val anchorKey = key()
        val otherKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, otherKey)
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark)), "INVALID", "MARK_SIGNATURE_INVALID")
    }

    @Test
    fun shouldRejectAnUnrecognizedMarkType() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey, type = "https://other.example/mark")
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark)), "INVALID", "MARK_TYPE_NOT_RECOGNIZED")
    }

    @Test
    fun shouldRejectAnIssuerNotListedForTheMarkType() = runTest {
        val anchorKey = key()
        val otherKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(issuerId, otherKey)
        assertRejected(strictResult(anchor, mark, statusResponse(otherKey, mark, issuer = issuerId)),
            "INVALID", "MARK_ISSUER_UNAUTHORIZED")
    }

    @Test
    fun shouldRejectAWithdrawnMark() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, statusResponse(anchorKey, mark, status = "revoked"))
        assertRejected(response, "INVALID", "STATUS_NOT_ACTIVE")
    }

    @Test
    fun shouldRecheckStatusWhenTheSameMarkIsLaterWithdrawn() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val verifier = command(fetched = anchor, expectedMark = mark, statusResponses = listOf(
            statusResponse(anchorKey, mark), statusResponse(anchorKey, mark, status = "revoked")
        ))
        val args = VerifyTrustMarkArgs(mark, anchor, now, subjectId, TrustMarkStatusPolicy(60, 5))
        val first = verifier.execute(args)
        assertTrue(first.isOk && first.value.isValid)
        val second = verifier.execute(args)
        assertTrue(second.isOk)
        assertRejected(json.encodeToJsonElement(second.value).jsonObject, "INVALID", "STATUS_NOT_ACTIVE")
    }

    @Test
    fun shouldDistinguishStatusServiceUnavailabilityFromInvalidEvidence() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val response = strictResult(anchor, mark, response = null)
        assertRejected(response, "UNAVAILABLE", "STATUS_UNAVAILABLE")
    }

    @Test
    fun shouldRejectAnUnknownStatus() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark, status = "unknown")),
            "INVALID", "STATUS_NOT_ACTIVE")
    }

    @Test
    fun shouldRejectAnExpiredStatus() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark, expiry = now - 5)),
            "INVALID", "STATUS_EXPIRED")
    }

    @Test
    fun shouldRejectAStatusIssuedBeyondTheAllowedClockSkew() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark, issuedAt = now + 6)),
            "INVALID", "STATUS_ISSUED_IN_FUTURE")
    }

    @Test
    fun shouldRejectAMarkIssuedBeyondTheAllowedClockSkew() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey, issuedAt = now + 6)
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark)), "INVALID", "MARK_ISSUED_IN_FUTURE")
    }

    @Test
    fun shouldRejectAMarkAtTheExpiryBoundary() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey, expiry = now - 5)
        assertRejected(strictResult(anchor, mark, statusResponse(anchorKey, mark)), "INVALID", "MARK_EXPIRED")
    }

    @Test
    fun shouldRequireTheExactSubjectForStrictValidation() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val status = statusResponse(anchorKey, mark)
        assertRejected(strictResult(anchor, mark, status, subject = null), "INVALID", "SUBJECT_REQUIRED")
        assertRejected(strictResult(anchor, mark, status, subject = ""), "INVALID", "SUBJECT_REQUIRED")
        assertRejected(strictResult(anchor, mark, status, subject = "https://other.example"),
            "INVALID", "SUBJECT_MISMATCH")
    }

    @Test
    fun shouldRejectStatusPolicyValuesOutsideTheDocumentedBounds() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val status = statusResponse(anchorKey, mark)
        val invalidPolicies = listOf(
            TrustMarkStatusPolicy(maximumAgeSeconds = 0, allowedClockSkewSeconds = 5),
            TrustMarkStatusPolicy(maximumAgeSeconds = -1, allowedClockSkewSeconds = 5),
            TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 0),
            TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = -1),
            TrustMarkStatusPolicy(maximumAgeSeconds = 31_536_001, allowedClockSkewSeconds = 5),
            TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 86_401),
        )
        for (policy in invalidPolicies) {
            assertRejected(strictResult(anchor, mark, status, policy = policy), "INVALID", "STATUS_POLICY_INVALID")
        }
    }

    @Test
    fun shouldPropagateCancellationFromStatusFetch() = runTest {
        val anchorKey = key()
        val anchor = anchorConfiguration(listOf(anchorKey), listOf(anchorId), advertiseStatus = true)
        val mark = trustMark(anchorId, anchorKey)
        val command = command(fetched = anchor, statusFailure = CancellationException("cancelled by caller"))
        assertFailsWith<CancellationException> {
            command.execute(VerifyTrustMarkArgs(mark, anchor, now, subjectId,
                TrustMarkStatusPolicy(maximumAgeSeconds = 60, allowedClockSkewSeconds = 5)))
        }
    }

}
