package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.json.jsonSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplaceEntityConfigurationComponentsSerializationTest {
    @Test
    fun argumentsRoundTripThroughTheSharedJsonSerializerWithTheirDeclaredFields() {
        val metadata = mapOf(
            "openid_provider" to jsonSerializer.parseToJsonElement(
                """{"issuer":"https://issuer.example","organization_name":"Sphereon","contacts":["https://ops.example","https://security.example"]}""",
            ).jsonObject,
        )
        val args = ReplaceEntityConfigurationComponentsArgs(
            accountId = "account-7",
            expectedEntityIdentifier = "https://entity.example",
            metadata = metadata,
            authorityHints = listOf("https://root-one.example", "https://root-two.example"),
        )

        val serialized = jsonSerializer.encodeToString(args)
        assertEquals(
            """{"accountId":"account-7","expectedEntityIdentifier":"https://entity.example","metadata":{"openid_provider":{"issuer":"https://issuer.example","organization_name":"Sphereon","contacts":["https://ops.example","https://security.example"]}},"authorityHints":["https://root-one.example","https://root-two.example"]}""",
            serialized,
        )
        assertEquals(args, jsonSerializer.decodeFromString<ReplaceEntityConfigurationComponentsArgs>(serialized))
    }

    @Test
    fun resultRoundTripThroughTheSharedJsonSerializerWithItsDeclaredFields() {
        val metadata = mapOf(
            "openid_provider" to jsonSerializer.parseToJsonElement(
                """{"issuer":"https://issuer.example","organization_name":"Sphereon","contacts":["https://ops.example","https://security.example"]}""",
            ).jsonObject,
        )
        val result = ReplacedEntityConfigurationComponents(
            accountId = "account-7",
            entityIdentifier = "https://entity.example",
            metadata = metadata,
            authorityHints = listOf("https://root-one.example", "https://root-two.example"),
        )

        val serialized = jsonSerializer.encodeToString(result)
        assertEquals(
            """{"accountId":"account-7","entityIdentifier":"https://entity.example","metadata":{"openid_provider":{"issuer":"https://issuer.example","organization_name":"Sphereon","contacts":["https://ops.example","https://security.example"]}},"authorityHints":["https://root-one.example","https://root-two.example"]}""",
            serialized,
        )
        assertEquals(result, jsonSerializer.decodeFromString<ReplacedEntityConfigurationComponents>(serialized))
    }
}
