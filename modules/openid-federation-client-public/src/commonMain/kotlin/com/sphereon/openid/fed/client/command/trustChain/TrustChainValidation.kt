package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Jwt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class TrustChainConstraintOutcome { PASSED, FAILED, IGNORED }

/** A check of one constraint parameter on a Subordinate Statement. */
data class TrustChainConstraintCheck(
    val issuer: String,
    val statementIndex: Int,
    val constraint: String,
    val outcome: TrustChainConstraintOutcome,
    val message: String? = null,
    val failureEntityId: String? = null,
    val reason: String? = null,
    val code: String? = null,
)

data class StatementSignatureCheck(val kidFound: Boolean, val signatureValid: Boolean)

data class StatementValidationDiagnostic(val code: String, val message: String)

/** Shared rules for chain verification and resolution diagnostics. */
object TrustChainValidation {
    private val constraintSyntaxOrder = listOf("max_path_length", "allowed_entity_types", "naming_constraints")
    private val constraintValidationOrder = listOf("max_path_length", "naming_constraints", "allowed_entity_types")

    /**
     * Check all present SS constraint parameters, from the anchor toward the subject.
     * The first failed check preserves the verifier's previous failure entity and reason.
     * Unknown parameters are ignored. Allowed entity types filter resolved metadata roles.
     */
    fun validateConstraints(statements: List<Jwt>): List<TrustChainConstraintCheck> = buildList {
        for (j in (statements.size - 1) downTo 0) {
            val statement = statements[j]
            val issuerClaim = stringClaim(statement, "iss")
            val subjectClaim = stringClaim(statement, "sub")
            if (issuerClaim != null && issuerClaim == subjectClaim) continue
            val element = statement.payload["constraints"] ?: continue
            val issuer = issuerClaim ?: "unknown"
            val subject = subjectClaim ?: "unknown"
            val parsed = EntityStatementValidation.parseConstraints(element)
            if (element !is JsonObject) {
                add(failedConstraint(issuer, j, "constraints", subject,
                    "Invalid constraints at position $j: ${parsed.reason ?: "unknown error"}"))
                continue
            }

            val constraints = parsed.constraints
            if (constraints == null) {
                // Syntax validation precedes all semantic checks in the legacy verifier.
                // Preserve that ordering while still describing every present parameter.
                val checks = (constraintSyntaxOrder.filter { it in element } +
                    element.keys.filter { it !in constraintSyntaxOrder }).map { name ->
                    if (name !in constraintSyntaxOrder) {
                        ignoredConstraint(issuer, j, name)
                    } else {
                        val field = EntityStatementValidation.parseConstraints(JsonObject(mapOf(name to element.getValue(name))))
                        if (field.constraints == null) {
                            failedConstraint(issuer, j, name, subject,
                                "Invalid constraints at position $j: ${field.reason ?: "unknown error"}")
                        } else {
                            checkConstraint(statements, j, issuer, subject, name, field.constraints)
                        }
                    }
                }
                val syntaxFailures = checks.filter { it.outcome == TrustChainConstraintOutcome.FAILED &&
                    it.reason?.startsWith("Invalid constraints at position $j:") == true }
                if (syntaxFailures.isNotEmpty()) {
                    addAll(syntaxFailures)
                    addAll(checks.filter { it !in syntaxFailures })
                } else {
                    // Defensive fallback for a model decode failure after known syntax was valid.
                    val first = checks.firstOrNull()
                    if (first == null) {
                        add(failedConstraint(issuer, j, "constraints", subject,
                            "Invalid constraints at position $j: ${parsed.reason ?: "unknown error"}"))
                    } else {
                        add(failedConstraint(issuer, j, first.constraint, subject,
                            "Invalid constraints at position $j: ${parsed.reason ?: "unknown error"}"))
                        addAll(checks.drop(1))
                    }
                }
                continue
            }

            for (name in constraintValidationOrder.filter { it in element }) {
                add(checkConstraint(statements, j, issuer, subject, name, constraints))
            }
            for (name in element.keys.filter { it !in constraintSyntaxOrder }) {
                add(ignoredConstraint(issuer, j, name))
            }
        }
    }

