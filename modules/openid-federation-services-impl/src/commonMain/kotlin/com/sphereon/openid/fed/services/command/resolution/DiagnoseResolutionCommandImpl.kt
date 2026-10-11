package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Resolve a published trust path and report validation failures as diagnostic data. */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DiagnoseResolutionCommand>())
class DiagnoseResolutionCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val verifyDelegation: VerifyTrustMarkDelegationCommand,
) : TypedServiceCommandAdapter<DiagnoseResolutionArgs, ResolutionDiagnosis, FederationError>(
    commandId = DiagnoseResolutionCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<DiagnoseResolutionArgs>(),
    outputTypeToken = typeToken<ResolutionDiagnosis>(),
), DiagnoseResolutionCommand {
    private val logger = execution.federationLogger("DiagnoseResolutionCommand")

    override suspend fun doExecute(args: DiagnoseResolutionArgs,
        applyDuring: (DiagnoseResolutionArgs) -> DiagnoseResolutionArgs): IdkResult<ResolutionDiagnosis, FederationError> {
        val input = applyDuring(args)
        return try {
            require(input.maxDepth >= 0) { "maxDepth must be non-negative" }
            require(input.subject.isNotBlank() && input.trustAnchor.isNotBlank()) { "Subject and Trust Anchor are required" }
            val now = getCurrentEpochTimeSeconds()
            val resolver = DiagnosisPathResolver(context, input, now)
            val evaluator = DiagnosisEvaluator(context, input, resolver, now)
            val candidates = evaluator.candidates()
            val selected = evaluator.select(candidates)
            val anchor = resolver.configuration(input.trustAnchor)
            val thumbprints = publishedKeyThumbprints(anchor.claims)
            val comparison = when {
                anchor.failure?.code == "FETCH_FAILED" -> AnchorKeyComparison.UNAVAILABLE
                input.expectedTrustAnchorKeyThumbprints.isEmpty() -> AnchorKeyComparison.NOT_PINNED
                input.expectedTrustAnchorKeyThumbprints.toSet() == thumbprints.toSet() -> AnchorKeyComparison.MATCH
                else -> AnchorKeyComparison.MISMATCH
            }
            val errors = selected.errors.toMutableList()
            if (comparison == AnchorKeyComparison.MISMATCH) errors += ResolutionDiagnosticError(
                "TRUST_ANCHOR_KEY_MISMATCH", "Expected thumbprints differ from the Trust Anchor's published keys",
                selected.chain.lastIndex.takeIf { selected.path.reachedAnchor },
            )
            if (anchor.failure != null && errors.none { it.code == anchor.failure.code }) errors += anchor.failure.copy(
                statementIndex = selected.chain.size,
            )
            val outcome = when {
                comparison == AnchorKeyComparison.MISMATCH -> ResolutionOutcome.INVALID
                selected.valid -> ResolutionOutcome.VALID
                // An unavailable resource takes precedence over validation errors on a partial path.
                candidates.any { candidate -> candidate.errors.any { it.code == "FETCH_FAILED" } } || anchor.failure?.code == "FETCH_FAILED" -> ResolutionOutcome.INCOMPLETE
                else -> ResolutionOutcome.INVALID
            }
            // Keep a fetch diagnostic in the chosen record when another failed branch was unavailable.
            if (outcome == ResolutionOutcome.INCOMPLETE && errors.none { it.code == "FETCH_FAILED" }) {
                errors += candidates.flatMap { it.errors }.first { it.code == "FETCH_FAILED" }
            }
            val warnings = selected.policy?.warnings.orEmpty().map { ResolutionDiagnosticError("POLICY_WARNING", it) }.toMutableList()
            val verifying = candidates.filter { it.valid }
            if (verifying.map { it.policy?.metadata }.distinct().size > 1) warnings += ResolutionDiagnosticError(
                "NON_TREE_TOPOLOGY", "Verifying paths to this Trust Anchor yield different resolved metadata",
            )
            val marks = if (input.includeTrustMarks) DiagnosisTrustMarks(context, input, resolver, evaluator, verifyDelegation, now)
                .diagnose(selected.path.statements.first().claims, anchor) else emptyList()
            val chainExpiry = if (selected.path.reachedAnchor && selected.chain.all { it.expiresAt != null })
                selected.chain.mapNotNull { it.expiresAt }.minOrNull() else null
            val includedMarkExpiries = marks.filter {
                it.signatureValid && it.issuerChainValid && it.delegationValid != false && it.acceptedByTrustAnchor && it.status == TrustMarkStatus.ACTIVE
            }.mapNotNull { it.expiresAt }
            IdkResult.ok(ResolutionDiagnosis(
                subject = input.subject, trustAnchor = input.trustAnchor, resolvedAt = now,
                outcome = outcome, errors = errors.distinct(), chain = selected.chain,
                chainExpiresAt = chainExpiry, expiresAt = chainExpiry?.let { (includedMarkExpiries + it).minOrNull() },
                resolvedMetadata = selected.policy?.metadata?.let { MetadataPolicyOperators.filterEntityTypes(it, input.entityTypes) }
                    .takeIf { outcome == ResolutionOutcome.VALID },
                selfAssertedMetadata = selected.path.statements.first().claims?.get("metadata") as? JsonObject,
                policyTrace = if (input.includePolicyTrace) selected.policy?.trace.orEmpty().map { entry ->
                    PolicyTraceEntry(entry.entityType, entry.parameter, entry.contributions.map {
                        PolicyContribution(it.issuer, it.statementIndex, PolicyContributionSource.valueOf(it.source.name), it.value, it.operators)
                    }, entry.mergedOperators, entry.valueBefore, entry.valueAfter, PolicyTraceOutcome.valueOf(entry.outcome.name), entry.message)
                } else emptyList(),
                constraintChecks = selected.constraints, trustMarks = marks,
                trustAnchorKeyComparison = comparison, trustAnchorKeyThumbprints = thumbprints, warnings = warnings,
                alternativePaths = if (input.includeAlternativePaths) candidates
                    .filter { it !== selected && it.path.entities.size >= 2 && it.path.entities.last() == input.trustAnchor }
                    .take(7)
                    .map { candidate ->
                        val pathOutcome = when {
                            candidate.valid -> ResolutionOutcome.VALID
                            candidate.errors.any { it.code == "FETCH_FAILED" } -> ResolutionOutcome.INCOMPLETE
                            else -> ResolutionOutcome.INVALID
                        }
                        ResolutionPathDiagnosis(
                            entities = candidate.path.entities,
                            outcome = pathOutcome,
                            errors = candidate.errors,
                            chain = candidate.chain.map { it.copy(verified = pathOutcome == ResolutionOutcome.VALID) },
                            chainExpiresAt = if (candidate.path.reachedAnchor && candidate.chain.all { it.expiresAt != null })
                                candidate.chain.mapNotNull { it.expiresAt }.minOrNull() else null,
                            resolvedMetadata = candidate.policy?.metadata
                                ?.let { MetadataPolicyOperators.filterEntityTypes(it, input.entityTypes) }
                                .takeIf { pathOutcome == ResolutionOutcome.VALID },
                        )
                    }.takeIf { it.isNotEmpty() } else null,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Resolution diagnosis failed for ${input.subject}", e)
            federationErr(ServerError(reason = "Resolution diagnosis failed", causeDescription = e.message, exception = e))
        }
    }
}
