package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Diagnose resolution against the keys published by a chosen Trust Anchor. */
interface DiagnoseResolutionCommand : ServiceCommand<DiagnoseResolutionArgs, ResolutionDiagnosis, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.resolution.diagnose"
    }
}

@Serializable
data class DiagnoseResolutionArgs(
    val subject: String,
    val trustAnchor: String,
    val entityTypes: List<String>? = null,
    val expectedTrustAnchorKeyThumbprints: List<String> = emptyList(),
    val bypassCache: Boolean = true,
    val includePolicyTrace: Boolean = true,
    val includeTrustMarks: Boolean = true,
    val checkTrustMarkStatus: Boolean = true,
    val maxDepth: Int = 5,
)

@Serializable
enum class ResolutionOutcome { VALID, INVALID, INCOMPLETE }

@Serializable
enum class StatementKind { ENTITY_CONFIGURATION, SUBORDINATE_STATEMENT }

@Serializable
enum class AnchorKeyComparison { NOT_PINNED, MATCH, MISMATCH, UNAVAILABLE }

@Serializable
data class ResolutionDiagnosticError(val code: String, val message: String, val statementIndex: Int? = null)

@Serializable
data class ResolutionDiagnosis(
    val subject: String,
    val trustAnchor: String,
    val resolvedAt: Long,
    val outcome: ResolutionOutcome,
    val errors: List<ResolutionDiagnosticError>,
    val chain: List<DiagnosedStatement>,
    val chainExpiresAt: Long?,
    val expiresAt: Long?,
    val resolvedMetadata: JsonObject?,
    val selfAssertedMetadata: JsonObject?,
    val policyTrace: List<PolicyTraceEntry>,
    val constraintChecks: List<ConstraintCheck>,
    val trustMarks: List<DiagnosedTrustMark>,
    val trustAnchorKeyComparison: AnchorKeyComparison,
    val trustAnchorKeyThumbprints: List<String>,
    val warnings: List<ResolutionDiagnosticError>,
)

@Serializable
data class DiagnosedStatement(
    val index: Int,
    val kind: StatementKind,
    val issuer: String,
    val subject: String,
    val issuedAt: Long?,
    val expiresAt: Long?,
    val kid: String?,
    val alg: String?,
    val typ: String?,
    val signatureValid: Boolean,
    val sourceUrl: String?,
    val jwt: String?,
    val claims: JsonObject?,
    val verified: Boolean,
)

@Serializable
enum class PolicyTraceOutcome { UNCHANGED, SET, MODIFIED, REMOVED, ERROR }

@Serializable
enum class PolicyContributionSource { METADATA, METADATA_POLICY }

@Serializable
data class PolicyTraceEntry(
    val entityType: String,
    val parameter: String,
    val contributions: List<PolicyContribution>,
    val mergedOperators: JsonObject?,
    val valueBefore: JsonElement?,
    val valueAfter: JsonElement?,
    val outcome: PolicyTraceOutcome,
    val message: String?,
)

@Serializable
data class PolicyContribution(
    val issuer: String,
    val statementIndex: Int,
    val source: PolicyContributionSource,
    val value: JsonElement?,
    val operators: JsonObject?,
)

@Serializable
enum class ConstraintOutcome { PASSED, FAILED, IGNORED }

@Serializable
data class ConstraintCheck(
    val issuer: String,
    val statementIndex: Int,
    val constraint: String,
    val outcome: ConstraintOutcome,
    val message: String?,
)

@Serializable
enum class TrustMarkStatus {
    @SerialName("active") ACTIVE,
    @SerialName("expired") EXPIRED,
    @SerialName("revoked") REVOKED,
    @SerialName("invalid") INVALID,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class TrustMarkStatusSource { STATUS_ENDPOINT, SIGNATURE_ONLY }

@Serializable
data class DiagnosedTrustMark(
    val trustMarkType: String,
    val issuer: String,
    val issuedAt: Long?,
    val expiresAt: Long?,
    val signatureValid: Boolean,
    val issuerChainValid: Boolean,
    val delegationValid: Boolean?,
    val acceptedByTrustAnchor: Boolean,
    val status: TrustMarkStatus,
    val statusSource: TrustMarkStatusSource,
    val jwt: String?,
)
