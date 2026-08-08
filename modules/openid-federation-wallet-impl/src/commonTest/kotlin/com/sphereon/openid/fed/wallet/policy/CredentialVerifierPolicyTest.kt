package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialVerifierPolicyTest {

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
}
