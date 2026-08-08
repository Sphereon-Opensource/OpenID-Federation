/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.trust

import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.common.FederationEntityContactMetadata
import com.sphereon.trust.core.EntityInfoExtractor
import com.sphereon.trust.core.model.DiscoveredEntityInfo
import com.sphereon.trust.core.model.EntityContact
import com.sphereon.trust.core.model.EntityDiscoveryOptions
import com.sphereon.trust.core.model.EntityDisplay
import com.sphereon.trust.core.model.EntityLogo
import com.sphereon.trust.core.model.EntityRole
import com.sphereon.trust.core.model.EntityRoleMapping
import com.sphereon.trust.core.model.LocalizedString
import com.sphereon.trust.core.model.LocalizedUri
import com.sphereon.trust.core.model.TrustAnchorType
import com.sphereon.trust.core.model.TrustChainNodeRole
import com.sphereon.trust.core.model.TrustChainPosition
import com.sphereon.trust.core.model.TrustContext
import com.sphereon.trust.core.model.inferContactType
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts entity information from OpenID Federation entity configurations.
 *
 * Parses the `federation_entity` metadata for contact/org info and
 * `openid_credential_issuer` metadata for issuer display info.
 * Follows authority_hints for trust chain traversal up to maxDepth.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(scope = SessionScope::class, binding = binding<EntityInfoExtractor>())
