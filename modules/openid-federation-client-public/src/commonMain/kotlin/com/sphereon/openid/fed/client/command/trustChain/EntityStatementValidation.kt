package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.Constraints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.floor
import kotlin.math.nextDown

/**
 * Structural Entity Statement checks from OpenID Federation 1.1 §3.2 / §10.2
 * that do not require network or crypto.
 */
object EntityStatementValidation {

    const val ENTITY_STATEMENT_TYP = "entity-statement+jwt"
    private const val CLOCK_SKEW_SECONDS = 5L

    /** Claims that MUST NOT appear in Entity Configurations (SS-only). */
    private val SUBORDINATE_ONLY_CLAIMS = setOf(
        "constraints",
        "metadata_policy",
        "metadata_policy_crit",
        "source_endpoint"
    )

    /** Claims that MUST NOT appear in Subordinate Statements (EC-only). */
    private val ENTITY_CONFIGURATION_ONLY_CLAIMS = setOf(
        "authority_hints",
        "trust_anchor_hints",
        "trust_marks",
        "trust_mark_issuers",
        "trust_mark_owners"
    )

    /** Standard Entity Statement claims that MUST NOT appear in `crit`. */
    private val STANDARD_CLAIMS = setOf(
        "iss", "sub", "iat", "exp", "jwks", "metadata", "crit",
        "authority_hints", "trust_anchor_hints", "trust_marks",
        "trust_mark_issuers", "trust_mark_owners",
        "constraints", "metadata_policy", "metadata_policy_crit", "source_endpoint"
    )

    data class StructuralResult(
        val ok: Boolean,
        val reason: String? = null
    )

    data class ConstraintsResult(
        val constraints: Constraints? = null,
        val reason: String? = null
    )

    /** Validate known constraint syntax before decoding nullable generated model fields. */
    fun parseConstraints(element: JsonElement): ConstraintsResult {
        if (element !is JsonObject) {
            return ConstraintsResult(reason = "constraints must be a JSON object")
        }

        element["max_path_length"]?.let { value ->
            val primitive = value as? JsonPrimitive
            val count = primitive?.takeUnless { it.isString }?.intOrNull
            if (count == null || count < 0) {
                return ConstraintsResult(reason = "max_path_length must be a non-negative integer")
            }
        }

        element["allowed_entity_types"]?.let { value ->
            if (value !is JsonArray) {
                return ConstraintsResult(reason = "allowed_entity_types must be an array of strings")
            }
            for (entry in value) {
                val primitive = entry as? JsonPrimitive
                if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
                    return ConstraintsResult(reason = "allowed_entity_types entries must be non-empty strings")
                }
                if (primitive.content == "federation_entity") {
                    return ConstraintsResult(reason = "allowed_entity_types must not explicitly list federation_entity")
                }
            }
        }

        element["naming_constraints"]?.let { value ->
            if (value !is JsonObject) {
                return ConstraintsResult(reason = "naming_constraints must be a JSON object")
            }
            for (name in listOf("permitted", "excluded")) {
                value[name]?.let { entries ->
                    if (entries !is JsonArray || entries.any { item ->
                            val primitive = item as? JsonPrimitive
                            primitive == null || !primitive.isString
                        }) {
                        return ConstraintsResult(reason = "naming_constraints.$name must be an array of strings")
                    }
                }
            }
        }

