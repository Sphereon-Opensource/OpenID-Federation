package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.json.jsonSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SignPreparedEntityConfigurationSerializationTest {
    @Test
    fun rawStatementExtensionsMetadataHintsAndNumericDatesSurviveTransport() {
        val statement = jsonSerializer.parseToJsonElement(
            """{"iss":"https://entity.example/case/%2F","sub":"https://entity.example/case/%2F","iat":1760000000.5,"exp":1760003600,"authority_hints":["https://ta-one.example","https://ta-two.example"],"metadata":{"federation_entity":{"organization_name":"Example","custom_evidence":{"enabled":true,"levels":[1,2]}},"oauth_authorization_server":{"issuer":"https://entity.example/case/%2F","custom_role_extension":{"nullable":null,"labels":["one","two"]}}},"noncritical_extension":{"nested":["alpha",{"flag":false}],"nullable":null}}""",
        ).jsonObject
        val args = SignPreparedEntityConfigurationArgs(
            accountId = "account-7",
            selectedKeyId = "123e4567-e89b-12d3-a456-426614174000",
            statement = statement,
            expectedSelectionRevision = 17L,
        )

        val transmitted = jsonSerializer.encodeToString(args)
        val envelope = jsonSerializer.parseToJsonElement(transmitted).jsonObject
        val received = jsonSerializer.decodeFromString<SignPreparedEntityConfigurationArgs>(transmitted)

        assertEquals(setOf("accountId", "selectedKeyId", "statement", "expectedSelectionRevision"), envelope.keys)
        assertEquals("account-7", received.accountId)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", received.selectedKeyId)
        assertEquals(17L, received.expectedSelectionRevision)
        assertEquals(
            statement.filterKeys { it != "iat" && it != "exp" },
            received.statement.filterKeys { it != "iat" && it != "exp" },
        )
        assertEquals(
            listOf("https://ta-one.example", "https://ta-two.example"),
            received.statement.getValue("authority_hints").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "Example",
            received.statement.getValue("metadata").jsonObject
                .getValue("federation_entity").jsonObject.getValue("organization_name").jsonPrimitive.content,
        )
        assertEquals(
            statement.getValue("noncritical_extension"),
            received.statement.getValue("noncritical_extension"),
        )
        val roleExtension = received.statement.getValue("metadata").jsonObject
            .getValue("oauth_authorization_server").jsonObject.getValue("custom_role_extension").jsonObject
        assertEquals(listOf("one", "two"), roleExtension.getValue("labels").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, roleExtension.getValue("nullable"))
        val receivedIat = received.statement.getValue("iat").jsonPrimitive
        val receivedExp = received.statement.getValue("exp").jsonPrimitive
        assertFalse(receivedIat.isString)
        assertFalse(receivedExp.isString)
        assertEquals(1760000000.5, assertNotNull(receivedIat.doubleOrNull))
        assertEquals(1760003600.0, assertNotNull(receivedExp.doubleOrNull))
    }

    @Test
    fun rawJwkKeyOperationsAndCertificateChainSurviveTransportWithoutProjectionToDto() {
        val statement = jsonSerializer.parseToJsonElement(
            """{"iss":"https://entity.example","sub":"https://entity.example","iat":1760000000,"exp":1760003600,"jwks":{"keys":[{"kty":"EC","crv":"P-256","kid":"public-key-1","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0","key_ops":["verify"],"x5c":["MIIB-example-certificate"]}]},"metadata":{"federation_entity":{"organization_name":"Example"}}}""",
        ).jsonObject
        val args = SignPreparedEntityConfigurationArgs(
            accountId = "account-keys",
            selectedKeyId = "223e4567-e89b-12d3-a456-426614174000",
            statement = statement,
            expectedSelectionRevision = 23L,
        )

        val transmitted = jsonSerializer.encodeToString(args)
        val received = jsonSerializer.decodeFromString<SignPreparedEntityConfigurationArgs>(transmitted)
        val key = received.statement.getValue("jwks").jsonObject.getValue("keys").jsonArray.single().jsonObject

        assertEquals("account-keys", received.accountId)
        assertEquals("223e4567-e89b-12d3-a456-426614174000", received.selectedKeyId)
        assertEquals(23L, received.expectedSelectionRevision)
        assertEquals(statement, received.statement)
        assertEquals(listOf("verify"), key.getValue("key_ops").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("MIIB-example-certificate"), key.getValue("x5c").jsonArray.map { it.jsonPrimitive.content })
    }
}
