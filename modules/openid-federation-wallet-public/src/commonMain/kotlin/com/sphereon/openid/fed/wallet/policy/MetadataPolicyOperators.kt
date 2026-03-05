package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*

/**
 * Metadata policy operations for OpenID Federation.
 *
 * Implements the metadata policy operators defined in the OpenID Federation specification:
 * - `value`: Override the metadata value
 * - `default`: Set a default if not present
 * - `one_of`: Ensure the value is one of the allowed values
 * - `subset_of`: Ensure array values are a subset of allowed values
 * - `superset_of`: Ensure array values are a superset of required values
 * - `add`: Add values to an array
 * - `essential`: Mark a claim as required
 */
object MetadataPolicyOperators {

    /**
     * Result of applying a metadata policy.
     */
    data class PolicyApplicationResult(
        val metadata: JsonObject,
        val warnings: List<String> = emptyList()
    )

    /**
     * Merge two metadata policy objects.
     * The overlay takes precedence for simple values, while nested objects are merged recursively.
     */
    fun mergePolicies(base: JsonObject, overlay: JsonObject): JsonObject {
        val result = base.toMutableMap()
        for ((key, value) in overlay) {
            val existing = result[key]
            if (existing is JsonObject && value is JsonObject) {
                result[key] = mergePolicies(existing, value)
            } else {
                result[key] = value
            }
        }
        return JsonObject(result)
    }

    /**
     * Apply a metadata policy to entity metadata.
     *
     * @param metadata The entity metadata to apply the policy to
     * @param policy The combined metadata policy
     * @param entityType Optional entity type to scope the policy application
     * @return The effective metadata after policy application, with any warnings
     */
    fun applyPolicy(
        metadata: JsonObject,
        policy: JsonObject,
        entityType: String? = null
    ): PolicyApplicationResult {
        val warnings = mutableListOf<String>()

        // If entityType is specified, scope to that type
        val targetPolicy = if (entityType != null) {
            policy[entityType]?.jsonObject ?: return PolicyApplicationResult(
                metadata = metadata[entityType]?.jsonObject ?: metadata,
                warnings = warnings
            )
        } else {
            policy
        }

        val targetMetadata = if (entityType != null) {
            metadata[entityType]?.jsonObject ?: return PolicyApplicationResult(metadata, warnings)
        } else {
            metadata
        }

        val result = targetMetadata.toMutableMap()

        for ((claim, policyEntry) in targetPolicy) {
            if (policyEntry !is JsonObject) continue
            applyClaimPolicy(claim, policyEntry, result, warnings)
        }

        return PolicyApplicationResult(JsonObject(result), warnings)
    }

    private fun applyClaimPolicy(
        claim: String,
        policyEntry: JsonObject,
        result: MutableMap<String, JsonElement>,
        warnings: MutableList<String>
    ) {
        val currentValue = result[claim]

        // Apply `value` operator (override) — highest priority
        policyEntry["value"]?.let { valueOp ->
            result[claim] = valueOp
            return
        }

        // Apply `default` operator — only if value is absent or null
        if (currentValue == null || currentValue is JsonNull) {
            policyEntry["default"]?.let { defaultOp ->
                result[claim] = defaultOp
            }
        }

        // Apply `add` operator (append to array)
        policyEntry["add"]?.let { addOp ->
            if (addOp is JsonArray) {
                val current = (result[claim] as? JsonArray)?.toMutableList() ?: mutableListOf()
                current.addAll(addOp)
                result[claim] = JsonArray(current)
            }
        }

        // Validate `essential` operator
        val isEssential = policyEntry["essential"]?.jsonPrimitive?.booleanOrNull ?: false
        if (isEssential && (result[claim] == null || result[claim] is JsonNull)) {
            warnings.add("Essential claim '$claim' is missing from metadata")
        }

        // Validate and enforce `one_of` operator
        policyEntry["one_of"]?.let { oneOfOp ->
            if (oneOfOp is JsonArray && result[claim] != null && result[claim] !is JsonNull) {
                if (!oneOfOp.contains(result[claim])) {
                    warnings.add("Claim '$claim' value is not one of the allowed values: ${oneOfOp.map { it }}")
                }
            }
        }

        // Enforce `subset_of` operator (filter to allowed values)
        policyEntry["subset_of"]?.let { subsetOfOp ->
            if (subsetOfOp is JsonArray && result[claim] is JsonArray) {
                val claimValues = result[claim] as JsonArray
                val filtered = claimValues.filter { subsetOfOp.contains(it) }
                result[claim] = JsonArray(filtered)
            }
        }

        // Validate `superset_of` operator
        policyEntry["superset_of"]?.let { supersetOfOp ->
            if (supersetOfOp is JsonArray && result[claim] is JsonArray) {
                val claimValues = result[claim] as JsonArray
                for (required in supersetOfOp) {
                    if (!claimValues.contains(required)) {
                        warnings.add("Claim '$claim' missing required value from superset_of: $required")
                    }
                }
            }
        }
    }
}
