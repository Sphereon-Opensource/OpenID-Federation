package com.sphereon.openid.fed.wallet.policy

import com.sphereon.core.api.http.util.validateHttpsUrlSyntax
import com.sphereon.crypto.core.jose.hasWellFormedPublicJwkMaterial
import com.sphereon.openid.fed.core.isValidEntityIdentifier
import kotlinx.serialization.json.*

/** Published Wallet Architectures 1.0 draft 05 participant metadata checks. Unknown extensions remain ignored. */
object WalletProfileValidator {
    private fun check(name: String, passed: Boolean, detail: String? = null) =
        MetadataValidationCheck(name, passed, detail, MetadataValidationCheck.PROFILE_WALLET)

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun https(value: JsonElement?): Boolean = text(value)?.let { validateHttpsUrlSyntax(it, allowQuery = true).isOk } == true
    private fun strings(value: JsonElement?): Boolean = value is JsonArray && value.isNotEmpty() && value.all { !text(it).isNullOrBlank() }
    private fun coseAlgorithms(value: JsonElement?): Boolean = value is JsonArray && value.isNotEmpty() && value.all {
        val primitive = it as? JsonPrimitive
        primitive != null && if (primitive.isString) primitive.content.isNotBlank() else primitive.longOrNull != null
    }
    private fun uriList(value: JsonElement?): Boolean = value is JsonArray && value.isNotEmpty() && value.all(::https)
    private fun publicKeys(value: JsonElement?, requireVerifierKids: Boolean): Boolean {
        val keys = (value as? JsonObject)?.get("keys") as? JsonArray ?: return false
        if (keys.any { it !is JsonObject || !hasWellFormedPublicJwkMaterial(it) }) return false
        if (!requireVerifierKids) return keys.all { "kid" !in (it as JsonObject) || text(it["kid"]) != null }
        val kids = keys.map { text((it as JsonObject)["kid"]) }
        return keys.isNotEmpty() && kids.none { it.isNullOrEmpty() } && kids.distinct().size == kids.size
    }

    fun validate(metadata: JsonObject, entityIdentifier: String, entityType: String? = null): List<MetadataValidationCheck> = buildList {
        add(check("entity_identifier", isValidEntityIdentifier(entityIdentifier), "Entity Identifier must be an exact HTTPS URL"))
        val federation = metadata[WalletEntityTypes.FEDERATION_ENTITY] as? JsonObject
        val organization = text(federation?.get("organization_name"))
        val display = text(federation?.get("display_name"))
        // Informational names are recommended, never a protocol admission requirement.
        add(check("federation_entity.organization_name", true, when {
            !organization.isNullOrBlank() -> null
            !display.isNullOrBlank() -> "Using display_name; organization_name is recommended"
            else -> "organization_name is recommended and is absent"
        }))
        if (entityType == null) return@buildList
        val role = metadata[entityType] as? JsonObject
        add(check("${entityType}_present", role != null, "Metadata for $entityType must be an object"))
        if (role == null) return@buildList
        add(check("$entityType.non_null_parameters", role.values.none { it == JsonNull }))
        val definesInlineProtocolKeys = entityType == WalletEntityTypes.OPENID_CREDENTIAL_ISSUER ||
            entityType == WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER
        if (definesInlineProtocolKeys && "jwks" in role) add(check(if (entityType == WalletEntityTypes.OPENID_CREDENTIAL_ISSUER)
            "credential_issuer_signing_keys" else "$entityType.jwks", publicKeys(role["jwks"],
                requireVerifierKids = entityType == WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER),
            "Present jwks must contain public key material; verifier keys require unique kid values"))
        when (entityType) {
            WalletEntityTypes.OPENID_CREDENTIAL_ISSUER -> {
                if ("jwks" !in role) add(check("credential_issuer_signing_keys", true, "Optional issuer jwks is absent; external profile keys are not substituted"))
                add(check("credential_issuer_matches_entity_id", text(role["credential_issuer"]) == entityIdentifier))
                add(check("credential_endpoint", https(role["credential_endpoint"])))
                for (field in listOf("nonce_endpoint", "deferred_credential_endpoint", "notification_endpoint"))
                    if (field in role) add(check(field, https(role[field])))
                if ("authorization_servers" in role) {
                    val servers = role["authorization_servers"]
                    add(check("authorization_servers", servers is JsonArray && servers.isNotEmpty() && servers.all { text(it)?.let(::isValidEntityIdentifier) == true }))
                }
                val configurations = role["credential_configurations_supported"] as? JsonObject
                add(check("credential_configurations_supported", configurations != null && configurations.isNotEmpty() &&
                    configurations.all { (id, configuration) -> id.isNotBlank() && configuration is JsonObject && !text(configuration["format"]).isNullOrBlank() }))
                if ("display" in role) add(check("display", role["display"] is JsonArray && (role["display"] as JsonArray).all { it is JsonObject }))
            }
            WalletEntityTypes.OPENID_CREDENTIAL_VERIFIER -> {
                val formats = role["vp_formats_supported"] as? JsonObject
                add(check("vp_formats_supported", formats != null && formats.isNotEmpty() && formats.all { (format, definition) ->
                    definition is JsonObject && when (format) {
                        "dc+sd-jwt" -> listOf("sd-jwt_alg_values", "kb-jwt_alg_values").all {
                            it !in definition || strings(definition[it])
                        }
                        "mso_mdoc" -> listOf("issuerauth_alg_values", "deviceauth_alg_values").all {
                            it !in definition || coseAlgorithms(definition[it])
                        }
                        else -> format.isNotBlank()
                    }
                }))
                for (field in listOf("request_uris", "response_uris", "redirect_uris"))
                    if (field in role) add(check(field, uriList(role[field])))
                if ("dcql_queries" in role) {
                    val queries = role["dcql_queries"]
                    add(check("dcql_queries", queries is JsonArray && queries.isNotEmpty() && queries.all { it is JsonObject }))
                }
            }
            WalletEntityTypes.OAUTH_AUTHORIZATION_SERVER -> {
                add(check("oauth_authorization_server.issuer_matches_entity_id", text(role["issuer"]) == entityIdentifier))
                for (field in listOf("authorization_endpoint", "token_endpoint", "jwks_uri"))
                    if (field in role) add(check(field, https(role[field])))
            }
        }
    }
}
