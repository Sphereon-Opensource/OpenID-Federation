package com.sphereon.openid.fed.client.command.trustChain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.SerializationException

class TrustChainResolutionDiagnosticTest {
    @Test
    fun rendersOnlyFixedSafeTokens() {
        val secret = "https://private.example/entity eyJhbGciOiJFUzI1NiJ9.secret.signature"
        val message = TrustChainResolutionDiagnostic.message(
            TrustChainDiagnosticStage.SUBORDINATE_FETCH,
            TrustChainDiagnosticReason.FETCH_OR_PARSE_FAILED,
            IllegalStateException(secret),
        )

        assertEquals(
            "trust_chain_resolution stage=SUBORDINATE_FETCH reason=FETCH_OR_PARSE_FAILED failure=OTHER",
            message,
        )
        assertFalse(message.contains(secret))
        assertFalse(message.contains("private.example"))
        assertFalse(message.contains("eyJ"))
    }

    @Test
    fun identifiesSignatureRejectionAsCryptographic() {
        assertEquals(
            "trust_chain_resolution stage=SUBORDINATE_STATEMENT reason=SIGNATURE_INVALID failure=CRYPTO",
            TrustChainResolutionDiagnostic.message(
                TrustChainDiagnosticStage.SUBORDINATE_STATEMENT,
                TrustChainDiagnosticReason.SIGNATURE_INVALID,
            ),
        )
    }

    @Test
    fun classifiesKnownParseFailuresWithoutTheirMessage() {
        val message = TrustChainResolutionDiagnostic.message(
            TrustChainDiagnosticStage.ENTITY_CONFIGURATION,
            TrustChainDiagnosticReason.JWKS_INVALID,
            SerializationException("private JSON fragment"),
        )

        assertEquals(
            "trust_chain_resolution stage=ENTITY_CONFIGURATION reason=JWKS_INVALID failure=PARSE",
            message,
        )
        assertFalse(message.contains("private JSON fragment"))
    }
}
