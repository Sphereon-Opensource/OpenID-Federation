package com.sphereon.openid.fed.client.mapper

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Raw compact-JWT parsing tests only; these fixtures do not verify a signature. */
@OptIn(ExperimentalEncodingApi::class)
class JsonMapperDuplicateMemberTest {
    private val header = """{"alg":"ES256","kid":"k1","typ":"entity-statement+jwt"}"""
    private val base = """{"iss":"https://superior.example","sub":"https://leaf.example","iat":1700000000,"exp":1700003600,"jwks":{"keys":[]}"""

    @Test
    fun rejectsLiteralEscapedAndNullFirstDuplicateRootClaims() {
        val payloads = listOf(
            // A later metadata_policy cannot hide the earlier policy in the signed payload.
            statement(""""metadata_policy":{"oauth_client":{"grant_types":{"value":["authorization_code"]}}},"metadata_policy":{"oauth_client":{"grant_types":{"value":["implicit"]}}}"""),
            statement(""""\u0069ss":"https://other.example""""),
            statement(""""note":null,"note":"later""""),
        )

        payloads.forEach(::assertDuplicateRejected)
    }

    @Test
    fun rejectsLiteralEscapedAndThreeOccurrenceDuplicatePolicyEntityTypes() {
        val payloads = listOf(
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"]}},"oauth_client":{"grant_types":{"value":["implicit"]}}}"""),
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"]}},"oauth_\u0063lient":{"grant_types":{"value":["implicit"]}}}"""),
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"]}},"oauth_client":{"grant_types":{"value":["implicit"]}},"oauth_client":{"grant_types":{"value":["refresh_token"]}}}"""),
        )

        payloads.forEach(::assertDuplicateRejected)
    }

    @Test
    fun rejectsLiteralEscapedAndThreeOccurrenceDuplicateMetadataParameters() {
        val payloads = listOf(
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"]},"grant_types":{"value":["implicit"]}}}"""),
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"]},"\u0067rant_types":{"value":["implicit"]}}}"""),
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"]},"grant_types":{"value":["implicit"]},"grant_types":{"value":["refresh_token"]}}}"""),
        )

        payloads.forEach(::assertDuplicateRejected)
    }

    @Test
    fun rejectsLiteralEscapedAndNullFirstDuplicateOperators() {
        val payloads = listOf(
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"],"value":["implicit"]}}}"""),
            policyStatement("""{"oauth_client":{"grant_types":{"value":["authorization_code"],"v\u0061lue":["implicit"]}}}"""),
            policyStatement("""{"oauth_client":{"grant_types":{"value":null,"value":["authorization_code"]}}}"""),
        )

        payloads.forEach(::assertDuplicateRejected)
    }

    @Test
    fun decodesStatementWithoutPolicyAndPreservesSuppliedSignatureSegment() {
        val payload = """{"iss":"https://leaf.example","sub":"https://leaf.example","iat":1700000000,"exp":1700003600,"jwks":{"keys":[]}}"""
        val decoded = decodeJWTComponents(compact(payload, "supplied-signature-segment"))

        assertEquals(JsonPrimitive("https://leaf.example"), decoded.payload["iss"])
        assertTrue("metadata_policy" !in decoded.payload)
        assertEquals("supplied-signature-segment", decoded.signature)
    }

    @Test
    fun acceptsDistinctPolicyRolesParametersAndOperators() {
        val decoded = decodeJWTComponents(compact(policyStatement(
            """{"oauth_client":{"grant_types":{"value":["authorization_code"],"subset_of":["authorization_code","refresh_token"]},"token_endpoint_auth_method":{"value":"private_key_jwt"}},"openid_relying_party":{"redirect_uris":{"value":["https://rp.example/cb"]}}}""",
        )))
        val policy = decoded.payload["metadata_policy"]!!.jsonObject
        val client = policy["oauth_client"]!!.jsonObject

        assertEquals(setOf("oauth_client", "openid_relying_party"), policy.keys)
        assertEquals(setOf("grant_types", "token_endpoint_auth_method"), client.keys)
        assertEquals(setOf("value", "subset_of"), client["grant_types"]!!.jsonObject.keys)
        assertEquals(JsonPrimitive("private_key_jwt"), client["token_endpoint_auth_method"]!!.jsonObject["value"])
    }

    @Test
    fun sameMetadataParameterNameInDifferentRolesIsAllowed() {
        val decoded = decodeJWTComponents(compact(policyStatement(
            """{"oauth_client":{"grant_types":{"value":["authorization_code"]}},"openid_relying_party":{"grant_types":{"value":["implicit"]}}}""",
        )))
        val policy = decoded.payload["metadata_policy"]!!.jsonObject

        assertEquals(
            JsonPrimitive("authorization_code"),
            policy["oauth_client"]!!.jsonObject["grant_types"]!!.jsonObject["value"]!!.jsonArray.single(),
        )
        assertEquals(
            JsonPrimitive("implicit"),
            policy["openid_relying_party"]!!.jsonObject["grant_types"]!!.jsonObject["value"]!!.jsonArray.single(),
        )
    }

    @Test
    fun distinctRootClaimsRetainOrdinaryNullValue() {
        val decoded = decodeJWTComponents(compact(statement(""""note":null,"aud":"https://audience.example"""")))

        assertEquals(JsonNull, decoded.payload["note"])
        assertEquals("https://audience.example", decoded.payload["aud"]?.jsonPrimitive?.content)
        assertEquals(setOf("iss", "sub", "iat", "exp", "jwks", "note", "aud"), decoded.payload.keys)
    }

    private fun assertDuplicateRejected(rawPayload: String) {
        val error = assertFailsWith<JwtDecodingException> {
            decodeJWTComponents(compact(rawPayload))
        }
        assertTrue(
            error.cause?.message.orEmpty().contains("duplicate", ignoreCase = true),
            "the failure must identify a repeated decoded member, not unrelated malformed JSON: ${error.cause}",
        )
    }

    private fun statement(rawMembers: String): String = "$base,$rawMembers}"

    private fun policyStatement(rawPolicy: String): String = statement("\"metadata_policy\":$rawPolicy")

    private fun compact(rawPayload: String, signature: String = "signature"): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return "${encoder.encode(header.encodeToByteArray())}.${encoder.encode(rawPayload.encodeToByteArray())}.$signature"
    }
}
