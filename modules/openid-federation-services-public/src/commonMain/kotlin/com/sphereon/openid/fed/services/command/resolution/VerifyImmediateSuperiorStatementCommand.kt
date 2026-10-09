package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.json.JsonObject

/** Exact uploaded bytes and explicit server-selected pins; authorization of those inputs is external. */
data class VerifyImmediateSuperiorStatementArgs(
    val subjectEcJwt: String,
    val uploadedSuperiorJwt: String,
    val trustChain: List<String>,
    val expectedSubjectEntityId: String,
    val expectedSuperiorEntityId: String,
    val trustAnchorEntityId: String,
    val trustAnchorPublicKeys: List<Jwk>,
)

/** This command claims only core Federation 1.1 relationship and metadata-policy evidence. */
enum class ImmediateSuperiorEvidenceScope {
    CORE_FEDERATION_1_1_RELATIONSHIP_AND_POLICY,
}

data class VerifiedImmediateSuperiorEvidence(
    val subjectEntityId: String,
    val superiorEntityId: String,
    val trustAnchorEntityId: String,
    val trustChain: List<String>,
    val effectiveMetadata: JsonObject,
    val validatedAtEpochSeconds: Long,
    val validUntilEpochSeconds: Long,
    val scope: ImmediateSuperiorEvidenceScope,
)

interface VerifyImmediateSuperiorStatementCommand :
    ServiceCommand<VerifyImmediateSuperiorStatementArgs, VerifiedImmediateSuperiorEvidence, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.resolution.verify-immediate-superior-statement"
    }
}
