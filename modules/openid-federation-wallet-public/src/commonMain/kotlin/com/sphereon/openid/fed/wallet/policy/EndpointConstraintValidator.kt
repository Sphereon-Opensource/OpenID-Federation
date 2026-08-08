package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates endpoint constraints for credential verifiers per the
 * OpenID Federation Wallet Architecture spec Section 7.3.
 *
 * Wallets MUST verify that the Credential Verifier's request_uris, response_uris,
 * and redirect_uris are registered in the entity's metadata obtained through federation.
 */
object EndpointConstraintValidator {

    /**
     * Result of validating a single endpoint.
     */
    data class EndpointValidation(
        val endpointType: String,
        val value: String,
        val valid: Boolean,
        val registeredValues: List<String>
    )

    /**
     * Validate that the given URIs are registered in the entity metadata.
     *
     * @param metadata The entity type metadata (e.g., the `openid_credential_verifier` object)
     * @param requestUri The request_uri to validate, if any
     * @param responseUri The response_uri to validate, if any
     * @param redirectUri The redirect_uri to validate, if any
     * @return List of validation results for each provided URI
     */
    fun validate(
        metadata: JsonObject,
        requestUri: String? = null,
        responseUri: String? = null,
        redirectUri: String? = null
    ): List<EndpointValidation> {
        val results = mutableListOf<EndpointValidation>()

        if (requestUri != null) {
            val registered = getRegisteredEndpoints(metadata, "request_uris")
            results.add(EndpointValidation(
                endpointType = "request_uri",
                value = requestUri,
                valid = registered.contains(requestUri),
                registeredValues = registered
            ))
        }

        if (responseUri != null) {
            val registered = getRegisteredEndpoints(metadata, "response_uris")
            results.add(EndpointValidation(
                endpointType = "response_uri",
                value = responseUri,
                valid = registered.contains(responseUri),
                registeredValues = registered
            ))
        }

        if (redirectUri != null) {
            val registered = getRegisteredEndpoints(metadata, "redirect_uris")
            results.add(EndpointValidation(
                endpointType = "redirect_uri",
                value = redirectUri,
                valid = registered.contains(redirectUri),
                registeredValues = registered
            ))
        }

        return results
    }

    /**
     * Extract registered endpoints from metadata.
     */
    fun getRegisteredEndpoints(metadata: JsonObject, key: String): List<String> {
        val element = metadata[key] ?: return emptyList()
        return try {
            if (element is JsonArray) {
                element.jsonArray.map { it.jsonPrimitive.content }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
