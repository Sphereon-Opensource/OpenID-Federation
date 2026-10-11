package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Source of an original, unmerged metadata parameter contribution. */
@Serializable
enum class PolicyContributionSource {
    METADATA,
    METADATA_POLICY
}

/** The change from the subject's raw metadata to its full resolved metadata. */
@Serializable
enum class PolicyTraceOutcome {
    UNCHANGED,
    SET,
    MODIFIED,
    REMOVED,
    ERROR
}

/** Stable policy failure categories, independent of human-readable diagnostics. */
@Serializable
enum class PolicyResolutionErrorCode {
    POLICY_CONFLICT,
    POLICY_VIOLATION,
    UNKNOWN_CRITICAL_OPERATOR
}

@Serializable
data class PolicyResolutionError(
    val code: PolicyResolutionErrorCode,
    val message: String,
    val entityType: String? = null,
    val parameter: String? = null
)

/** [statementIndex] is the index in the original subject-first decoded Trust Chain. */
@Serializable
data class PolicyContribution(
    val issuer: String,
    val statementIndex: Int,
    val source: PolicyContributionSource,
    val value: JsonElement? = null,
    val operators: JsonObject? = null
)

@Serializable
data class PolicyTraceEntry(
    val entityType: String,
    val parameter: String,
    val contributions: List<PolicyContribution>,
    val mergedOperators: JsonObject? = null,
    val valueBefore: JsonElement? = null,
    val valueAfter: JsonElement? = null,
    val outcome: PolicyTraceOutcome,
    val message: String? = null
)

/**
 * [metadata] has the optional entity-type filter applied. [trace] describes the full
 * resolution before that caller filter, including signed constraints and inactive policies.
 */
@Serializable
data class TracedPolicyResolution(
    val metadata: JsonObject,
    val errors: List<PolicyResolutionError> = emptyList(),
    val warnings: List<String> = emptyList(),
    val trace: List<PolicyTraceEntry> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()
}