class OidfEntityInfoExtractor(
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand
) : EntityInfoExtractor {

    override val supportedContextTypes: Set<String> = setOf(TrustContext.TYPE_OPENID_FEDERATION)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extractEntityInfo(
        context: TrustContext,
        validationPath: List<String>,
        options: EntityDiscoveryOptions
    ): List<DiscoveredEntityInfo> {
        val effectiveDepth = if (options.maxDepth == 0) Int.MAX_VALUE else options.maxDepth
        val results = mutableListOf<DiscoveredEntityInfo>()

        // validationPath contains entity identifiers (leaf first, anchor last)
        // For OIDFED, the first item is usually the entity identifier
        val entityId = validationPath.firstOrNull()
            ?: context.parameters["entityIdentifier"]
            ?: return emptyList()

        // Fetch and extract from the leaf entity — this is the trust anchor
        // (the entity that passed trust chain verification)
        val leafEntity = fetchAndExtract(entityId, 0, TrustChainNodeRole.LEAF, options)
        if (leafEntity != null) {
            results.add(leafEntity.copy(trustAnchor = true))
        }

        // Follow authority_hints for parent entities if depth allows
        if (effectiveDepth > 1) {
            val configResult = getEntityConfigurationCommand.getEntityConfiguration(entityId)
            if (configResult.isOk) {
                val authorityHints = configResult.value.authorityHints ?: emptyList()
                for ((index, hint) in authorityHints.withIndex()) {
                    if (results.size >= effectiveDepth) break
                    val depth = index + 1
                    val nodeRole = TrustChainNodeRole.INTERMEDIATE
                    val parentEntity = fetchAndExtract(hint, depth, nodeRole, options)
                    if (parentEntity != null) {
                        results.add(parentEntity)
                    }
                }
            }
        }

        return results
    }

    private suspend fun fetchAndExtract(
        entityId: String,
        depth: Int,
        nodeRole: TrustChainNodeRole,
        options: EntityDiscoveryOptions
    ): DiscoveredEntityInfo? {
        val configResult = getEntityConfigurationCommand.getEntityConfiguration(entityId)
        if (configResult.isErr) return null

        val config = configResult.value
        val metadata = config.metadata ?: return DiscoveredEntityInfo(
            entityIdentifier = entityId,
            sourceType = TrustAnchorType.OPENID_FEDERATION,
            chainPosition = TrustChainPosition(depth = depth, role = nodeRole)
        )

        // Parse federation_entity metadata
        val fedEntity = metadata["federation_entity"]?.jsonObject
        val fedContactMeta = fedEntity?.let {
            try {
                json.decodeFromJsonElement(FederationEntityContactMetadata.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        val localizedMeta = fedEntity?.let {
            FederationEntityContactMetadata.parseLocalized(it)
        }

        // Build names from organization_name + display_name (with localized variants)
        val names = buildList {
            fedContactMeta?.organizationName?.let { add(LocalizedString(lang = "und", value = it)) }
            localizedMeta?.localizedOrganizationNames?.forEach { (lang, value) ->
                add(LocalizedString(lang = lang, value = value))
            }
            // display_name#lang variants are localized name aliases, not display entries
            localizedMeta?.localizedDisplayNames?.forEach { (lang, value) ->
                if (none { it.lang == lang && it.value == value }) {
                    add(LocalizedString(lang = lang, value = value))
                }
            }
        }

        // Build display entries from all relevant metadata types
        val display = buildList {
            // Collect locale-specific entries from metadata types (issuer, verifier, etc.)
            val issuerMeta = metadata["openid_credential_issuer"]?.jsonObject
            if (issuerMeta != null && (options.roles.isEmpty() || options.roles.any { it == EntityRole.ISSUER })) {
                issuerMeta["display"]?.jsonArray?.forEach { displayEl ->
                    try {
                        val obj = displayEl.jsonObject
                        add(
                            EntityDisplay(
                                name = obj["name"]?.jsonPrimitive?.contentOrNull,
                                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                                locale = obj["locale"]?.jsonPrimitive?.contentOrNull,
                                logo = obj["logo"]?.jsonObject?.let { logoObj ->
                                    EntityLogo(
                                        uri = logoObj["uri"]?.jsonPrimitive?.contentOrNull ?: return@let null,
                                        altText = logoObj["alt_text"]?.jsonPrimitive?.contentOrNull
                                    )
                                }
                            )
                        )
                    } catch (_: Exception) {
                        // skip malformed display entries
                    }
                }
            }

            // Ensure a default (locale=null) entry exists.
            // If none of the collected entries is locale-less, synthesize one.
            val hasDefault = any { it.locale == null }
            if (!hasDefault) {
                // Use the first available locale entry as a base for defaults,
                // enriched with federation_entity-level name/logo.
                // Marked as derived so consumers know this is synthesized.
                val firstLocale = firstOrNull()
                val isDerived = firstLocale != null
                add(
                    0,
                    EntityDisplay(
                        name = fedContactMeta?.displayName ?: firstLocale?.name,
                        description = firstLocale?.description,
                        locale = null,
                        logo = fedContactMeta?.logoUri?.let { EntityLogo(uri = it) } ?: firstLocale?.logo,
                        derived = isDerived
                    )
                )
            }
        }

        // Contacts
        val contacts = fedContactMeta?.contacts?.map { contact ->
            EntityContact(type = inferContactType(contact), value = contact)
        } ?: emptyList()

        // Logos
        val logos = buildList {
            fedContactMeta?.logoUri?.let { add(EntityLogo(uri = it)) }
        }

        // Information URIs
        val informationUris = buildList {
            fedContactMeta?.homepageUri?.let { add(LocalizedUri(lang = "und", uri = it)) }
        }

        // Detect roles from metadata keys
        val discoveredRoles = buildList {
            if (metadata.containsKey("federation_entity")) add(EntityRole.GENERAL)
            if (metadata.containsKey("openid_credential_issuer")) add(EntityRole.ISSUER)
            if (metadata.containsKey("openid_relying_party")) add(EntityRole.VERIFIER)
            if (metadata.containsKey("openid_wallet")) add(EntityRole.WALLET)
        }.let { allRoles ->
            if (options.roles.isEmpty()) allRoles
            else allRoles.filter { role ->
                role in options.roles || options.roles.any { requested ->
                    EntityRoleMapping.toOidfedMetadataKeys(requested).any { key ->
                        metadata.containsKey(key)
                    }
                }
            }
        }

        return DiscoveredEntityInfo(
            entityIdentifier = entityId,
            sourceType = TrustAnchorType.OPENID_FEDERATION,
            chainPosition = TrustChainPosition(depth = depth, role = nodeRole),
            names = names,
            display = display,
            contacts = contacts,
            logos = logos,
            informationUris = informationUris,
            organizationName = fedContactMeta?.organizationName,
            roles = discoveredRoles,
            sourceMetadata = metadata
        )
    }
}
