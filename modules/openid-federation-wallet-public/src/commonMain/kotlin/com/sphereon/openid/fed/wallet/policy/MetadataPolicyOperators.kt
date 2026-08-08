package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*

/**
 * Metadata policy operations for OpenID Federation 1.1 §6.1.
 *
 * Standard operators: `value`, `add`, `default`, `one_of`, `subset_of`, `superset_of`, `essential`.
 *
 * Merge walks Superior → Subordinate (Trust Anchor side first). Application order per claim:
 * `value` → `add` → `default` → `one_of` → `subset_of` → `superset_of` → `essential`.
 * Failed checks produce policy errors (fail closed).
 */
object MetadataPolicyOperators {

    /** Standard operator names from OpenID Federation 1.1 §6.1.3.1. */
    val STANDARD_OPERATORS: Set<String> = setOf(
        "value", "add", "default", "one_of", "subset_of", "superset_of", "essential"
    )

    /**
     * Result of applying a metadata policy.
     *
     * [errors] are fatal policy violations; when non-empty the resolved metadata MUST NOT be used.
     * [warnings] are reserved for non-fatal diagnostics (currently unused for standard operators).
     */
    data class PolicyApplicationResult(
        val metadata: JsonObject,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    /**
     * Result of merging metadata policies.
     */
    sealed class PolicyMergeResult {
        data class Ok(val policy: JsonObject) : PolicyMergeResult()
        data class Error(val reason: String) : PolicyMergeResult()
    }

    /**
     * Merge [subordinate] policy into [superior] (current) policy per §6.1.4.1.
     *
     * Call order: start with empty or most-superior policy as [superior], then merge each next
     * more-subordinate policy as [subordinate].
     */
    fun mergePolicies(superior: JsonObject, subordinate: JsonObject): PolicyMergeResult {
        val result = superior.toMutableMap()
        for ((entityType, subEntityPolicy) in subordinate) {
            if (subEntityPolicy !is JsonObject) {
                return PolicyMergeResult.Error(
                    "metadata_policy entry for entity type '$entityType' must be a JSON object"
                )
            }
            val existing = result[entityType]
            if (existing == null) {
                result[entityType] = subEntityPolicy
                continue
            }
            if (existing !is JsonObject) {
                return PolicyMergeResult.Error(
                    "metadata_policy entry for entity type '$entityType' must be a JSON object"
                )
            }
            when (val merged = mergeEntityTypePolicies(existing, subEntityPolicy, entityType)) {
                is PolicyMergeResult.Ok -> result[entityType] = merged.policy
                is PolicyMergeResult.Error -> return merged
            }
        }
        return PolicyMergeResult.Ok(JsonObject(result))
    }

    /**
     * Backward-compatible merge that returns the superior policy on error (prefer [mergePolicies]).
     * Prefer callers that handle [PolicyMergeResult].
     */
    @Deprecated(
        message = "Use mergePolicies and handle PolicyMergeResult",
        replaceWith = ReplaceWith("mergePolicies(base, overlay)")
    )
    fun mergePoliciesLegacy(base: JsonObject, overlay: JsonObject): JsonObject {
        return when (val r = mergePolicies(base, overlay)) {
            is PolicyMergeResult.Ok -> r.policy
            is PolicyMergeResult.Error -> base
        }
    }

    private fun mergeEntityTypePolicies(
        superior: JsonObject,
        subordinate: JsonObject,
        entityType: String
    ): PolicyMergeResult {
        val result = superior.toMutableMap()
        for ((claim, subClaimPolicy) in subordinate) {
            if (subClaimPolicy !is JsonObject) {
                return PolicyMergeResult.Error(
                    "metadata_policy for $entityType.$claim must be a JSON object of operators"
                )
            }
            val existing = result[claim]
            if (existing == null) {
                result[claim] = subClaimPolicy
                continue
            }
            if (existing !is JsonObject) {
                return PolicyMergeResult.Error(
                    "metadata_policy for $entityType.$claim must be a JSON object of operators"
                )
            }
            when (val merged = mergeClaimOperators(existing, subClaimPolicy, "$entityType.$claim")) {
                is PolicyMergeResult.Ok -> result[claim] = merged.policy
                is PolicyMergeResult.Error -> return merged
            }
        }
        return PolicyMergeResult.Ok(JsonObject(result))
    }

    private fun mergeClaimOperators(
        superior: JsonObject,
        subordinate: JsonObject,
        path: String
    ): PolicyMergeResult {
        val result = superior.toMutableMap()
        for ((op, subValue) in subordinate) {
            val existing = result[op]
            if (existing == null) {
                result[op] = subValue
                continue
            }
            when (val merged = mergeOperatorValues(op, existing, subValue, path)) {
                is OperatorMerge.Ok -> result[op] = merged.value
                is OperatorMerge.Error -> return PolicyMergeResult.Error(merged.reason)
            }
        }
        return PolicyMergeResult.Ok(JsonObject(result))
    }

    private sealed class OperatorMerge {
        data class Ok(val value: JsonElement) : OperatorMerge()
        data class Error(val reason: String) : OperatorMerge()
    }

    /** Merge two operator values per §6.1.3.1. */
    private fun mergeOperatorValues(
        op: String,
        superiorValue: JsonElement,
        subordinateValue: JsonElement,
        path: String
    ): OperatorMerge {
        return when (op) {
            "value", "default" -> {
                if (superiorValue != subordinateValue) {
                    OperatorMerge.Error("Policy merge error at $path.$op: operator values must be equal")
                } else {
                    OperatorMerge.Ok(superiorValue)
                }
            }
            "add", "superset_of" -> {
                val sup = toJsonArray(superiorValue)
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                val sub = toJsonArray(subordinateValue)
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                OperatorMerge.Ok(JsonArray(unionPreserveOrder(sup, sub)))
            }
            "one_of", "subset_of" -> {
                val sup = toJsonArray(superiorValue)
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                val sub = toJsonArray(subordinateValue)
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                val intersection = sup.filter { sub.contains(it) }
                if (op == "one_of" && intersection.isEmpty()) {
                    return OperatorMerge.Error(
                        "Policy merge error at $path.one_of: intersection of operator values is empty"
                    )
                }
                OperatorMerge.Ok(JsonArray(intersection))
            }
            "essential" -> {
                val sup = superiorValue.jsonPrimitive.booleanOrNull
                    ?: return OperatorMerge.Error("Policy merge error at $path.essential: expected boolean")
                val sub = subordinateValue.jsonPrimitive.booleanOrNull
                    ?: return OperatorMerge.Error("Policy merge error at $path.essential: expected boolean")
                OperatorMerge.Ok(JsonPrimitive(sup || sub))
            }
            else -> {
                if (superiorValue != subordinateValue) {
                    OperatorMerge.Error(
                        "Policy merge error at $path.$op: non-standard operator values must be equal"
                    )
                } else {
                    OperatorMerge.Ok(superiorValue)
                }
            }
        }
    }

    /**
     * Validate that every operator listed in [criticalOperators] is understood (standard or supported).
     */
    fun validateCriticalOperators(
        policy: JsonObject,
        criticalOperators: Collection<String>
    ): List<String> {
        if (criticalOperators.isEmpty()) return emptyList()
        val errors = mutableListOf<String>()
        val usedOperators = collectOperatorNames(policy)
        for (crit in criticalOperators) {
            if (crit in STANDARD_OPERATORS) continue
            if (crit !in usedOperators) continue
            // Critical non-standard operator present and not in standard set → unsupported
            errors.add("Unsupported critical metadata policy operator: '$crit'")
        }
        // Also: if crit lists operators that appear in policy but we don't support them
        for (op in usedOperators) {
            if (op !in STANDARD_OPERATORS && op in criticalOperators) {
                if (errors.none { it.contains("'$op'") }) {
                    errors.add("Unsupported critical metadata policy operator: '$op'")
                }
            }
        }
        return errors.distinct()
    }

    private fun collectOperatorNames(policy: JsonObject): Set<String> {
        val ops = mutableSetOf<String>()
        for ((_, entityPolicy) in policy) {
            val entityObj = entityPolicy as? JsonObject ?: continue
            for ((_, claimPolicy) in entityObj) {
                val claimObj = claimPolicy as? JsonObject ?: continue
                ops.addAll(claimObj.keys)
            }
        }
        return ops
    }

    /**
     * Apply a resolved metadata policy to entity metadata.
     *
     * @param metadata Leaf entity `metadata` (optionally after Immediate Superior `metadata` overrides)
     * @param policy Combined `metadata_policy` for the Trust Chain
     * @param entityType Optional Entity Type Identifier to scope application and returned metadata
     */
    fun applyPolicy(
        metadata: JsonObject,
        policy: JsonObject,
        entityType: String? = null
    ): PolicyApplicationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (entityType != null) {
            val typePolicy = policy[entityType]?.jsonObject
            val typeMetadata = metadata[entityType]?.jsonObject ?: JsonObject(emptyMap())
            if (typePolicy == null) {
                return PolicyApplicationResult(metadata = typeMetadata, errors = errors, warnings = warnings)
            }
            val applied = applyEntityTypePolicy(typeMetadata, typePolicy, entityType, errors, warnings)
            return PolicyApplicationResult(metadata = applied, errors = errors, warnings = warnings)
        }

        // Full metadata: apply each entity-type policy; leave types without policy unchanged
        val result = metadata.toMutableMap()
        for ((type, typePolicyElement) in policy) {
            if (typePolicyElement !is JsonObject) {
                errors.add("metadata_policy for entity type '$type' must be a JSON object")
                continue
            }
            val typeMetadata = metadata[type]?.jsonObject ?: JsonObject(emptyMap())
            result[type] = applyEntityTypePolicy(typeMetadata, typePolicyElement, type, errors, warnings)
        }
        return PolicyApplicationResult(JsonObject(result), errors, warnings)
    }

