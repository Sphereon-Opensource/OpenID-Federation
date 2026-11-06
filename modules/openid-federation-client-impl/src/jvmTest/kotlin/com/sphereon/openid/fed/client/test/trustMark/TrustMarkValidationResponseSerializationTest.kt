package com.sphereon.openid.fed.client.test.trustMark

import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalSerializationApi::class)
class TrustMarkValidationResponseSerializationTest {
    private val json = Json { explicitNulls = false }

    private fun assertRoundTrip(input: String) {
        val original = json.parseToJsonElement(input)
        val value = json.decodeFromJsonElement<TrustMarkValidationResponse>(original)
        assertEquals(original, json.encodeToJsonElement(value))
    }

    @Test
    fun shouldRoundTripTheExistingUnscopedResponse() {
        assertRoundTrip("""{"isValid":true}""")
    }

    @Test
    fun shouldDecodeExplicitNullsInOptionalFields() {
        val element = json.parseToJsonElement(
            """{"isValid":false,"errorMessage":null,"outcome":null,"failureCode":null,"verifiedEvidence":null}"""
        )
        assertFalse(json.decodeFromJsonElement<TrustMarkValidationResponse>(element).isValid)
    }

    @Test
    fun shouldRoundTripVerifiedEvidenceWithoutDroppingFields() {
        assertRoundTrip("""
            {
              "isValid": true,
              "outcome": "VALID",
              "verifiedEvidence": {
                "trustLevel": "VERIFIED",
                "issuer": "https://issuer.example",
                "subject": "https://subject.example",
                "markType": "https://authority.example/marks/example",
                "criteriaReference": "https://authority.example/criteria/v1",
                "markDigest": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "authorityAnchor": "https://authority.example",
                "delegationOwner": "https://owner.example",
                "delegationDelegate": "https://issuer.example",
                "delegationDigest": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "checkedAt": 1800000000,
                "expiresAt": 1800000600,
                "statusCheckedAt": 1800000000,
                "statusValidUntil": 1800000050
              }
            }
        """.trimIndent())
    }

    @Test
    fun shouldRoundTripFractionalSignedStatusIssuanceTime() {
        val input = json.parseToJsonElement("""{"isValid":true,"outcome":"VALID","verifiedEvidence":{"trustLevel":"VERIFIED","issuer":"https://issuer.example","subject":"https://subject.example","markType":"https://authority.example/mark","markDigest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","authorityAnchor":"https://authority.example","checkedAt":1800000000,"statusCheckedAt":1800000000,"statusIssuedAt":1799999990.25,"statusValidUntil":1800000050}}""")
        val decoded = json.decodeFromJsonElement<TrustMarkValidationResponse>(input)
        assertEquals(1799999990.25, decoded.verifiedEvidence?.statusIssuedAt)
        assertEquals(decoded, json.decodeFromJsonElement<TrustMarkValidationResponse>(json.encodeToJsonElement(decoded)))
    }

    @Test
    fun shouldRoundTripInvalidOutcome() {
        assertRoundTrip("""{"isValid":false,"outcome":"INVALID","failureCode":"STATUS_STALE"}""")
    }

    @Test
    fun shouldRoundTripUnavailableOutcome() {
        assertRoundTrip("""{"isValid":false,"outcome":"UNAVAILABLE","failureCode":"STATUS_UNAVAILABLE"}""")
    }

    @Test
    fun shouldRejectUnknownOutcome() {
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement<TrustMarkValidationResponse>(
                json.parseToJsonElement("""{"isValid":false,"outcome":"OTHER"}""")
            )
        }
    }

    @Test
    fun shouldRejectUnknownFailureCode() {
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement<TrustMarkValidationResponse>(
                json.parseToJsonElement("""{"isValid":false,"failureCode":"OTHER"}""")
            )
        }
    }
}
