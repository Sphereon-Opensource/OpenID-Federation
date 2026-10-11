package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.openid.fed.client.command.trustChain.TrustChainValidation
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import com.sphereon.openid.fed.wallet.policy.TracedPolicyResolution
import kotlinx.serialization.json.JsonObject

internal data class EvaluatedDiagnosisPath(
    val path: DiagnosisPath,
    val chain: List<DiagnosedStatement>,
    val errors: List<ResolutionDiagnosticError>,
    val constraints: List<ConstraintCheck>,
    val policy: TracedPolicyResolution?,
    val chainValid: Boolean,
) {
    val valid: Boolean get() = chainValid && policy?.isValid == true
}

/** Check every available statement while preserving discovery indexes across missing links. */
internal class DiagnosisEvaluator(
    private val context: FederationContext,
    private val args: DiagnoseResolutionArgs,
    private val resolver: DiagnosisPathResolver,
    private val now: Long,
) {
    suspend fun evaluate(path: DiagnosisPath): EvaluatedDiagnosisPath {
        val errors = path.errors.toMutableList()
        val chain = path.statements.mapIndexed { index, statement ->
            errors += listOfNotNull(statement.failure?.copy(statementIndex = index))
            val decoded = statement.forDiagnostics()
            var signatureValid = false
            if (decoded != null) {
                errors += TrustChainValidation.validateStructureDiagnostics(decoded, now, index, context.understoodCriticalClaims)
                    .map { ResolutionDiagnosticError(it.code, it.message, index) }
                val claims = decoded.payload
                if (claims.string("iss") != statement.issuer || claims.string("sub") != statement.subject ||
                    (statement.kind == StatementKind.ENTITY_CONFIGURATION && claims.string("iss") != claims.string("sub"))) {
                    errors += ResolutionDiagnosticError("ISSUER_SUBJECT_MISMATCH", "Statement identifiers do not match the discovered link", index)
                }
                val next = path.statements.getOrNull(index + 1)
                if (next?.decoded != null && claims.string("iss") != next.claims?.string("sub")) {
                    errors += ResolutionDiagnosticError("ISSUER_SUBJECT_MISMATCH", "Statement chain broken between $index and ${index + 1}", index)
                }

                val keySources = buildList {
                    if (statement.kind == StatementKind.ENTITY_CONFIGURATION) add(statement.claims)
                    if (next?.decoded != null) add(next.claims)
                }
                signatureValid = statement.decoded != null && keySources.isNotEmpty()
                for (source in keySources.takeIf { statement.decoded != null }.orEmpty()) {
                    val signature = verifyDiagnosticSignature(context,
                        requireNotNull(statement.jwt), resolver.keys(source))
                    if (!signature.kidFound) errors += ResolutionDiagnosticError("KID_NOT_FOUND", "Signing kid is not in the verification JWKS", index)
                    if (!signature.signatureValid) {
                        signatureValid = false
                        errors += ResolutionDiagnosticError("SIGNATURE_INVALID", "Statement signature verification failed", index)
                    }
                }
                if (statement.issuerSignature?.signatureValid == false) signatureValid = false
            }
            DiagnosedStatement(
                index = index, kind = statement.kind,
                issuer = statement.claims?.string("iss") ?: statement.issuer,
                subject = statement.claims?.string("sub") ?: statement.subject,
                issuedAt = statement.claims?.epoch("iat"), expiresAt = statement.claims?.epoch("exp"),
                kid = decoded?.header?.kid?.takeIf { it.isNotBlank() }, alg = decoded?.header?.alg?.takeIf { it.isNotBlank() }, typ = decoded?.header?.typ,
                signatureValid = signatureValid, sourceUrl = statement.sourceUrl, jwt = statement.jwt,
                claims = statement.claims, verified = false,
            )
        }

        val constraints = constraintChecks(path)
        errors += constraints.filter { it.outcome == ConstraintOutcome.FAILED }.map {
            ResolutionDiagnosticError("CONSTRAINT_VIOLATION", it.message ?: "Constraint failed", it.statementIndex)
        }
        val chainValid = path.reachedAnchor && path.statements.all { it.decoded != null } && errors.isEmpty()
        // Policies can still explain a failed signature or constraint when all payloads are available.
        val payloads = path.statements.mapNotNull { it.claims }
        val policy = if (payloads.size == path.statements.size) MetadataPolicyOperators.resolveWithTrace(payloads) else null
        errors += policy?.errors.orEmpty().map { error ->
            val entry = policy?.trace?.firstOrNull { it.entityType == error.entityType && it.parameter == error.parameter }
            val index = entry?.contributions?.lastOrNull { it.source == com.sphereon.openid.fed.wallet.policy.PolicyContributionSource.METADATA_POLICY }?.statementIndex
            ResolutionDiagnosticError(error.code.name, error.message, index)
        }
        return EvaluatedDiagnosisPath(path, chain.map { it.copy(verified = chainValid) }, errors.distinct(), constraints, policy, chainValid)
    }

    private fun constraintChecks(path: DiagnosisPath): List<ConstraintCheck> = buildList {
        val prefix = path.statements.takeWhile { it.forDiagnostics() != null }.map { requireNotNull(it.forDiagnostics()) }
        addAll(TrustChainValidation.validateConstraints(prefix).map {
            ConstraintCheck(it.issuer, it.statementIndex, it.constraint, ConstraintOutcome.valueOf(it.outcome.name), it.message)
        })
        for (index in prefix.size until path.statements.size) {
            val statement = path.statements[index]
            val constraints = statement.claims?.get("constraints") as? JsonObject ?: continue
            for (name in constraints.keys) add(ConstraintCheck(statement.issuer, index, name, ConstraintOutcome.IGNORED,
                "Cannot evaluate against an unavailable statement below this link"))
        }
    }

    suspend fun candidates(subject: String = args.subject): List<EvaluatedDiagnosisPath> =
        resolver.discover(subject).map { evaluate(it) }

    fun select(candidates: List<EvaluatedDiagnosisPath>): EvaluatedDiagnosisPath =
        candidates.firstOrNull { it.valid } ?: candidates.maxWithOrNull(
            compareBy<EvaluatedDiagnosisPath> { it.path.reachedAnchor }.thenBy { it.path.depth },
        ) ?: error("Path discovery produced no diagnostic path")
}

internal fun DiagnosisStatement.forDiagnostics(): Jwt? = decoded ?: claims?.let { payload -> Jwt(
        JwtHeader(kid = rawHeader?.string("kid").orEmpty(), alg = rawHeader?.string("alg").orEmpty(), typ = rawHeader?.string("typ")),
        payload, "",
    )
}