    private fun checkConstraint(
        statements: List<Jwt>,
        position: Int,
        issuer: String,
        subject: String,
        name: String,
        constraints: Constraints,
    ): TrustChainConstraintCheck {
        when (name) {
            "max_path_length" -> {
                val maximum = requireNotNull(constraints.maxPathLength)
                val intermediatesBelow = position - 1
                if (intermediatesBelow > maximum) {
                    return failedConstraint(issuer, position, name, subject,
                        "Constraint violation: max_path_length ($maximum) exceeded " +
                            "at position $position, found $intermediatesBelow intermediates")
                }
                return passedConstraint(issuer, position, name,
                    "$intermediatesBelow intermediates below statement; max_path_length is $maximum")
            }
            "naming_constraints" -> {
                val naming = requireNotNull(constraints.namingConstraints)
                // OIDFed 1.1 §6.2.2: every Subordinate of the issuer in the chain, including the statement's own
                // subject. For an intermediate subject statements[position].sub is not repeated further down.
                for (k in 0..position) {
                    val entitySub = stringClaim(statements[k], "sub") ?: continue
                    if (!NamingConstraintsMatcher.isAllowed(entitySub, naming.permitted, naming.excluded)) {
                        return failedConstraint(issuer, position, name, entitySub,
                            "Constraint violation: entity identifier '$entitySub' at position $k " +
                                "violates naming_constraints at position $position " +
                                "(host='${NamingConstraintsMatcher.extractHost(entitySub)}')")
                    }
                }
                return passedConstraint(issuer, position, name,
                    "The statement subject and all entity identifiers below it satisfy naming_constraints")
            }
            "allowed_entity_types" -> {
                val allowed = requireNotNull(constraints.allowedEntityTypes)
                val removedRoles = (0 until position).flatMap { k ->
                    (statements[k].payload["metadata"] as? JsonObject)?.keys.orEmpty()
                        .filter { !EntityStatementValidation.isEntityTypeAllowed(it, allowed) }
                }.distinct()
                val message = if (removedRoles.isEmpty()) {
                    "allowed_entity_types filters resolved metadata roles; federation_entity is always allowed"
                } else {
                    "allowed_entity_types removes resolved metadata roles ${removedRoles.joinToString()}; " +
                        "federation_entity is always allowed"
                }
                return passedConstraint(issuer, position, name, message)
            }
            else -> return ignoredConstraint(issuer, position, name)
        }
    }

    private fun passedConstraint(issuer: String, position: Int, name: String, message: String) =
        TrustChainConstraintCheck(issuer, position, name, TrustChainConstraintOutcome.PASSED, message)

    private fun ignoredConstraint(issuer: String, position: Int, name: String) =
        TrustChainConstraintCheck(issuer, position, name, TrustChainConstraintOutcome.IGNORED,
            "Unknown constraint parameter '$name' is ignored")

    private fun failedConstraint(issuer: String, position: Int, name: String, entityId: String, reason: String) =
        TrustChainConstraintCheck(issuer, position, name, TrustChainConstraintOutcome.FAILED,
            message = reason, failureEntityId = entityId, reason = reason, code = "CONSTRAINT_VIOLATION")

    /** Exact kid matching uses the first matching key, as in the existing verifier. */
    suspend fun verifyStatementSignature(jwtService: JwtService, jwt: String, keys: List<Jwk>): StatementSignatureCheck {
        val decoded = decodeJWTComponents(jwt)
        val key = keys.find { it.kid == decoded.header.kid }
            ?: return StatementSignatureCheck(kidFound = false, signatureValid = false)
        // Do not turn dependency failures or cancellation into a signature diagnostic.
        return StatementSignatureCheck(kidFound = true, signatureValid = jwtService.verifyJwtSignature(jwt, key))
    }

    /** Collect every structural defect using the same ordered rules as the verifier. */
    fun validateStructureDiagnostics(
        statement: Jwt,
        currentTimeSeconds: Long,
        position: Int,
        understoodCriticalClaims: Set<String> = emptySet(),
    ): List<StatementValidationDiagnostic> = buildList {
        addAll(EntityStatementValidation.structureDiagnostics(statement, currentTimeSeconds, position, understoodCriticalClaims))
        val issuer = stringClaim(statement, "iss")
        val subject = stringClaim(statement, "sub")
        if (position == 0 && issuer != null && subject != null && issuer != subject) {
            add(StatementValidationDiagnostic("ISSUER_SUBJECT_MISMATCH",
                "First statement must have iss == sub (Entity Configuration)"))
        }
    }

    private fun stringClaim(statement: Jwt, name: String): String? =
        (statement.payload[name] as? JsonPrimitive)?.contentOrNull
}
