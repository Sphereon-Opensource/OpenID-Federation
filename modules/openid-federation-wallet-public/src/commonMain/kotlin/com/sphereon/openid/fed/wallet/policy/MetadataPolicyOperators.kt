package com.sphereon.openid.fed.wallet.policy

import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
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
    private val ARRAY_OPERATORS = setOf("add", "one_of", "subset_of", "superset_of")
    private val SCOPE_ENTITY_TYPES = setOf("openid_relying_party", "oauth_client")

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

    /** Validate signed policy structure and operator configuration even for inactive entity types. */
    private fun validatePolicy(policy: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        for ((entityType, entityPolicy) in policy) {
            if (entityPolicy !is JsonObject) {
                errors.add("metadata_policy entry for entity type '$entityType' must be a JSON object")
                continue
            }
            for ((claim, claimPolicy) in entityPolicy) {
                val path = "$entityType.$claim"
                if (claimPolicy !is JsonObject) {
                    errors.add("metadata_policy for $path must be a JSON object of operators")
                    continue
                }
                errors.addAll(validateClaimPolicy(claimPolicy, path))
            }
        }
        return errors
    }

    private fun validateClaimPolicy(operators: JsonObject, path: String): List<String> {
        val errors = mutableListOf<String>()
        for (operator in ARRAY_OPERATORS) {
            operators[operator]?.let { value ->
                arrayOperatorError(operator, value, path)?.let(errors::add)
            }
        }
        val essential = operators["essential"]
        if (essential != null &&
            (essential !is JsonPrimitive || essential.isString || essential.booleanOrNull == null)
        ) {
            errors.add("Operator 'essential' at $path must be a boolean")
        }
        if (operators["default"] is JsonNull) {
            errors.add("Operator 'default' at $path must not be null")
        }
        if (errors.isNotEmpty()) return errors

        val value = operators["value"]
        val add = operators["add"] as? JsonArray
        val oneOf = operators["one_of"] as? JsonArray
        val subsetOf = operators["subset_of"] as? JsonArray
        val supersetOf = operators["superset_of"] as? JsonArray

        if (value != null) {
            if (value is JsonNull) {
                if ("default" in operators || essential?.jsonPrimitive?.booleanOrNull == true) {
                    errors.add("Policy at $path cannot combine null value with default or essential=true")
                }
            }
            if (add != null && !containsAll(value as? JsonArray, add)) {
                errors.add("Policy at $path requires add values to be a subset of value")
            }
            if (oneOf != null && value !in oneOf) {
                errors.add("Policy at $path requires value to be among one_of values")
            }
            if (subsetOf != null && !containsAll(subsetOf, value as? JsonArray)) {
                errors.add("Policy at $path requires value values to be a subset of subset_of")
            }
            if (supersetOf != null && !containsAll(value as? JsonArray, supersetOf)) {
                errors.add("Policy at $path requires value values to be a superset of superset_of")
            }
        }
        if (oneOf != null && (add != null || subsetOf != null || supersetOf != null)) {
            errors.add("Policy at $path cannot combine one_of with add, subset_of, or superset_of")
        }
        if (add != null && subsetOf != null && !containsAll(subsetOf, add)) {
            errors.add("Policy at $path requires add values to be a subset of subset_of")
        }
        if (subsetOf != null && supersetOf != null && !containsAll(subsetOf, supersetOf)) {
            errors.add("Policy at $path requires subset_of values to contain superset_of values")
        }
        return errors
    }

    private fun containsAll(container: JsonArray?, required: JsonArray?): Boolean =
        container != null && required != null && required.all { it in container }

    /**
     * Merge [subordinate] policy into [superior] (current) policy per §6.1.4.1.
     *
     * Call order: start with empty or most-superior policy as [superior], then merge each next
     * more-subordinate policy as [subordinate].
     */
    fun mergePolicies(superior: JsonObject, subordinate: JsonObject): PolicyMergeResult {
        val inputErrors = validatePolicy(superior) + validatePolicy(subordinate)
        if (inputErrors.isNotEmpty()) return PolicyMergeResult.Error(inputErrors.first())

        val result = standardOperatorsOnly(superior).toMutableMap()
        for ((entityType, subEntityPolicy) in standardOperatorsOnly(subordinate)) {
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
        val merged = JsonObject(result)
        val mergedErrors = validatePolicy(merged)
        return if (mergedErrors.isEmpty()) PolicyMergeResult.Ok(merged)
        else PolicyMergeResult.Error(mergedErrors.first())
    }

    /** Unknown noncritical operators have no merge or application semantics. */
    private fun standardOperatorsOnly(policy: JsonObject): JsonObject = JsonObject(
        policy.mapValues { (_, entityPolicy) ->
            JsonObject((entityPolicy as JsonObject).mapNotNull { (claim, claimPolicy) ->
                val known = (claimPolicy as JsonObject).filterKeys { it in STANDARD_OPERATORS }
                if (known.isEmpty()) null else claim to JsonObject(known)
            }.toMap())
        }
    )

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
                val sup = superiorValue as? JsonArray
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                val sub = subordinateValue as? JsonArray
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                OperatorMerge.Ok(JsonArray(unionPreserveOrder(sup, sub)))
            }
            "one_of", "subset_of" -> {
                val sup = superiorValue as? JsonArray
                    ?: return OperatorMerge.Error("Policy merge error at $path.$op: expected array")
                val sub = subordinateValue as? JsonArray
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
            else -> OperatorMerge.Error("Unsupported policy operator at $path.$op")
        }
    }

    /**
     * Standard names cannot be critical; this primitive supports no additional operators.
     */
    fun validateCriticalOperators(
        @Suppress("UNUSED_PARAMETER")
        policy: JsonObject,
        criticalOperators: Collection<String>
    ): List<String> {
        return criticalOperators.distinct().map { name ->
            when {
                name.isBlank() -> "metadata_policy_crit entries must be non-empty strings"
                name in STANDARD_OPERATORS -> "Standard metadata policy operator '$name' must not be critical"
                else -> "Unsupported critical metadata policy operator: '$name'"
            }
        }
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
        errors.addAll(validatePolicy(policy))
        if (errors.isNotEmpty()) {
            val unchanged = if (entityType == null) metadata
            else (metadata[entityType] as? JsonObject ?: JsonObject(emptyMap()))
            return PolicyApplicationResult(metadata = unchanged, errors = errors)
        }

        if (entityType != null) {
            val typePolicy = policy[entityType]?.jsonObject
            val typeMetadata = metadata[entityType]?.jsonObject ?: JsonObject(emptyMap())
            if (typePolicy == null) {
                return PolicyApplicationResult(metadata = typeMetadata, errors = errors, warnings = warnings)
            }
            if (entityType !in metadata) {
                return PolicyApplicationResult(metadata = typeMetadata, errors = errors, warnings = warnings)
            }
            val applied = applyEntityTypePolicy(typeMetadata, typePolicy, entityType, errors, warnings)
            return PolicyApplicationResult(metadata = applied, errors = errors, warnings = warnings)
        }

        // Full metadata: apply policies only to declared entity types; retain types without policy.
        val result = metadata.toMutableMap()
        for ((type, typePolicyElement) in policy) {
            if (typePolicyElement !is JsonObject) {
                errors.add("metadata_policy for entity type '$type' must be a JSON object")
                continue
            }
            if (type !in metadata) {
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
     * 3. Filter entity types by each SS `allowed_entity_types` constraint
     * 4. Merge `metadata_policy` from SSs superior-first; enforce `metadata_policy_crit`
     * 5. Apply resolved policy (fail closed), then optionally scope to [entityType]
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
        val leafMetadata = leafPayload["metadata"]?.jsonObject ?: JsonObject(emptyMap())

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
        for (statement in subordinateStatements) {
            val constraintsElement = statement["constraints"] ?: continue
            val parsed = EntityStatementValidation.parseConstraints(constraintsElement)
            val constraints = parsed.constraints ?: return TrustChainMetadataResult(
                metadata = workingMetadata,
                policiesApplied = 0,
                errors = listOf(parsed.reason ?: "Invalid constraints")
            )
            val allowedTypes = constraints.allowedEntityTypes ?: continue
            workingMetadata = JsonObject(workingMetadata.filterKeys { type ->
                EntityStatementValidation.isEntityTypeAllowed(type, allowedTypes)
            })
        }
        val ssSuperiorFirst = subordinateStatements.asReversed()

        var combinedPolicy = JsonObject(emptyMap())
        var policiesApplied = 0
        val criticalOperators = linkedSetOf<String>()
        // Check every signed declaration before merging discards unknown noncritical operators.
        for (statement in ssSuperiorFirst) {
            if (!statement.containsKey("metadata_policy_crit")) continue
            val declarations = statement["metadata_policy_crit"] as? JsonArray
            if (declarations == null || declarations.isEmpty()) {
                return TrustChainMetadataResult(
                    metadata = workingMetadata,
                    policiesApplied = 0,
                    errors = listOf("metadata_policy_crit must be a non-empty array of strings")
                )
            }
            for (element in declarations) {
                val name = element as? JsonPrimitive
                if (name == null || !name.isString || name.content.isBlank()) {
                    return TrustChainMetadataResult(
                        metadata = workingMetadata,
                        policiesApplied = 0,
                        errors = listOf("metadata_policy_crit entries must be non-empty strings")
                    )
                }
                criticalOperators.add(name.content)
            }
        }
        val criticalErrors = validateCriticalOperators(combinedPolicy, criticalOperators)
        if (criticalErrors.isNotEmpty()) {
            return TrustChainMetadataResult(
                metadata = workingMetadata,
                policiesApplied = 0,
                errors = criticalErrors
            )
        }

        for (statement in ssSuperiorFirst) {
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

        if (policiesApplied == 0) {
            val scoped = if (entityType != null) {
                workingMetadata[entityType]?.jsonObject ?: JsonObject(emptyMap())
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
        val isClientScope = entityType in SCOPE_ENTITY_TYPES && claim == "scope"
        if (isClientScope) {
            val scope = result[claim]
            if (scope != null) {
                if (scope !is JsonPrimitive || !scope.isString) {
                    errors.add("Claim '$path' must be a space-separated string")
                    return
                }
                result[claim] = JsonArray(scope.content.split(' ').filter { it.isNotEmpty() }.map { JsonPrimitive(it) })
            }
        }

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
            if (isClientScope) restoreClientScope(result, claim, path, errors)
            return
        }

        // 2. add (after value)
        policyEntry["add"]?.let { addOp ->
            val toAdd = checkedArrayOperator("add", addOp, path, errors)
            if (toAdd != null) {
                val current = if (result.containsKey(claim)) {
                    result[claim]?.let { checkedArrayOperator("add", it, path, errors) }
                } else {
                    JsonArray(emptyList())
                }
                if (current != null) {
                    val merged = current.toMutableList()
                    for (item in toAdd) {
                        if (!merged.contains(item)) {
                            merged.add(item)
                        }
                    }
                    result[claim] = JsonArray(merged)
                }
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
            val allowed = checkedArrayOperator("one_of", oneOfOp, path, errors)
            if (allowed != null) {
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
            val allowed = checkedArrayOperator("subset_of", subsetOfOp, path, errors)
            if (allowed != null) {
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
            val required = checkedArrayOperator("superset_of", supersetOfOp, path, errors)
            if (required != null) {
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
        if (isClientScope) restoreClientScope(result, claim, path, errors)

        // Unknown non-critical operators are ignored (extensions)
        @Suppress("UNUSED_VARIABLE")
        val unusedWarnings = warnings
    }

    private fun checkedArrayOperator(
        operator: String,
        value: JsonElement,
        path: String,
        errors: MutableList<String>
    ): JsonArray? {
        arrayOperatorError(operator, value, path)?.let { error ->
            errors.add(error)
            return null
        }
        return value as JsonArray
    }

    private fun arrayOperatorError(operator: String, value: JsonElement, path: String): String? {
        val array = value as? JsonArray
            ?: return "Operator '$operator' at $path must be an array"
        if (array.any { item ->
                item is JsonNull || item is JsonArray ||
                    (item is JsonPrimitive && !item.isString && item.booleanOrNull != null)
            }) {
            return "Operator '$operator' at $path contains an unsupported array item"
        }
        return null
    }

    private fun restoreClientScope(
        result: MutableMap<String, JsonElement>,
        claim: String,
        path: String,
        errors: MutableList<String>
    ) {
        val scope = result[claim] ?: return
        val tokens = scope as? JsonArray
        if (tokens == null || tokens.any { it !is JsonPrimitive || !it.isString }) {
            errors.add("Claim '$path' policy result must be an array of strings")
            return
        }
        result[claim] = JsonPrimitive(tokens.joinToString(" ") { it.jsonPrimitive.content })
    }

    private fun unionPreserveOrder(a: JsonArray, b: JsonArray): List<JsonElement> {
        val out = a.toMutableList()
        for (item in b) {
            if (!out.contains(item)) out.add(item)
        }
        return out
    }
}