        return try {
            ConstraintsResult(constraints = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<Constraints>(element))
        } catch (e: Exception) {
            ConstraintsResult(reason = "Invalid constraints: ${e.message}")
        }
    }

    fun isEntityConfiguration(payload: JsonObject): Boolean {
        val iss = payload["iss"]?.jsonPrimitive?.contentOrNull
        val sub = payload["sub"]?.jsonPrimitive?.contentOrNull
        return iss != null && iss == sub
    }

    fun isSubordinateStatement(payload: JsonObject): Boolean {
        val iss = payload["iss"]?.jsonPrimitive?.contentOrNull
        val sub = payload["sub"]?.jsonPrimitive?.contentOrNull
        return iss != null && sub != null && iss != sub
    }

    /**
     * Validate header + required claims + claim placement + crit + timestamps.
     *
     * @param understoodCriticalClaims non-standard claim names this deployment processes
     *   (OIDFed 1.1 §3.2 step 13). Any other `crit` entry fails closed.
     */
    fun validateStructure(
        statement: Jwt,
        currentTimeSeconds: Long,
        position: Int,
        understoodCriticalClaims: Set<String> = emptySet(),
    ): StructuralResult {
        val typ = statement.header.typ
        if (typ != ENTITY_STATEMENT_TYP) {
            return StructuralResult(
                false,
                "Statement at position $position typ must be '$ENTITY_STATEMENT_TYP', got '${typ ?: "(missing)"}'"
            )
        }

        if (statement.header.trustChain != null) {
            return StructuralResult(false, "Statement at position $position must not contain trust_chain header")
        }
        if (statement.header.peerTrustChain != null) {
            return StructuralResult(false, "Statement at position $position must not contain peer_trust_chain header")
        }

        val alg = statement.header.alg
        if (alg.isBlank() || alg.equals("none", ignoreCase = true)) {
            return StructuralResult(
                false,
                "Statement at position $position alg must be a signing algorithm and MUST NOT be 'none'"
            )
        }

        if (statement.header.kid.isBlank()) {
            return StructuralResult(false, "Statement at position $position missing kid header")
        }

        val payload = statement.payload
        for (claim in listOf("iss", "sub", "iat", "exp", "jwks")) {
            if (payload[claim] == null) {
                return StructuralResult(false, "Statement at position $position missing required claim '$claim'")
            }
        }

        val iss = payload["iss"]?.jsonPrimitive?.contentOrNull
        val sub = payload["sub"]?.jsonPrimitive?.contentOrNull
        if (iss.isNullOrBlank() || sub.isNullOrBlank()) {
            return StructuralResult(false, "Statement at position $position has empty iss or sub")
        }

        val isEc = iss == sub
        if (isEc) {
            for (claim in SUBORDINATE_ONLY_CLAIMS) {
                if (payload.containsKey(claim)) {
                    return StructuralResult(
                        false,
                        "Entity Configuration at position $position must not contain '$claim'"
                    )
                }
            }
            // authority_hints must not be empty array when present
            val hints = payload["authority_hints"]
            if (hints is JsonArray && hints.isEmpty()) {
                return StructuralResult(
                    false,
                    "Entity Configuration at position $position authority_hints must not be empty array"
                )
            }
            // trust_anchor_hints: EC-only; when present MUST be non-empty array of Entity Identifiers
            // (OIDFed 1.1 §3.1.2 / validation step 15)
            val taHints = payload["trust_anchor_hints"]
            if (taHints != null) {
                if (taHints !is JsonArray || taHints.isEmpty()) {
                    return StructuralResult(
                        false,
                        "Entity Configuration at position $position trust_anchor_hints must be a non-empty array when present"
                    )
                }
                for (el in taHints) {
                    val id = el.jsonPrimitive.contentOrNull
                    if (id.isNullOrBlank()) {
                        return StructuralResult(
                            false,
                            "Entity Configuration at position $position trust_anchor_hints entries must be non-empty strings"
                        )
                    }
                }
            }
        } else {
            for (claim in ENTITY_CONFIGURATION_ONLY_CLAIMS) {
                if (payload.containsKey(claim)) {
                    return StructuralResult(
                        false,
                        "Subordinate Statement at position $position must not contain '$claim'"
                    )
                }
            }
        }

        // crit: each name must be a non-standard claim that is understood and processable
        // (OIDFed 1.1 §3.2 step 13 / §13.4) — fail closed for unknown extensions
        val crit = payload["crit"]
        if (crit != null) {
            if (crit !is JsonArray || crit.isEmpty()) {
                return StructuralResult(
                    false,
                    "Statement at position $position crit must be a non-empty array when present"
                )
            }
            for (el in crit) {
                val name = el.jsonPrimitive.contentOrNull
                    ?: return StructuralResult(false, "Statement at position $position crit entry must be a string")
                if (name in STANDARD_CLAIMS) {
                    return StructuralResult(
                        false,
                        "Statement at position $position crit must not list standard claim '$name'"
                    )
                }
                if (name !in understoodCriticalClaims) {
                    return StructuralResult(
                        false,
                        "Statement at position $position has unsupported critical claim '$name' " +
                            "(understood: ${understoodCriticalClaims.ifEmpty { setOf("(none)") }})"
                    )
                }
                // Claim must actually be present when listed as critical
                if (!payload.containsKey(name)) {
                    return StructuralResult(
                        false,
                        "Statement at position $position critical claim '$name' is listed in crit but missing from payload"
                    )
                }
            }
        }

        val iat = numericDate(payload["iat"])
        val now = currentTimeSeconds.toDouble()
        val skew = CLOCK_SKEW_SECONDS.toDouble()
        if (iat == null || iat > now + skew) {
            return StructuralResult(false, "Statement at position $position has invalid or future iat")
        }
        val exp = numericDate(payload["exp"])
        if (exp == null || exp <= now - skew) {
            return StructuralResult(false, "Statement at position $position has expired exp")
        }

        return StructuralResult(true)
    }

    private fun numericDate(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Shorten a signed NumericDate to a representable whole-second evidence lifetime. */
    fun conservativeExpiryEpochSeconds(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null

        // Integral JSON tokens retain their exact value, including both Long endpoints.
        primitive.content.toLongOrNull()?.let { return it }

        val seconds = numericDate(value) ?: return null
        val lowerBound = Long.MIN_VALUE.toDouble()
        val upperExclusive = -lowerBound // 2^63; Long.MAX_VALUE.toDouble() rounds here too.
        if (seconds <= lowerBound || seconds >= upperExclusive) return null

        // Conversion can round a decimal upward, so step toward -infinity before flooring.
        val conservative = floor(seconds.nextDown())
        if (!conservative.isFinite() || conservative <= lowerBound || conservative >= upperExclusive) return null
        return conservative.toLong()
    }

    /**
     * Leaf EC authority_hints must include the issuer of the immediate superior Subordinate Statement.
     */
    fun validateAuthorityHintsLink(
        subjectEntityConfiguration: Jwt,
        superiorSubordinateStatement: Jwt
    ): StructuralResult {
        if (!isEntityConfiguration(subjectEntityConfiguration.payload)) {
            return StructuralResult(true) // only check when we have the subject's EC
        }
        val superiorIss = superiorSubordinateStatement.payload["iss"]?.jsonPrimitive?.contentOrNull
            ?: return StructuralResult(false, "Superior Subordinate Statement missing iss")
        val hints = subjectEntityConfiguration.payload["authority_hints"]
        if (hints !is JsonArray || hints.isEmpty()) {
            return StructuralResult(
                false,
                "Subject Entity Configuration missing authority_hints for superior '$superiorIss'"
            )
        }
        val hintList = hints.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (superiorIss !in hintList) {
            return StructuralResult(
                false,
                "Superior '$superiorIss' is not listed in subject's authority_hints"
            )
        }
        return StructuralResult(true)
    }

    /**
     * `allowed_entity_types`: `federation_entity` is always allowed; empty list means only that type.
     */
    fun isEntityTypeAllowed(entityType: String, allowedEntityTypes: List<String>?): Boolean {
        if (entityType == "federation_entity") return true
        if (allowedEntityTypes == null) return true // no constraint
        if (allowedEntityTypes.isEmpty()) return false // only federation_entity
        return entityType in allowedEntityTypes
    }
}
