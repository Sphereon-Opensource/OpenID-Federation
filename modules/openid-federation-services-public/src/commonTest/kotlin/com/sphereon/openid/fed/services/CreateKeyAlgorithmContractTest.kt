package com.sphereon.openid.fed.services

import com.sphereon.core.api.json.jsonSerializer
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.openid.fed.openapi.models.CreateKey
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreateKeyAlgorithmContractTest {
    @Test
    fun rs256WireRequestMapsToRsaSha256WithoutChangingProviderOrAlias() {
        val model = jsonSerializer.decodeFromString<CreateKey>(
            """{"signatureAlgorithm":"RS256","kms":"configured-rsa-provider","kmsKeyRef":"rsa-alias"}""",
        )
        val args = CreateKeyArgs.fromModel(model)
        assertEquals(SignatureAlgorithm.RSA_SHA256, args.alg)
        assertEquals("configured-rsa-provider", args.providerId)
        assertEquals("rsa-alias", args.alias)
    }

    @Test
    fun existingEcWireAlgorithmsKeepTheirExactMappings() {
        val expected = mapOf(
            "ES256" to SignatureAlgorithm.ECDSA_SHA256,
            "ES384" to SignatureAlgorithm.ECDSA_SHA384,
            "ES512" to SignatureAlgorithm.ECDSA_SHA512,
        )
        expected.forEach { (wire, algorithm) ->
            val model = jsonSerializer.decodeFromString<CreateKey>("""{"signatureAlgorithm":"$wire"}""")
            assertEquals(algorithm, CreateKeyArgs.fromModel(model).alg)
        }
    }

    @Test
    fun omittedAlgorithmKeepsEs256Default() {
        assertEquals(
            SignatureAlgorithm.ECDSA_SHA256,
            CreateKeyArgs.fromModel(jsonSerializer.decodeFromString<CreateKey>("{}")).alg,
        )
    }

    @Test
    fun unsupportedWireAlgorithmIsRejectedInsteadOfFallingBack() {
        assertFailsWith<SerializationException> {
            jsonSerializer.decodeFromString<CreateKey>("""{"signatureAlgorithm":"not-an-algorithm"}""")
        }
    }
}
