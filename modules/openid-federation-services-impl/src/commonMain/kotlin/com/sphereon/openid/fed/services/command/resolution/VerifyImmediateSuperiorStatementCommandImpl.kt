package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.MetadataPolicyApplicationError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyImmediateSuperiorStatementCommand>())
class VerifyImmediateSuperiorStatementCommandImpl(
    execution: SessionExecution,
    private val verifyTrustChainCommand: VerifyTrustChainCommand,
) : TypedServiceCommandAdapter<VerifyImmediateSuperiorStatementArgs, VerifiedImmediateSuperiorEvidence, FederationError>(
    commandId = VerifyImmediateSuperiorStatementCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<VerifyImmediateSuperiorStatementArgs>(),
    outputTypeToken = typeToken<VerifiedImmediateSuperiorEvidence>(),
), VerifyImmediateSuperiorStatementCommand {
    override suspend fun doExecute(
        args: VerifyImmediateSuperiorStatementArgs,
        applyDuring: (VerifyImmediateSuperiorStatementArgs) -> VerifyImmediateSuperiorStatementArgs,
    ): IdkResult<VerifiedImmediateSuperiorEvidence, FederationError> {
        val applied = applyDuring(args)
        val chain = applied.trustChain.toList()
        val pins = applied.trustAnchorPublicKeys.map { it.copy(x5c = it.x5c?.toList()) }
        val subjectId = applied.expectedSubjectEntityId

        fun invalid(reason: String) = federationErr<VerifiedImmediateSuperiorEvidence>(
            TrustChainValidationFailedError(subjectId, reason)
        )

        if (subjectId.isBlank() || applied.expectedSuperiorEntityId.isBlank() || applied.trustAnchorEntityId.isBlank()) {
            return invalid("Expected subject, superior, and trust anchor identifiers are required")
        }
        if (chain.size < 2 || pins.isEmpty()) {
            return invalid("A signed subject and immediate-superior chain with explicit trust anchor keys is required")
        }
        if (chain[0] != applied.subjectEcJwt || chain[1] != applied.uploadedSuperiorJwt) {
            return invalid("Trust chain does not contain the exact uploaded subject and superior statements")
        }

        return try {
            val statements = chain.map(::decodeJWTComponents)
            val leaf = statements[0]
            val superior = statements[1]
            if (leaf.payload["iss"]?.jsonPrimitive?.contentOrNull != subjectId ||
                leaf.payload["sub"]?.jsonPrimitive?.contentOrNull != subjectId ||
                superior.payload["iss"]?.jsonPrimitive?.contentOrNull != applied.expectedSuperiorEntityId ||
                superior.payload["sub"]?.jsonPrimitive?.contentOrNull != subjectId ||
                applied.expectedSuperiorEntityId == subjectId
            ) {
                return invalid("Subject Entity Configuration and immediate-superior statement identifiers do not match")
            }
            val authorityLink = EntityStatementValidation.validateAuthorityHintsLink(leaf, superior)
            if (!authorityLink.ok) {
                return invalid(authorityLink.reason ?: "Immediate superior is missing from authority_hints")
            }

            val verification = verifyTrustChainCommand.verifyTrustChain(
                trustChain = chain.toTypedArray(),
                trustAnchor = applied.trustAnchorEntityId,
                currentTime = getCurrentEpochTimeSeconds(),
                trustAnchorPublicKeys = pins,
            )
            if (verification.isErr) return federationErr(verification.error)
            if (!verification.value.isValid) {
                return invalid(verification.value.errorMessage ?: "Trust chain verification returned invalid")
            }

            val policy = MetadataPolicyOperators.resolveFromTrustChainPayloads(
                decodedStatements = statements.map { it.payload },
            )
            if (!policy.isValid) {
                return federationErr(MetadataPolicyApplicationError(subjectId, policy.errors.joinToString("; ")))
            }

            val validatedAt = getCurrentEpochTimeSeconds()
            val expiries = statements.map { statement ->
                EntityStatementValidation.conservativeExpiryEpochSeconds(statement.payload["exp"])
                    ?: return invalid("Signed trust chain contains an invalid expiration")
            }
            val validUntil = expiries.minOrNull() ?: return invalid("Trust chain has no signed expiration")
            if (validUntil <= validatedAt) return invalid("Signed trust chain has expired")

            IdkResult.ok(
                VerifiedImmediateSuperiorEvidence(
                    subjectEntityId = subjectId,
                    superiorEntityId = applied.expectedSuperiorEntityId,
                    trustAnchorEntityId = applied.trustAnchorEntityId,
                    trustChain = chain,
                    effectiveMetadata = policy.metadata,
                    validatedAtEpochSeconds = validatedAt,
                    validUntilEpochSeconds = validUntil,
                    scope = ImmediateSuperiorEvidenceScope.CORE_FEDERATION_1_1_RELATIONSHIP_AND_POLICY,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            federationErr(TrustChainValidationFailedError(
                subjectId,
                "Immediate-superior evidence could not be validated",
                e,
            ))
        }
    }
}