    /**
     * Apply Immediate Superior Subordinate Statement `metadata` overrides onto leaf metadata.
     * Only Entity Types present in the leaf metadata are updated (spec §3.1.1).
     */
    fun applySuperiorMetadata(leafMetadata: JsonObject, superiorMetadata: JsonObject): JsonObject {
        val result = leafMetadata.toMutableMap()
        for ((entityType, superiorTypeMeta) in superiorMetadata) {
            if (entityType !in leafMetadata) continue
            if (superiorTypeMeta !is JsonObject) continue
            val leafType = leafMetadata[entityType]
            if (leafType is JsonObject) {
                val merged = leafType.toMutableMap()
                for ((k, v) in superiorTypeMeta) {
                    merged[k] = v
                }
                result[entityType] = JsonObject(merged)
            } else {
                result[entityType] = superiorTypeMeta
            }
        }
        return JsonObject(result)
    }

    /**
     * Derive Resolved Metadata from decoded Trust Chain JWT payloads (OIDFed 1.1 §6.1 / §10).
     *
     * [decodedStatements] must be ordered leaf Entity Configuration first, Trust Anchor EC last
     * (standard Trust Chain order). Subordinate Statements are detected via `iss != sub`.
     *
     * Steps:
     * 1. Start from leaf `metadata`
     * 2. Apply Immediate Superior SS `metadata` overrides
     * 3. Merge `metadata_policy` from SSs superior-first; enforce `metadata_policy_crit`
     * 4. Apply resolved policy (fail closed)
     * 5. Optionally scope to a single [entityType] (returns that type's object only)
     *
     * @return [TrustChainMetadataResult] with effective metadata and how many policies merged
     */
    fun resolveFromTrustChainPayloads(
        decodedStatements: List<JsonObject>,
        entityType: String? = null
    ): TrustChainMetadataResult {
        if (decodedStatements.isEmpty()) {
            return TrustChainMetadataResult(
                metadata = JsonObject(emptyMap()),
                policiesApplied = 0,
                errors = listOf("Trust chain is empty")
            )
        }

        val leafPayload = decodedStatements.first()
        val leafMetadata = leafPayload["metadata"]?.jsonObject
            ?: return TrustChainMetadataResult(
                metadata = JsonObject(emptyMap()),
                policiesApplied = 0
            )

        val leafSub = leafPayload["sub"]?.jsonPrimitive?.contentOrNull
        val immediateSuperiorSs = decodedStatements.drop(1).firstOrNull { stmt ->
            isSubordinateStatement(stmt) &&
                (leafSub == null || stmt["sub"]?.jsonPrimitive?.contentOrNull == leafSub)
        } ?: decodedStatements.getOrNull(1)?.takeIf { isSubordinateStatement(it) }

        var workingMetadata = leafMetadata
        immediateSuperiorSs?.get("metadata")?.jsonObject?.let { superiorMeta ->
            workingMetadata = applySuperiorMetadata(workingMetadata, superiorMeta)
        }

        val subordinateStatements = decodedStatements.filter { isSubordinateStatement(it) }
        val ssSuperiorFirst = subordinateStatements.asReversed()

        var combinedPolicy = JsonObject(emptyMap())
        var policiesApplied = 0
        val criticalOperators = linkedSetOf<String>()
        val errors = mutableListOf<String>()

        for (statement in ssSuperiorFirst) {
            statement["metadata_policy_crit"]?.let { critElement ->
                when (critElement) {
                    is JsonArray -> critElement.forEach { el ->
                        el.jsonPrimitive.contentOrNull?.let { criticalOperators.add(it) }
                    }
                    else -> {
                        return TrustChainMetadataResult(
                            metadata = workingMetadata,
                            policiesApplied = policiesApplied,
                            errors = listOf("metadata_policy_crit must be an array of strings")
                        )
                    }
                }
            }

            val metadataPolicy = statement["metadata_policy"]?.jsonObject ?: continue
            when (val mergeResult = mergePolicies(combinedPolicy, metadataPolicy)) {
                is PolicyMergeResult.Ok -> {
                    combinedPolicy = mergeResult.policy
                    policiesApplied++
                }
                is PolicyMergeResult.Error -> {
                    return TrustChainMetadataResult(
                        metadata = workingMetadata,
                        policiesApplied = policiesApplied,
                        errors = listOf(mergeResult.reason)
                    )
                }
            }
        }

        errors.addAll(validateCriticalOperators(combinedPolicy, criticalOperators))
        if (errors.isNotEmpty()) {
            return TrustChainMetadataResult(
                metadata = workingMetadata,
                policiesApplied = policiesApplied,
                errors = errors
            )
        }

        if (policiesApplied == 0) {
            val scoped = if (entityType != null) {
                workingMetadata[entityType]?.jsonObject ?: workingMetadata
            } else {
                workingMetadata
            }
            return TrustChainMetadataResult(
                metadata = scoped,
                policiesApplied = 0
            )
        }

        val applied = applyPolicy(workingMetadata, combinedPolicy, entityType)
        return TrustChainMetadataResult(
            metadata = applied.metadata,
            policiesApplied = policiesApplied,
            errors = applied.errors,
            warnings = applied.warnings
        )
    }

