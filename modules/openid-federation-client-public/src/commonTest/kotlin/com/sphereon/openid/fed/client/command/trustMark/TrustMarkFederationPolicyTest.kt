package com.sphereon.openid.fed.client.command.trustMark

import com.sphereon.openid.fed.openapi.models.BaseStatementJwks
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustMarkOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustMarkFederationPolicyTest {

    private fun ta(
        trustMarkIssuers: Map<String, List<String>>? = null,
        trustMarkOwners: Map<String, TrustMarkOwner>? = null
    ): EntityConfigurationStatement {
        return EntityConfigurationStatement(
            iss = "https://ta.example.com",
            sub = "https://ta.example.com",
            iat = 1.0,
            exp = 9999999999.0,
            jwks = BaseStatementJwks(propertyKeys = emptyList()),
            trustMarkIssuers = trustMarkIssuers,
            trustMarkOwners = trustMarkOwners
        )
    }

    @Test
    fun notRecognizedWhenTaHasNoTrustMarkPolicy() {
        val recognition = TrustMarkFederationPolicy.recognize(
            "https://other-fed.example/marks/foo",
            ta()
        )
        assertEquals(TrustMarkRecognition.NOT_RECOGNIZED, recognition)
    }

    @Test
    fun notRecognizedWhenTypeMissingFromIssuersAndOwners() {
        val recognition = TrustMarkFederationPolicy.recognize(
            "https://other-fed.example/marks/foreign",
            ta(
                trustMarkIssuers = mapOf(
                    "https://this-fed.example/marks/local" to listOf("https://issuer.example")
                )
            )
        )
        assertEquals(TrustMarkRecognition.NOT_RECOGNIZED, recognition)
    }

    @Test
    fun authorizedIssuersWhenTypeListedWithNonEmptyList() {
        val type = "https://fed.example/marks/rp"
        val recognition = TrustMarkFederationPolicy.recognize(
            type,
            ta(trustMarkIssuers = mapOf(type to listOf("https://issuer.example")))
        )
        assertEquals(TrustMarkRecognition.AUTHORIZED_ISSUERS, recognition)
        assertTrue(
            TrustMarkFederationPolicy.isIssuerAuthorized(
                recognition, type, "https://issuer.example",
                ta(trustMarkIssuers = mapOf(type to listOf("https://issuer.example")))
            )
        )
        assertFalse(
            TrustMarkFederationPolicy.isIssuerAuthorized(
                recognition, type, "https://evil.example",
                ta(trustMarkIssuers = mapOf(type to listOf("https://issuer.example")))
            )
        )
    }

    @Test
    fun anyoneMayIssueWhenIssuerListEmpty() {
        val type = "https://fed.example/marks/open"
        val taConfig = ta(trustMarkIssuers = mapOf(type to emptyList()))
        val recognition = TrustMarkFederationPolicy.recognize(type, taConfig)
        assertEquals(TrustMarkRecognition.ANYONE_MAY_ISSUE, recognition)
        assertTrue(
            TrustMarkFederationPolicy.isIssuerAuthorized(
                recognition, type, "https://any-issuer.example", taConfig
            )
        )
    }

    @Test
    fun ownerDelegationWhenTypeInOwners() {
        val type = "https://refeds.org/sirtfi"
        val taConfig = ta(
            trustMarkOwners = mapOf(
                type to TrustMarkOwner(sub = "https://refeds.org/sirtfi", jwks = emptyList())
            )
        )
        val recognition = TrustMarkFederationPolicy.recognize(type, taConfig)
        assertEquals(TrustMarkRecognition.OWNER_DELEGATION, recognition)
    }

    @Test
    fun ownersTakePrecedenceForRecognition() {
        val type = "https://fed.example/marks/both"
        val taConfig = ta(
            trustMarkIssuers = mapOf(type to listOf("https://issuer.example")),
            trustMarkOwners = mapOf(
                type to TrustMarkOwner(sub = "https://owner.example", jwks = emptyList())
            )
        )
        assertEquals(
            TrustMarkRecognition.OWNER_DELEGATION,
            TrustMarkFederationPolicy.recognize(type, taConfig)
        )
    }

    @Test
    fun crossFederationForeignMarkFilteredByNotRecognized() {
        // Federation A trusts mark type A; foreign mark type B from federation B is not listed
        val federationATa = ta(
            trustMarkIssuers = mapOf(
                "https://fed-a.example/marks/compliance" to listOf("https://fed-a.example/issuer")
            )
        )
        assertEquals(
            TrustMarkRecognition.AUTHORIZED_ISSUERS,
            TrustMarkFederationPolicy.recognize("https://fed-a.example/marks/compliance", federationATa)
        )
        assertEquals(
            TrustMarkRecognition.NOT_RECOGNIZED,
            TrustMarkFederationPolicy.recognize("https://fed-b.example/marks/other", federationATa)
        )
    }
}
