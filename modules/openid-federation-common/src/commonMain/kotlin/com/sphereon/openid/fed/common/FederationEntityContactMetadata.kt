/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed model for the `federation_entity` metadata from an OpenID Federation entity configuration.
 *
 * Provides typed access to contact, organization, and display fields that
 * appear in the federation_entity metadata object.
 *
 * Supports language-tagged fields (e.g., `organization_name#nl`) via
 * the localized maps.
 */
@Serializable
data class FederationEntityContactMetadata(
    @SerialName("organization_name")
    val organizationName: String? = null,

    @SerialName("display_name")
    val displayName: String? = null,

    val contacts: List<String> = emptyList(),

    @SerialName("logo_uri")
    val logoUri: String? = null,

    @SerialName("homepage_uri")
    val homepageUri: String? = null,

    @SerialName("policy_uri")
    val policyUri: String? = null
) {
    companion object {
        /**
         * Parses localized variants from a raw federation_entity metadata map.
         *
         * Keys like `organization_name#nl`, `display_name#en` are parsed
         * into lang -> value maps.
         */
        fun parseLocalized(
            raw: Map<String, kotlinx.serialization.json.JsonElement>
        ): LocalizedFederationEntityMetadata {
            val orgNames = mutableMapOf<String, String>()
            val displayNames = mutableMapOf<String, String>()

            for ((key, value) in raw) {
                val strValue = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                when {
                    key.startsWith("organization_name#") -> {
                        val lang = key.removePrefix("organization_name#")
                        orgNames[lang] = strValue
                    }
                    key.startsWith("display_name#") -> {
                        val lang = key.removePrefix("display_name#")
                        displayNames[lang] = strValue
                    }
                }
            }

            return LocalizedFederationEntityMetadata(
                localizedOrganizationNames = orgNames,
                localizedDisplayNames = displayNames
            )
        }
    }
}

/**
 * Language-tagged variants of federation entity metadata fields.
 */
@Serializable
data class LocalizedFederationEntityMetadata(
    val localizedOrganizationNames: Map<String, String> = emptyMap(),
    val localizedDisplayNames: Map<String, String> = emptyMap()
)
