package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.openid.fed.client.command.trustChain.TrustChainValidation
import com.sphereon.openid.fed.client.command.trustChain.StatementSignatureCheck
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.httpResolver.FederationHttpFetchException
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Jwt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class DiagnosisStatement(
    val issuer: String,
    val subject: String,
    val kind: StatementKind,
    val sourceUrl: String,
    val jwt: String? = null,
    val decoded: Jwt? = null,
    val claims: JsonObject? = decoded?.payload,
    val failure: ResolutionDiagnosticError? = null,
    val rawHeader: JsonObject? = null,
    val issuerSignature: StatementSignatureCheck? = null,
)

internal data class DiagnosisPath(
    val statements: List<DiagnosisStatement>,
    val errors: List<ResolutionDiagnosticError> = emptyList(),
    val reachedAnchor: Boolean = false,
    val depth: Int = 0,
)

/** Discovery state belongs to a single invocation, including failed HTTP fetches. */
internal class DiagnosisPathResolver(
    private val context: FederationContext,
    private val args: DiagnoseResolutionArgs,
    private val now: Long,
) {
    private val configurations = mutableMapOf<String, DiagnosisStatement>()
    private val subordinateStatements = mutableMapOf<Pair<String, String>, DiagnosisStatement>()

    suspend fun configuration(identifier: String): DiagnosisStatement = configurations[identifier] ?: fetch(
        url = getEntityConfigurationEndpoint(identifier),
        issuer = identifier,
        subject = identifier,
        kind = StatementKind.ENTITY_CONFIGURATION,
    ).also { configurations[identifier] = it }

    suspend fun discover(subject: String = args.subject): List<DiagnosisPath> {
        val leaf = configuration(subject)
        // Fetch the exact anchor's own EC even when another part of the path fails.
        configuration(args.trustAnchor)
        val leafErrors = listOfNotNull(leaf.failure?.copy(statementIndex = 0))
        if (leaf.claims == null) return listOf(DiagnosisPath(listOf(leaf), leafErrors))
        if (subject == args.trustAnchor) return listOf(DiagnosisPath(listOf(leaf), leafErrors, reachedAnchor = true))
        val paths = mutableListOf<DiagnosisPath>()
        visit(subject, leaf, listOf(leaf), setOf(subject), 0, leafErrors, paths)
        return paths
    }

    private suspend fun visit(
        identifier: String,
        configuration: DiagnosisStatement,
        prefix: List<DiagnosisStatement>,
        visited: Set<String>,
        depth: Int,
        errors: List<ResolutionDiagnosticError>,
        paths: MutableList<DiagnosisPath>,
    ) {
        val hints = (configuration.claims?.get("authority_hints") as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull
        }.orEmpty()
        if (hints.isEmpty()) {
            paths += DiagnosisPath(prefix, errors + listOf(
                ResolutionDiagnosticError("AUTHORITY_HINT_MISSING", "$identifier has no authority_hint toward ${args.trustAnchor}", prefix.lastIndex),
                ResolutionDiagnosticError("NO_PATH_TO_TRUST_ANCHOR", "No path from $identifier to ${args.trustAnchor}", prefix.lastIndex),
            ), depth = depth)
            return
        }
        if (depth >= args.maxDepth) {
            paths += DiagnosisPath(prefix, errors + ResolutionDiagnosticError(
                "MAX_DEPTH_EXCEEDED", "Maximum path depth ${args.maxDepth} reached at $identifier", prefix.size,
            ), depth = depth)
            return
        }
        for (authority in hints.distinct().sortedBy { if (it == args.trustAnchor) 0 else 1 }) {
            if (authority in visited) {
                paths += DiagnosisPath(prefix, errors + ResolutionDiagnosticError(
                    "LOOP_DETECTED", "authority_hint $authority loops back into the current path", prefix.size,
                ), depth = depth)
                continue
            }
            val superior = this.configuration(authority)
            if (superior.claims == null) {
                val missing = superior.copy(subject = identifier, kind = StatementKind.SUBORDINATE_STATEMENT)
                paths += DiagnosisPath(prefix + missing, errors + listOfNotNull(superior.failure?.copy(statementIndex = prefix.size)), depth = depth + 1)
                continue
            }
            // Intermediate ECs supply endpoints and hints, but are not entries of the returned Trust Chain.
            val configErrors = if (authority == args.trustAnchor) emptyList() else validateConfiguration(superior, prefix.size)
            val endpoint = ((superior.claims?.get("metadata") as? JsonObject)?.get("federation_entity") as? JsonObject)
                ?.string("federation_fetch_endpoint")
            if (endpoint.isNullOrBlank()) {
                paths += DiagnosisPath(prefix, errors + configErrors + ResolutionDiagnosticError(
                    "NO_PATH_TO_TRUST_ANCHOR", "$authority publishes no federation_fetch_endpoint", prefix.size,
                ), depth = depth + 1)
                continue
            }
            val key = authority to identifier
            val fetched = subordinateStatements[key] ?: fetch(
                getSubordinateStatementEndpoint(endpoint.substringBefore('?'), identifier),
                authority, identifier, StatementKind.SUBORDINATE_STATEMENT,
            ).also { subordinateStatements[key] = it }
            val signature = if (fetched.decoded != null) verifyDiagnosticSignature(context,
                requireNotNull(fetched.jwt), keys(superior.claims)) else null
            val subordinate = fetched.copy(issuerSignature = signature)
            val extended = prefix + subordinate
            val signatureErrors = if (signature != null) {
                buildList {
                    if (!signature.kidFound) add(ResolutionDiagnosticError("KID_NOT_FOUND",
                        "Subordinate Statement kid not in $authority Entity Configuration JWKS", prefix.size))
                    if (!signature.signatureValid) add(ResolutionDiagnosticError("SIGNATURE_INVALID",
                        "Subordinate Statement signature failed against $authority Entity Configuration JWKS", prefix.size))
                }
            } else emptyList()
            val pathErrors = errors + configErrors + signatureErrors + listOfNotNull(subordinate.failure?.copy(statementIndex = prefix.size))
            if (authority == args.trustAnchor) {
                paths += DiagnosisPath(extended + superior, pathErrors, reachedAnchor = true, depth = depth + 1)
            } else {
                visit(authority, superior, extended, visited + authority, depth + 1, pathErrors, paths)
            }
        }
    }

    private suspend fun validateConfiguration(statement: DiagnosisStatement, index: Int): List<ResolutionDiagnosticError> {
        val decoded = requireNotNull(statement.forDiagnostics())
        val errors = (listOfNotNull(statement.failure?.copy(statementIndex = index)) +
            TrustChainValidation.validateStructureDiagnostics(decoded, now, index, context.understoodCriticalClaims)
            .map { ResolutionDiagnosticError(it.code, "Entity Configuration ${statement.issuer}: ${it.message}", index) }
        ).toMutableList()
        if (decoded.payload.string("iss") != statement.issuer || decoded.payload.string("sub") != statement.subject) {
            errors += ResolutionDiagnosticError("ISSUER_SUBJECT_MISMATCH", "Entity Configuration identifiers do not match ${statement.issuer}", index)
        }
        val signature = if (statement.decoded != null) verifyDiagnosticSignature(context, requireNotNull(statement.jwt), keys(statement.claims))
            else StatementSignatureCheck(kidFound = decoded.header.kid.isNotBlank(), signatureValid = false)
        if (!signature.kidFound) errors += ResolutionDiagnosticError("KID_NOT_FOUND", "Entity Configuration ${statement.issuer} signing kid not found", index)
        if (!signature.signatureValid) errors += ResolutionDiagnosticError("SIGNATURE_INVALID", "Entity Configuration ${statement.issuer} self-signature is invalid", index)
        return errors
    }

    fun keys(claims: JsonObject?): List<Jwk> {
        val keys = (claims?.get("jwks") as? JsonObject)?.get("keys") as? JsonArray ?: return emptyList()
        return keys.mapNotNull { element ->
            try {
                context.json.decodeFromJsonElement<Jwk>(element)
            } catch (_: kotlinx.serialization.SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private suspend fun fetch(url: String, issuer: String, subject: String, kind: StatementKind): DiagnosisStatement {
        val jwt = try {
            context.httpResolver.getJwt(url, bypassCache = args.bypassCache)
        } catch (e: FederationHttpFetchException) {
            return DiagnosisStatement(issuer, subject, kind, url, failure = ResolutionDiagnosticError("FETCH_FAILED", "Could not fetch $url: ${e.message ?: "Fetch failed"}"))
        }
        return try {
            DiagnosisStatement(issuer, subject, kind, url, jwt, decodeJWTComponents(jwt))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosisStatement(issuer, subject, kind, url, jwt, claims = rawObject(jwt, 1), rawHeader = rawObject(jwt, 0), failure = ResolutionDiagnosticError(
                "SIGNATURE_INVALID", "Could not decode Entity Statement from $url: ${e.message}",
            ))
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun rawObject(jwt: String, index: Int): JsonObject? = try {
        val payload = jwt.split('.').getOrNull(index)
        payload?.let { context.json.parseToJsonElement(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it).decodeToString()) as? JsonObject }
    } catch (_: Exception) {
        null
    }
}

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.epoch(name: String): Long? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()

/** Invalid remote key material is a signature diagnostic; dependency faults still escape. */
internal suspend fun verifyDiagnosticSignature(context: FederationContext, jwt: String, keys: List<Jwk>): StatementSignatureCheck = try {
    TrustChainValidation.verifyStatementSignature(context.jwtService, jwt, keys)
} catch (_: kotlinx.serialization.SerializationException) {
    StatementSignatureCheck(kidFound = true, signatureValid = false)
} catch (_: IllegalArgumentException) {
    StatementSignatureCheck(kidFound = true, signatureValid = false)
}
