package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.fetchAndVerifyJwt
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.TrustAnchorHints
import com.sphereon.openid.fed.client.helpers.checkKidInJwks
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.mapper.mapEntityStatement
import com.sphereon.openid.fed.client.services.trustChainService.TrustChainServiceConst
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.NoTrustChainFoundError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Resolves trust chains for entities (OIDFed 1.1 §4 / §9 / §10.3).
 *
 * - Explores **all** authority_hints paths (not first-success only).
 * - Uses leaf Entity Configuration `trust_anchor_hints` to refine Trust Anchor preference
 *   (fallback when caller supplies none; otherwise reorder configured TAs).
 * - Selects a preferred chain: effective Trust Anchor order, then shortest path.
 * - Uses Subordinate Statement `source_endpoint` when known to skip re-discovering fetch URLs.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveTrustChainCommand>())
class ResolveTrustChainCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext
) : ExecutionScopedCommandAdapter<ResolveTrustChainArgs, TrustChainResolveResponse, FederationError>(
    id = ResolveTrustChainCommand.COMMAND_ID,
    execution = execution
), ResolveTrustChainCommand {

    private val logger = TrustChainServiceConst.LOG
    private val trustChainCache: ScopedCache<String, String>? = context.trustChainCache

    override suspend fun resolveTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): IdkResult<TrustChainResolveResponse, FederationError> {
        return execute(
            ResolveTrustChainArgs(entityIdentifier, trustAnchors, maxDepth)
        )
    }

    override suspend fun doExecute(
        args: ResolveTrustChainArgs,
        applyDuring: (ResolveTrustChainArgs) -> ResolveTrustChainArgs
    ): IdkResult<TrustChainResolveResponse, FederationError> {
        val (entityIdentifier, configuredTrustAnchors, maxDepth) = applyDuring(args)

        return try {
            // Apply leaf EC trust_anchor_hints (OIDFed 1.1 §3.1.2) before graph walk / selection
            val publishedHints = fetchLeafTrustAnchorHints(entityIdentifier)
            val trustAnchors = TrustAnchorHints.effectiveTrustAnchors(configuredTrustAnchors, publishedHints)

            logger.info(
                "Resolving trust chain for entity: $entityIdentifier " +
                    "(maxDepth=$maxDepth, configuredTAs=${configuredTrustAnchors.joinToString()}, " +
                    "effectiveTAs=${trustAnchors.joinToString()}, " +
                    "publishedHints=${publishedHints?.joinToString() ?: "(none)"})"
            )

            if (trustAnchors.isEmpty()) {
                return IdkResult.err(
                    NoTrustChainFoundError(
                        entityId = entityIdentifier,
                        trustAnchors = emptyList(),
                    )
                )
            }

            // Special case: entity itself is a configured Trust Anchor → single-EC chain
            if (trustAnchors.contains(entityIdentifier)) {
                val taEc = fetchAndVerifySelfEntityConfiguration(entityIdentifier)
                if (taEc != null) {
                    logger.info("Entity $entityIdentifier is a Trust Anchor; returning self-signed EC chain")
                    return IdkResult.ok(TrustChainResolveResponse(listOf(taEc)))
                }
            }

            val candidates = mutableListOf<List<String>>()
            collectTrustChains(
                entityIdentifier = entityIdentifier,
                trustAnchors = trustAnchors,
                prefix = emptyList(),
                depth = 0,
                maxDepth = maxDepth,
                out = candidates,
            )

            val selected = TrustChainTopology.selectPreferredChain(
                candidates = candidates,
                preferredTrustAnchors = trustAnchors,
                trustAnchorOf = { chain -> trustAnchorOfChain(chain) },
            )

            if (selected != null) {
                logger.info(
                    "Selected trust chain for $entityIdentifier " +
                        "(candidates=${candidates.size}, length=${selected.size}, " +
                        "ta=${trustAnchorOfChain(selected)})"
                )
                IdkResult.ok(TrustChainResolveResponse(selected))
            } else {
                logger.error("Could not establish trust chain for entity: $entityIdentifier")
                IdkResult.err(
                    NoTrustChainFoundError(
                        entityId = entityIdentifier,
                        trustAnchors = trustAnchors.toList(),
                    )
                )
            }
        } catch (e: Throwable) {
            logger.error("Trust chain resolution failed for entity: $entityIdentifier", e)
            IdkResult.err(
                NoTrustChainFoundError(
                    entityId = entityIdentifier,
                    trustAnchors = configuredTrustAnchors.toList(),
                    exception = e,
                )
            )
        }
    }

    /**
     * Depth-first search collecting **all** successful paths to any [trustAnchors] entry.
     * Each path is independent (prefix is copied when extending).
     */
    private suspend fun collectTrustChains(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        prefix: List<String>,
        depth: Int,
        maxDepth: Int,
        out: MutableList<List<String>>,
    ) {
        if (depth >= maxDepth) {
            logger.debug("Maximum depth reached ($maxDepth) at $entityIdentifier")
            return
        }

        val entityConfigurationJwt = fetchAndVerifySelfEntityConfiguration(entityIdentifier) ?: return
        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)
        val entityStatement = mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class)
            ?: return

        val chainSoFar = if (prefix.isEmpty()) {
            listOf(entityConfigurationJwt)
        } else {
            prefix
        }

        val lastKid = decodedEntityConfiguration.header.kid

        // Direct Trust Anchor membership of this entity (intermediate that is also a TA)
        if (prefix.isNotEmpty() && trustAnchors.contains(entityIdentifier)) {
            // prefix already ends with SS about this entity; append TA EC
            out.add(chainSoFar + entityConfigurationJwt)
            // still explore superiors if any (rare multi-federation TA)
        }

        val authorityHints = entityStatement.authorityHints
        if (authorityHints.isNullOrEmpty()) {
            logger.debug("No authority_hints for $entityIdentifier")
            return
        }

        // Prefer configured TAs first when exploring (faster common path) but still visit all
        val reordered = authorityHints.sortedBy { hint -> if (trustAnchors.contains(hint)) 0 else 1 }

        for (authority in reordered) {
            processAuthorityPaths(
                authority = authority,
                subjectEntityId = entityIdentifier,
                trustAnchors = trustAnchors,
                chainPrefix = chainSoFar,
                lastStatementKid = lastKid,
                depth = depth + 1,
                maxDepth = maxDepth,
                out = out,
            )
        }
    }

    private suspend fun processAuthorityPaths(
        authority: String,
        subjectEntityId: String,
        trustAnchors: Array<String>,
        chainPrefix: List<String>,
        lastStatementKid: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<List<String>>,
    ) {
        try {
            val (authorityEntityConfigurationJwt, authorityEntityConfiguration) =
                fetchAndVerifyAuthorityConfiguration(authority) ?: return

            val subordinatePair = fetchAndVerifySubordinateStatement(
                authority = authority,
                authorityEntityConfiguration = authorityEntityConfiguration,
                authorityConfigurationJwt = authorityEntityConfigurationJwt,
                subjectEntityId = subjectEntityId,
                lastStatementKid = lastStatementKid,
            ) ?: return

            val (subordinateStatementJwt, _) = subordinatePair
            val extended = chainPrefix + subordinateStatementJwt

            if (trustAnchors.contains(authority)) {
                // Complete with Trust Anchor Entity Configuration
                out.add(extended + authorityEntityConfigurationJwt)
                return
            }

            // Continue toward superiors of this intermediate
            if (authorityEntityConfiguration.authorityHints.isNullOrEmpty()) {
                logger.debug("Authority $authority has no authority_hints and is not a trust anchor")
                return
            }
            collectTrustChains(
                entityIdentifier = authority,
                trustAnchors = trustAnchors,
                prefix = extended,
                depth = depth,
                maxDepth = maxDepth,
                out = out,
            )
        } catch (e: Exception) {
            logger.error("Failed to process authority: $authority", e)
        }
    }

    /**
     * Load leaf Entity Configuration and read published `trust_anchor_hints`, if any.
     */
    private suspend fun fetchLeafTrustAnchorHints(entityIdentifier: String): List<String>? {
        val leafJwt = fetchAndVerifySelfEntityConfiguration(entityIdentifier) ?: return null
        return TrustAnchorHints.extractFromCompactJwt(leafJwt)
    }

    private suspend fun fetchAndVerifySelfEntityConfiguration(entityIdentifier: String): String? {
        val endpoint = getEntityConfigurationEndpoint(entityIdentifier)
        return try {
            val jwt = context.jwtService.fetchAndVerifyJwt(endpoint, context.httpResolver)
            val decoded = decodeJWTComponents(jwt)
            val jwks: Array<Jwk> =
                context.json.decodeFromString(decoded.payload["jwks"]?.jsonObject?.get("keys").toString())
            val key = jwks.find { it.kid == decoded.header.kid } ?: return null
            if (!context.jwtService.verifyJwtSignature(jwt, key)) return null
            jwt
        } catch (e: Exception) {
            logger.debug("Failed to fetch EC for $entityIdentifier: ${e.message}")
            null
        }
    }

    private suspend fun fetchAndVerifyAuthorityConfiguration(
        authority: String
    ): Pair<String, EntityConfigurationStatement>? {
        val authorityConfigurationEndpoint = getEntityConfigurationEndpoint(authority)

        val cachedJwt = trustChainCache?.getApp(authorityConfigurationEndpoint)
        val authorityEntityConfigurationJwt = if (cachedJwt != null) {
            logger.debug("Authority $authority already fetched, using cached configuration")
            cachedJwt
        } else {
            val jwt = context.jwtService.fetchAndVerifyJwt(authorityConfigurationEndpoint, context.httpResolver)
            trustChainCache?.putApp(authorityConfigurationEndpoint, jwt)
            jwt
        }

        val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)
        val jwks: Array<Jwk> =
            context.json.decodeFromString(decodedJwt.payload["jwks"]?.jsonObject?.get("keys").toString())
        val key = jwks.find { it.kid == decodedJwt.header.kid }
            ?: throw IllegalStateException("No matching key found for kid: ${decodedJwt.header.kid}")

        if (!context.jwtService.verifyJwtSignature(authorityEntityConfigurationJwt, key)) {
            throw IllegalStateException("Authority configuration JWT signature verification failed")
        }

        val authorityEntityConfiguration = mapEntityStatement(
            authorityEntityConfigurationJwt,
            EntityConfigurationStatement::class
        ) ?: return null

        return Pair(authorityEntityConfigurationJwt, authorityEntityConfiguration)
    }

    /**
     * Fetch Subordinate Statement with optional `source_endpoint` optimization (§3.1.3 / §8.1).
     * Prefer cached source_endpoint for (issuer, subject); fall back to federation_fetch_endpoint.
     */
    private suspend fun fetchAndVerifySubordinateStatement(
        authority: String,
        authorityEntityConfiguration: EntityConfigurationStatement,
        authorityConfigurationJwt: String,
        subjectEntityId: String,
        lastStatementKid: String,
    ): Pair<String, SubordinateStatement>? {
        val decodedAuthorityConfiguration = decodeJWTComponents(authorityConfigurationJwt)

        val fetchFromEc = getAuthorityFetchEndpoint(authorityEntityConfiguration)
        val cachedSourceBase = trustChainCache?.getApp(sourceEndpointCacheKey(authority, subjectEntityId))
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }

        // Prefer source_endpoint (refresh optimization), then federation_fetch_endpoint from EC
        val fetchBases = buildList {
            if (cachedSourceBase != null) add(cachedSourceBase)
            if (!fetchFromEc.isNullOrBlank()) add(fetchFromEc.substringBefore('?'))
        }.distinct()

        if (fetchBases.isEmpty()) {
            logger.debug("No fetch endpoint for authority $authority")
            return null
        }

        val authorityJwks: Array<Jwk> =
            context.json.decodeFromString(
                decodedAuthorityConfiguration.payload["jwks"]?.jsonObject?.get("keys").toString()
            )

        for (base in fetchBases) {
            val endpoint = getSubordinateStatementEndpoint(base, subjectEntityId)
            try {
                val subordinateStatementJwt = context.httpResolver.get(endpoint)
                val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

                val subordinateStatementKey =
                    authorityJwks.find { it.kid == decodedSubordinateStatement.header.kid }
                        ?: continue

                if (!context.jwtService.verifyJwtSignature(subordinateStatementJwt, subordinateStatementKey)) {
                    continue
                }

                val subordinateStatement = mapEntityStatement(
                    subordinateStatementJwt,
                    SubordinateStatement::class
                ) ?: continue

                val keys = subordinateStatement.jwks.propertyKeys ?: continue
                if (!checkKidInJwks(keys, lastStatementKid)) continue

                // Prefer claim source_endpoint; else remember the working fetch base for refresh
                val sourceFromClaim = decodedSubordinateStatement.payload["source_endpoint"]
                    ?.jsonPrimitive?.contentOrNull
                    ?.substringBefore('?')
                    ?.takeIf { it.isNotBlank() }
                val toCache = sourceFromClaim ?: base
                trustChainCache?.putApp(sourceEndpointCacheKey(authority, subjectEntityId), toCache)
                if (sourceFromClaim != null) {
                    logger.debug("Cached source_endpoint for $authority → $subjectEntityId")
                }

                return Pair(subordinateStatementJwt, subordinateStatement)
            } catch (e: Exception) {
                logger.debug("Fetch SS failed from $endpoint: ${e.message}")
            }
        }
        return null
    }

    private fun getAuthorityFetchEndpoint(
        authorityEntityConfiguration: EntityConfigurationStatement
    ): String? {
        val federationEntityMetadata =
            authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
                ?: return null
        return federationEntityMetadata["federation_fetch_endpoint"]?.jsonPrimitive?.content
    }

    private fun sourceEndpointCacheKey(issuer: String, subject: String): String =
        "oidf:source_endpoint:$issuer|$subject"

    /**
     * Trust Anchor Entity Identifier for a completed chain (last EC iss, or last SS iss if TA EC omitted).
     */
    private fun trustAnchorOfChain(chain: List<String>): String? {
        if (chain.isEmpty()) return null
        return try {
            val last = decodeJWTComponents(chain.last())
            val iss = last.payload["iss"]?.jsonPrimitive?.contentOrNull
            val sub = last.payload["sub"]?.jsonPrimitive?.contentOrNull
            if (iss != null && iss == sub) iss else iss
        } catch (_: Exception) {
            null
        }
    }
}