    /**
     * Filter resolved full metadata to the given Entity Type Identifiers.
     * When [entityTypes] is null or empty, returns [metadata] unchanged.
     */
    fun filterEntityTypes(metadata: JsonObject, entityTypes: Collection<String>?): JsonObject {
        if (entityTypes.isNullOrEmpty()) return metadata
        val filtered = metadata.entries
            .filter { (key, _) -> key in entityTypes }
            .associate { it.key to it.value }
        return JsonObject(filtered)
    }

    private fun isSubordinateStatement(payload: JsonObject): Boolean {
        val iss = payload["iss"]?.jsonPrimitive?.contentOrNull
        val sub = payload["sub"]?.jsonPrimitive?.contentOrNull
        return iss != null && sub != null && iss != sub
    }

    /**
     * Result of resolving metadata from a Trust Chain (policy merge + application).
     */
    data class TrustChainMetadataResult(
        val metadata: JsonObject,
        val policiesApplied: Int,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    private fun applyEntityTypePolicy(
        metadata: JsonObject,
        policy: JsonObject,
        entityType: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ): JsonObject {
        val result = metadata.toMutableMap()
        for ((claim, policyEntry) in policy) {
            if (policyEntry !is JsonObject) {
                errors.add("Policy for $entityType.$claim must be a JSON object")
                continue
            }
            applyClaimPolicy(claim, policyEntry, result, entityType, errors, warnings)
        }
        return JsonObject(result)
    }

    private fun applyClaimPolicy(
        claim: String,
        policyEntry: JsonObject,
        result: MutableMap<String, JsonElement>,
        entityType: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val path = "$entityType.$claim"

        // 1. value (first) — null removes the parameter
        if (policyEntry.containsKey("value")) {
            val valueOp = policyEntry["value"]
            if (valueOp == null || valueOp is JsonNull) {
                result.remove(claim)
            } else {
                result[claim] = valueOp
            }
            // value replaces; still run essential if present after value
            val isEssential = policyEntry["essential"]?.jsonPrimitive?.booleanOrNull ?: false
            if (isEssential && (result[claim] == null || result[claim] is JsonNull)) {
                errors.add("Essential claim '$path' is missing from metadata after policy application")
            }
            return
        }

        // 2. add (after value)
        policyEntry["add"]?.let { addOp ->
            val toAdd = toJsonArray(addOp)
            if (toAdd == null) {
                errors.add("Operator 'add' at $path must be an array (or a single value coercible to array)")
            } else {
                val current = (result[claim] as? JsonArray)?.toList() ?: emptyList()
                val merged = current.toMutableList()
                for (item in toAdd) {
                    if (!merged.contains(item)) {
                        merged.add(item)
                    }
                }
                result[claim] = JsonArray(merged)
            }
        }

        // 3. default (after add)
        val currentAfterAdd = result[claim]
        if (currentAfterAdd == null || currentAfterAdd is JsonNull) {
            policyEntry["default"]?.let { defaultOp ->
                if (defaultOp !is JsonNull) {
                    result[claim] = defaultOp
                }
            }
        }

        // 4. one_of (after default)
        policyEntry["one_of"]?.let { oneOfOp ->
            val allowed = toJsonArray(oneOfOp)
            if (allowed == null) {
                errors.add("Operator 'one_of' at $path must be an array")
            } else {
                val value = result[claim]
                if (value != null && value !is JsonNull) {
                    if (!allowed.contains(value)) {
                        errors.add("Claim '$path' value is not one of the allowed values")
                    }
                }
            }
        }

        // 5. subset_of (after one_of) — filter to intersection
        policyEntry["subset_of"]?.let { subsetOfOp ->
            val allowed = toJsonArray(subsetOfOp)
            if (allowed == null) {
                errors.add("Operator 'subset_of' at $path must be an array")
            } else {
                val value = result[claim]
                if (value is JsonArray) {
                    result[claim] = JsonArray(value.filter { allowed.contains(it) })
                } else if (value != null && value !is JsonNull) {
                    errors.add("Claim '$path' must be an array for subset_of")
                }
            }
        }

        // 6. superset_of (after subset_of)
        policyEntry["superset_of"]?.let { supersetOfOp ->
            val required = toJsonArray(supersetOfOp)
            if (required == null) {
                errors.add("Operator 'superset_of' at $path must be an array")
            } else {
                val value = result[claim]
                if (value is JsonArray) {
                    for (req in required) {
                        if (!value.contains(req)) {
                            errors.add("Claim '$path' missing required value from superset_of: $req")
                        }
                    }
                } else if (value != null && value !is JsonNull) {
                    errors.add("Claim '$path' must be an array for superset_of")
                }
            }
        }

        // 7. essential (last)
        val isEssential = policyEntry["essential"]?.jsonPrimitive?.booleanOrNull ?: false
        if (isEssential && (result[claim] == null || result[claim] is JsonNull)) {
            errors.add("Essential claim '$path' is missing from metadata")
        }

        // Unknown non-critical operators are ignored (extensions)
        @Suppress("UNUSED_VARIABLE")
        val unusedWarnings = warnings
    }

    private fun toJsonArray(element: JsonElement): JsonArray? {
        return when (element) {
            is JsonArray -> element
            is JsonNull -> null
            else -> JsonArray(listOf(element)) // coerce single value (common in admin tests for `add`)
        }
    }

    private fun unionPreserveOrder(a: JsonArray, b: JsonArray): List<JsonElement> {
        val out = a.toMutableList()
        for (item in b) {
            if (!out.contains(item)) out.add(item)
        }
        return out
    }
}
