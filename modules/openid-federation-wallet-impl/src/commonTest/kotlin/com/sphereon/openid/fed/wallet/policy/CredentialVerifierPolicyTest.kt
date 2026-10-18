package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialVerifierPolicyTest {

    @Test
    fun malformedPresentFederationQueriesDoNotDisableTheConstraint() {
        for (bad in listOf(JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive("query"))))) {
            assertFalse(CredentialVerifierPolicy.validateDcqlQuery(JsonObject(emptyMap()),
                JsonObject(mapOf("dcql_queries" to bad))).valid)
        }
    }

    @Test
    fun availableFederatedMetadataNeverFallsBackToRequestKeys() {
        val client = JsonObject(mapOf("jwks" to JsonObject(mapOf("keys" to JsonArray(listOf(
            JsonObject(mapOf("kid" to JsonPrimitive("attacker"), "kty" to JsonPrimitive("EC")))))))))
        for (federated in listOf(JsonObject(emptyMap()), JsonObject(mapOf("jwks" to JsonNull)),
            JsonObject(mapOf("jwks" to JsonObject(mapOf("keys" to JsonArray(emptyList()))))))) {
            val resolved = CredentialVerifierPolicy.resolveJwks(federated, client)
            assertTrue(resolved.fromFederation)
            assertEquals(null, resolved.jwks)
        }
    }

    @Test
    fun prefersFederatedJwksOverClientMetadata() {
        val fed = JsonObject(
            mapOf(
                "jwks" to JsonObject(
                    mapOf(
                        "keys" to JsonArray(
                            listOf(JsonObject(mapOf("kid" to JsonPrimitive("fed-key"), "kty" to JsonPrimitive("EC"))))
                        )
                    )
                )
            )
        )
        val client = JsonObject(
            mapOf(
                "jwks" to JsonObject(
                    mapOf(
                        "keys" to JsonArray(
                            listOf(JsonObject(mapOf("kid" to JsonPrimitive("client-key"), "kty" to JsonPrimitive("EC"))))
                        )
                    )
                )
            )
        )

        val result = CredentialVerifierPolicy.resolveJwks(fed, client)
        assertTrue(result.fromFederation)
        assertEquals("fed-key", result.jwks!!["keys"]!!.jsonArray[0].jsonObject["kid"]!!.jsonPrimitive.content)
    }

    @Test
    fun fallsBackToClientMetadataWhenNoFederatedJwks() {
        val client = JsonObject(
            mapOf(
                "jwks" to JsonObject(
                    mapOf(
                        "keys" to JsonArray(
                            listOf(JsonObject(mapOf("kid" to JsonPrimitive("client-key"), "kty" to JsonPrimitive("EC"))))
                        )
                    )
                )
            )
        )
        val result = CredentialVerifierPolicy.resolveJwks(null, client)
        assertFalse(result.fromFederation)
        assertTrue(result.jwks != null)
    }

    @Test
    fun dcqlUnconstrainedWhenNoRegisteredQueries() {
        val result = CredentialVerifierPolicy.validateDcqlQuery(
            requestDcqlQuery = JsonObject(mapOf("credentials" to JsonArray(emptyList()))),
            federatedVerifierMetadata = JsonObject(emptyMap())
        )
        assertTrue(result.valid)
    }

    @Test
    fun dcqlMustMatchRegisteredQuery() {
        val registered = JsonObject(mapOf("id" to JsonPrimitive("q1")))
        val meta = JsonObject(
            mapOf("dcql_queries" to JsonArray(listOf(registered)))
        )
        val equal = CredentialVerifierPolicy.validateDcqlQuery(
            requestDcqlQuery = JsonObject(mapOf("id" to JsonPrimitive("q1"))),
            federatedVerifierMetadata = meta
        )
        assertTrue(equal.valid)
        assertEquals(DcqlRefinement.MatchKind.EQUAL, equal.matchKind)

        assertFalse(
            CredentialVerifierPolicy.validateDcqlQuery(
                requestDcqlQuery = JsonObject(mapOf("id" to JsonPrimitive("q2"))),
                federatedVerifierMetadata = meta
            ).valid
        )
        assertFalse(
            CredentialVerifierPolicy.validateDcqlQuery(
                requestDcqlQuery = null,
                federatedVerifierMetadata = meta
            ).valid
        )
    }

    @Test
    fun dcqlAcceptsClaimSubsetRefinement() {
        val registered = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("pid"),
                                "format" to JsonPrimitive("dc+sd-jwt"),
                                "claims" to JsonArray(
                                    listOf(
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("given_name"))))),
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("family_name"))))),
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("birthdate"))))),
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )
        val request = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("pid"),
                                "format" to JsonPrimitive("dc+sd-jwt"),
                                "claims" to JsonArray(
                                    listOf(
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("given_name"))))),
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )
        val meta = JsonObject(mapOf("dcql_queries" to JsonArray(listOf(registered))))
        val result = CredentialVerifierPolicy.validateDcqlQuery(request, meta)
        assertTrue(result.valid, result.reason)
        assertEquals(DcqlRefinement.MatchKind.REFINEMENT, result.matchKind)
    }

    @Test
    fun dcqlRejectsClaimExpansion() {
        val registered = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("pid"),
                                "format" to JsonPrimitive("dc+sd-jwt"),
                                "claims" to JsonArray(
                                    listOf(
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("given_name"))))),
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )
        val request = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("pid"),
                                "format" to JsonPrimitive("dc+sd-jwt"),
                                "claims" to JsonArray(
                                    listOf(
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("given_name"))))),
                                        JsonObject(mapOf("path" to JsonArray(listOf(JsonPrimitive("ssn"))))),
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )
        val meta = JsonObject(mapOf("dcql_queries" to JsonArray(listOf(registered))))
        assertFalse(CredentialVerifierPolicy.validateDcqlQuery(request, meta).valid)
    }

    @Test
    fun dcqlRejectsUnknownCredentialId() {
        val registered = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("pid"),
                                "format" to JsonPrimitive("dc+sd-jwt"),
                            )
                        )
                    )
                )
            )
        )
        val request = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("other"),
                                "format" to JsonPrimitive("dc+sd-jwt"),
                            )
                        )
                    )
                )
            )
        )
        val meta = JsonObject(mapOf("dcql_queries" to JsonArray(listOf(registered))))
        assertFalse(CredentialVerifierPolicy.validateDcqlQuery(request, meta).valid)
    }

    private fun dcql(claim: String): JsonObject = Json.parseToJsonElement(
        """{"credentials":[{"id":"pid","format":"dc+sd-jwt","claims":[{"path":["$claim"]}]}]}"""
    ).jsonObject

    private fun dcqlPolicy(operators: String): JsonObject =
        Json.parseToJsonElement("""{"openid_credential_verifier":{"dcql_queries":$operators}}""").jsonObject

    // Wallet Architectures 1.0 draft 05 section 7.1.1: a dcql_queries policy applies to the request's dcql_query
    // when the Credential Verifier metadata has no dcql_queries.
    @Test
    fun dcqlQueriesPolicyAppliesToTheRequestQueryWithoutVerifierQueries() {
        val policy = dcqlPolicy("""{"subset_of":[${dcql("given_name")}]}""")
        val noQueries = JsonObject(mapOf("response_uris" to JsonArray(listOf(JsonPrimitive("https://verifier.example/response")))))
        assertTrue(CredentialVerifierPolicy.validateDcqlQuery(dcql("given_name"), noQueries, policy).valid)
        val outside = CredentialVerifierPolicy.validateDcqlQuery(dcql("birthdate"), noQueries, policy)
        assertFalse(outside.valid, "a request query outside the subset_of policy must be refused")
    }

    @Test
    fun anEssentialDcqlQueriesPolicyRequiresARequestQuery() {
        val policy = dcqlPolicy("""{"essential":true}""")
        assertFalse(CredentialVerifierPolicy.validateDcqlQuery(null, JsonObject(emptyMap()), policy).valid)
        assertTrue(CredentialVerifierPolicy.validateDcqlQuery(dcql("given_name"), JsonObject(emptyMap()), policy).valid)
    }

    @Test
    fun withoutADcqlQueriesPolicyAnyRequestQueryIsAccepted() {
        val other = Json.parseToJsonElement("""{"openid_credential_verifier":{"response_uris":{"essential":true}}}""").jsonObject
        assertTrue(CredentialVerifierPolicy.validateDcqlQuery(dcql("birthdate"), JsonObject(emptyMap()), other).valid)
        assertTrue(CredentialVerifierPolicy.validateDcqlQuery(dcql("birthdate"), JsonObject(emptyMap()), null).valid)
    }
}
