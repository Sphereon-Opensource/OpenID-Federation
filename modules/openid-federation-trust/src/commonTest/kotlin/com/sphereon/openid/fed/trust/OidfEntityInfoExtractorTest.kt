/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.trust

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationArgs
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.BaseStatementJwks
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.trust.core.model.ContactType
import com.sphereon.trust.core.model.EntityDiscoveryOptions
import com.sphereon.trust.core.model.EntityRole
import com.sphereon.trust.core.model.TrustChainNodeRole
import com.sphereon.trust.core.model.TrustContext
import com.sphereon.trust.core.model.toContact
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OidfEntityInfoExtractorTest {

    /**
     * Tests extraction from a federation_entity metadata block modeled after
     * the eduID entity configuration at https://issuer.dev.eduid.nl/eduid/.well-known/openid-federation
     */
    @Test
    fun extractsContactInfoFromFederationEntityMetadata() = runTest {
        val entityId = "https://issuer.dev.eduid.nl/eduid"
        val extractor = createExtractor(entityId, createEduIdMetadata())

        val entities = extractor.extractEntityInfo(
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = mapOf("entityIdentifier" to entityId)
            ),
            validationPath = listOf(entityId),
            options = EntityDiscoveryOptions(enabled = true, maxDepth = 1)
        )

        assertEquals(1, entities.size)
        val entity = entities.first()

        // Entity identifier
        assertEquals(entityId, entity.entityIdentifier)
        assertEquals(TrustChainNodeRole.LEAF, entity.chainPosition.role)
        assertEquals(0, entity.chainPosition.depth)

        // Organization name extracted from federation_entity
        assertEquals("eduID", entity.organizationName)
        println("  Organization: ${entity.organizationName}")

        // Names include default + localized variants
        assertTrue(entity.names.isNotEmpty(), "Should have names")
        println("  Names: ${entity.names.map { "${it.lang}=${it.value}" }}")
        assertTrue(entity.names.any { it.value == "eduID" })
        assertTrue(entity.names.any { it.lang == "nl" && it.value == "eduID" })
        assertTrue(entity.names.any { it.lang == "en" && it.value == "eduID" })

        // Contacts
        assertTrue(entity.contacts.isNotEmpty(), "Should have contacts")
        val emailContact = entity.contacts.first { it.type == ContactType.EMAIL }
        assertEquals("info@eduwallet.nl", emailContact.value)
        println("  Contacts: ${entity.contacts.map { "${it.type}: ${it.value}" }}")

        // Logo
        assertTrue(entity.logos.isNotEmpty(), "Should have logos")
        assertEquals("https://static.dev.eduid.nl/images/eduid_credential_logo.png", entity.logos.first().uri)
        println("  Logo: ${entity.logos.first().uri}")

        // Roles - has both federation_entity and openid_credential_issuer
        assertTrue(entity.roles.contains(EntityRole.GENERAL), "Should have GENERAL role (federation_entity)")
        assertTrue(entity.roles.contains(EntityRole.ISSUER), "Should have ISSUER role (openid_credential_issuer)")
        println("  Roles: ${entity.roles.map { it.value }}")

        // Source metadata preserved
        assertTrue(entity.sourceMetadata.isNotEmpty(), "Should preserve source metadata")
        assertTrue(entity.sourceMetadata.containsKey("federation_entity"))
        assertTrue(entity.sourceMetadata.containsKey("openid_credential_issuer"))
        println("  Source metadata keys: ${entity.sourceMetadata.keys}")
    }

    @Test
    fun extractsIssuerDisplayInfo() = runTest {
        val entityId = "https://issuer.dev.eduid.nl/eduid"
        val extractor = createExtractor(entityId, createEduIdMetadata())

        val entities = extractor.extractEntityInfo(
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = mapOf("entityIdentifier" to entityId)
            ),
            validationPath = listOf(entityId),
            options = EntityDiscoveryOptions(enabled = true, maxDepth = 1)
        )

        val entity = entities.first()

        // Display entries: default + nl + en from issuer metadata
        assertTrue(entity.display.isNotEmpty(), "Should have display entries")
        println("  Display entries:")
        entity.display.forEach { d ->
            println("    locale=${d.locale ?: "default"}, name=${d.name}, desc=${d.description}, logo=${d.logo?.uri}, derived=${d.derived}")
        }

        // Default entry should exist with name/desc/logo populated, marked as derived
        val defaultDisplay = entity.display.firstOrNull { it.locale == null }
        assertNotNull(defaultDisplay, "Should have a default (locale=null) display entry")
        assertEquals("eduID", defaultDisplay.name)
        assertNotNull(defaultDisplay.description, "Default should have description from first available locale")
        assertNotNull(defaultDisplay.logo, "Default should have logo")
        assertTrue(defaultDisplay.derived, "Default should be marked as derived (synthesized from first locale)")
        println("  Default display: name=${defaultDisplay.name}, desc=${defaultDisplay.description}, logo=${defaultDisplay.logo?.uri}, derived=${defaultDisplay.derived}")

        // Locale-specific entries should NOT be derived
        val nlDisplay2 = entity.display.first { it.locale == "nl" }
        assertFalse(nlDisplay2.derived, "Locale-specific entries should not be derived")

        // Locale-specific entries: exactly one per locale, no duplicates
        val nlDisplayEntries = entity.display.filter { it.locale == "nl" }
        assertEquals(1, nlDisplayEntries.size, "Should have exactly one Dutch display entry")
        val nlDisplay = nlDisplayEntries.first()
        assertEquals("eduID", nlDisplay.name)
        assertTrue(nlDisplay.description!!.contains("onderwijs"), "Dutch description should mention onderwijs")
        assertNotNull(nlDisplay.logo, "Dutch issuer display should have logo")
    }

    @Test
    fun convertsToContact() = runTest {
        val entityId = "https://issuer.dev.eduid.nl/eduid"
        val extractor = createExtractor(entityId, createEduIdMetadata())

        val entities = extractor.extractEntityInfo(
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = mapOf("entityIdentifier" to entityId)
            ),
            validationPath = listOf(entityId),
            options = EntityDiscoveryOptions(enabled = true, maxDepth = 1)
        )

        val contact = entities.first().toContact()

        println("\n--- Discovered Contact ---")
        println("  Display name: ${contact.displayName}")
        println("  Organization: ${contact.organizationName}")
        println("  Emails: ${contact.emails}")
        println("  Phones: ${contact.phones}")
        println("  URLs: ${contact.urls}")
        println("  Logo: ${contact.logoUri}")

        assertEquals("eduID", contact.organizationName)
        assertTrue(contact.emails.contains("info@eduwallet.nl"))
        assertEquals("https://static.dev.eduid.nl/images/eduid_credential_logo.png", contact.logoUri)
    }

    @Test
    fun followsAuthorityHintsForChainTraversal() = runTest {
        val leafId = "https://issuer.dev.eduid.nl/eduid"
        val intermediaryId = "https://intermediary.poc2.dev.oidf.lab.surfconext.nl"

        val leafMetadata = createEduIdMetadata(authorityHints = listOf(intermediaryId))
        val intermediaryMetadata = createMinimalFedEntityMetadata(
            orgName = "SURF Intermediary",
            displayName = "SURF Federation Intermediary",
            contacts = listOf("admin@surf.nl", "https://www.surf.nl")
        )

        val configs = mapOf(
            leafId to leafMetadata,
            intermediaryId to intermediaryMetadata
        )

        val getCmd = createGetEntityConfigCmd(configs)
        val extractor = OidfEntityInfoExtractor(getCmd)

        val entities = extractor.extractEntityInfo(
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = mapOf("entityIdentifier" to leafId)
            ),
            validationPath = listOf(leafId),
            options = EntityDiscoveryOptions(enabled = true, maxDepth = 0) // unlimited
        )

        assertEquals(2, entities.size)

        println("\n--- Trust Chain Entity Discovery ---")
        entities.forEach { e ->
            val contact = e.toContact()
            println("  [depth=${e.chainPosition.depth}, role=${e.chainPosition.role}]")
            println("    Entity: ${e.entityIdentifier}")
            println("    Organization: ${contact.organizationName}")
            println("    Emails: ${contact.emails}")
            println("    URLs: ${contact.urls}")
            println("    Roles: ${e.roles.map { it.value }}")
        }

        // Leaf entity
        val leaf = entities[0]
        assertEquals(leafId, leaf.entityIdentifier)
        assertEquals(TrustChainNodeRole.LEAF, leaf.chainPosition.role)
        assertEquals("eduID", leaf.organizationName)

        // Intermediary
        val intermediary = entities[1]
        assertEquals(intermediaryId, intermediary.entityIdentifier)
        assertEquals(TrustChainNodeRole.INTERMEDIATE, intermediary.chainPosition.role)
        assertEquals("SURF Intermediary", intermediary.organizationName)
        assertTrue(intermediary.contacts.any { it.value == "admin@surf.nl" })
    }

    @Test
    fun respectsMaxDepth() = runTest {
        val leafId = "https://issuer.dev.eduid.nl/eduid"
        val intermediaryId = "https://intermediary.example.com"

        val leafMetadata = createEduIdMetadata(authorityHints = listOf(intermediaryId))
        val configs = mapOf(
            leafId to leafMetadata,
            intermediaryId to createMinimalFedEntityMetadata("Intermediary Org")
        )

        val getCmd = createGetEntityConfigCmd(configs)
        val extractor = OidfEntityInfoExtractor(getCmd)

        // maxDepth = 1 => only leaf
        val entities = extractor.extractEntityInfo(
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = mapOf("entityIdentifier" to leafId)
            ),
            validationPath = listOf(leafId),
            options = EntityDiscoveryOptions(enabled = true, maxDepth = 1)
        )

        assertEquals(1, entities.size, "maxDepth=1 should only return leaf entity")
        assertEquals(leafId, entities.first().entityIdentifier)
    }

    // -- Test Data --

    /**
     * Creates metadata matching the eduID entity configuration example.
     */
    private fun createEduIdMetadata(
        authorityHints: List<String>? = null
    ): EntityConfigWithMetadata {
        val federationEntity = JsonObject(mapOf(
            "display_name" to JsonPrimitive("eduID"),
            "logo_uri" to JsonPrimitive("https://static.dev.eduid.nl/images/eduid_credential_logo.png"),
            "organization_name" to JsonPrimitive("eduID"),
            "contacts" to JsonArray(listOf(JsonPrimitive("info@eduwallet.nl"))),
            "display_name#nl" to JsonPrimitive("eduID"),
            "organization_name#nl" to JsonPrimitive("eduID"),
            "display_name#en" to JsonPrimitive("eduID"),
            "organization_name#en" to JsonPrimitive("eduID")
        ))

        val issuerDisplay = JsonArray(listOf(
            JsonObject(mapOf(
                "locale" to JsonPrimitive("nl"),
                "name" to JsonPrimitive("eduID"),
                "description" to JsonPrimitive("eduID is een relatie met een hoger onderwijs- of onderzoeksinstelling"),
                "text_color" to JsonPrimitive("#ffffff"),
                "logo" to JsonObject(mapOf(
                    "uri" to JsonPrimitive("https://static.dev.eduid.nl/images/eduid_credential_logo.png"),
                    "alt_text" to JsonPrimitive("EduID")
                )),
                "background_color" to JsonPrimitive("#4779aa")
            )),
            JsonObject(mapOf(
                "locale" to JsonPrimitive("en"),
                "name" to JsonPrimitive("eduID"),
                "description" to JsonPrimitive("eduID represents your affiliation with an institute of higher education or research"),
                "logo" to JsonObject(mapOf(
                    "uri" to JsonPrimitive("https://static.dev.eduid.nl/images/eduid_credential_logo.png"),
                    "alt_text" to JsonPrimitive("EduID")
                ))
            ))
        ))

        val openidCredentialIssuer = JsonObject(mapOf(
            "credential_issuer" to JsonPrimitive("https://issuer.dev.eduid.nl/eduid"),
            "credential_endpoint" to JsonPrimitive("https://issuer.dev.eduid.nl/eduid/credentials"),
            "display" to issuerDisplay
        ))

        val metadata = JsonObject(mapOf(
            "federation_entity" to federationEntity,
            "openid_credential_issuer" to openidCredentialIssuer
        ))

        return EntityConfigWithMetadata(metadata, authorityHints)
    }

    private fun createMinimalFedEntityMetadata(
        orgName: String,
        displayName: String? = null,
        contacts: List<String> = emptyList()
    ): EntityConfigWithMetadata {
        val entries = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "organization_name" to JsonPrimitive(orgName)
        )
        if (displayName != null) entries["display_name"] = JsonPrimitive(displayName)
        if (contacts.isNotEmpty()) entries["contacts"] = JsonArray(contacts.map { JsonPrimitive(it) })

        val metadata = JsonObject(mapOf(
            "federation_entity" to JsonObject(entries)
        ))
        return EntityConfigWithMetadata(metadata, null)
    }

    private data class EntityConfigWithMetadata(
        val metadata: JsonObject,
        val authorityHints: List<String>?
    )

    // -- Helpers --

    private fun createExtractor(
        entityId: String,
        config: EntityConfigWithMetadata
    ): OidfEntityInfoExtractor {
        val getCmd = createGetEntityConfigCmd(mapOf(entityId to config))
        return OidfEntityInfoExtractor(getCmd)
    }

    private fun createGetEntityConfigCmd(
        configs: Map<String, EntityConfigWithMetadata>
    ): GetEntityConfigurationCommand {
        return object : GetEntityConfigurationCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun getEntityConfiguration(
                entityIdentifier: String
            ): IdkResult<EntityConfigurationStatement, FederationError> {
                val config = configs[entityIdentifier]
                    ?: return Ok(EntityConfigurationStatement(
                        iss = entityIdentifier,
                        sub = entityIdentifier,
                        exp = 9999999999.0,
                        iat = 1000000000.0,
                        jwks = BaseStatementJwks(propertyKeys = listOf(
                            Jwk(kty = "EC", kid = "key-1", crv = "P-256",
                                x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
                                y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")
                        ))
                    ))

                return Ok(EntityConfigurationStatement(
                    iss = entityIdentifier,
                    sub = entityIdentifier,
                    exp = 9999999999.0,
                    iat = 1000000000.0,
                    jwks = BaseStatementJwks(propertyKeys = listOf(
                        Jwk(kty = "EC", kid = "key-1", crv = "P-256",
                            x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
                            y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")
                    )),
                    metadata = config.metadata,
                    authorityHints = config.authorityHints
                ))
            }

            override suspend fun execute(
                args: GetEntityConfigurationArgs
            ): IdkResult<EntityConfigurationStatement, FederationError> =
                getEntityConfiguration(args.entityIdentifier)

            override suspend fun supports(args: Any): Boolean = true
            override val id: String get() = GetEntityConfigurationCommand.COMMAND_ID
        }
    }
}
