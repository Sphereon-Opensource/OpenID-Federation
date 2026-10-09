package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.fetchAndVerifyJwt
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.TrustAnchorHints
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.helpers.checkKidInJwks
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.mapper.mapEntityStatement
import com.sphereon.openid.fed.client.services.trustChainService.TrustChainServiceConst
import com.sphereon.core.api.cache.ScopedCache
import kotlinx.coroutines.CancellationException
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.NoTrustChainFoundError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
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
 * Resolves trust chains for entities (OpenID Federation 1.1 §10).
 *
 * - Explores every `authority_hints` path, fetching each Entity Configuration at most once per resolution and never
 *   following a hint back into the current path (§10.1).
 * - Verifies every candidate (§10.2) with the Trust Anchor keys pinned in the arguments, or else with the keys of the
 *   Trust Anchor's own published Entity Configuration.
 * - Returns the preferred valid candidate (§10.3): effective Trust Anchor order, then shortest path. Leaf
 *   `trust_anchor_hints` refine the Trust Anchor order.
 * - Uses a Subordinate Statement's `source_endpoint` when known to skip re-discovering fetch URLs.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ResolveTrustChainCommand>())
class ResolveTrustChainCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val verifyTrustChain: VerifyTrustChainCommand,
    private val trustAnchorKeys: TrustAnchorKeyResolver,
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
        val applied = applyDuring(args)
        val entityIdentifier = applied.entityIdentifier
        val configuredTrustAnchors = applied.trustAnchors
        val startingAuthorityHints = applied.startingAuthorityHints
        val resolution = Resolution(applied.maxDepth)

        return try {
            val leafJwt = resolution.selfConfiguration(entityIdentifier)
            if (startingAuthorityHints != null) {
                leafJwt ?: return IdkResult.err(
                    NoTrustChainFoundError(entityId = entityIdentifier, trustAnchors = configuredTrustAnchors.toList())
                )
                val published = mapEntityStatement(leafJwt, EntityConfigurationStatement::class)?.authorityHints.orEmpty()
                val unpublished = startingAuthorityHints.filterNot { it in published }
                if (startingAuthorityHints.isEmpty() || unpublished.isNotEmpty()) {
                    return IdkResult.err(
                        TrustChainValidationFailedError(
                            entityId = entityIdentifier,
                            reason = "Starting authority_hints must be a non-empty subset of the published " +
                                "authority_hints; not published: ${unpublished.joinToString()}",
                        )
                    )
                }
            }

            // Leaf Entity Configuration trust_anchor_hints (§3.1.2) refine the Trust Anchor preference.
            val publishedHints = leafJwt?.let(TrustAnchorHints::extractFromCompactJwt)
            val trustAnchors = TrustAnchorHints.effectiveTrustAnchors(configuredTrustAnchors, publishedHints)
            logger.info(
                "Resolving trust chain for entity: $entityIdentifier " +
                    "(maxDepth=${applied.maxDepth}, configuredTAs=${configuredTrustAnchors.joinToString()}, " +
                    "effectiveTAs=${trustAnchors.joinToString()}, " +
                    "publishedHints=${publishedHints?.joinToString() ?: "(none)"})"
            )
            if (trustAnchors.isEmpty()) {
                return IdkResult.err(NoTrustChainFoundError(entityId = entityIdentifier, trustAnchors = emptyList()))
            }

            val candidates = mutableListOf<List<String>>()
            if (startingAuthorityHints == null && trustAnchors.contains(entityIdentifier) && leafJwt != null) {
                // The subject is itself a Trust Anchor: its self-signed Entity Configuration is the chain.
                candidates += listOf(leafJwt)
            }
            resolution.collect(
                entityIdentifier = entityIdentifier,
                trustAnchors = trustAnchors,
                prefix = emptyList(),
                path = setOf(entityIdentifier),
                depth = 0,
                out = candidates,
                startingAuthorityHints = startingAuthorityHints,
            )
            if (candidates.isEmpty()) {
                logger.error("Could not establish trust chain for entity: $entityIdentifier")
                return IdkResult.err(NoTrustChainFoundError(entityId = entityIdentifier, trustAnchors = trustAnchors.toList()))
            }
            selectValidChain(entityIdentifier, candidates, trustAnchors, applied.trustAnchorKeys)
        } catch (cancelled: CancellationException) {
            throw cancelled
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

    /** The first candidate in preference order that verifies; otherwise why the most preferred one failed. */
    private suspend fun selectValidChain(
        entityIdentifier: String,
        candidates: List<List<String>>,
        trustAnchors: Array<String>,
        pinnedKeys: Map<String, List<Jwk>>,
    ): IdkResult<TrustChainResolveResponse, FederationError> {
        val now = getCurrentEpochTimeSeconds()
        val publishedKeys = mutableMapOf<String, IdkResult<List<Jwk>, FederationError>>()
        var firstFailure: String? = null
        val ordered = TrustChainTopology.orderPreferredChains(candidates, trustAnchors) { chain -> trustAnchorOfChain(chain) }
        for (chain in ordered) {
            val anchor = trustAnchorOfChain(chain) ?: continue
            val keys = pinnedKeys[anchor] ?: run {
                val resolved = publishedKeys.getOrPut(anchor) { trustAnchorKeys.publishedKeys(anchor, now) }
                if (resolved.isErr) {
                    if (firstFailure == null) firstFailure = resolved.error.message.defaultMessage
                    null
                } else {
                    resolved.value
                }
            } ?: continue
            val verified = verifyTrustChain.verifyTrustChain(chain.toTypedArray(), anchor, now, keys)
            if (verified.isOk && verified.value.isValid) {
                logger.info("Selected trust chain for $entityIdentifier (candidates=${candidates.size}, length=${chain.size}, ta=$anchor)")
                return IdkResult.ok(TrustChainResolveResponse(chain))
            }
            if (firstFailure == null) {
                firstFailure = if (verified.isErr) verified.error.message.defaultMessage else verified.value.errorMessage ?: "Trust Chain is invalid"
            }
        }
        return IdkResult.err(
            TrustChainValidationFailedError(
                entityId = entityIdentifier,
                reason = "None of the ${candidates.size} Trust Chain candidates is valid: ${firstFailure ?: "no Trust Anchor"}",
            )
        )
    }

    /** State of one resolution: every Entity Configuration is fetched and verified at most once. */
    private inner class Resolution(private val maxDepth: Int) {
        private val configurations = mutableMapOf<String, String?>()

        /** The Entity Configuration published at [entityIdentifier], verified as self-signed, or null. */
        suspend fun selfConfiguration(entityIdentifier: String): String? =
            if (configurations.containsKey(entityIdentifier)) {
                configurations[entityIdentifier]
            } else {
                fetchAndVerifySelfEntityConfiguration(entityIdentifier).also { configurations[entityIdentifier] = it }
            }

        /**
         * Depth-first search collecting every path to a [trustAnchors] entry. [path] holds the Entity Identifiers
         * already on the current path; a hint back into it is a loop and is not used (§10.1).
         */
        suspend fun collect(
            entityIdentifier: String,
            trustAnchors: Array<String>,
            prefix: List<String>,
            path: Set<String>,
            depth: Int,
            out: MutableList<List<String>>,
            startingAuthorityHints: List<String>? = null,
        ) {
            if (depth >= maxDepth) {
                logger.debug("Maximum depth reached ($maxDepth) at $entityIdentifier")
                return
            }
            val entityConfigurationJwt = selfConfiguration(entityIdentifier) ?: return
            val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)
            val entityStatement = mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: return
            val chainSoFar = prefix.ifEmpty { listOf(entityConfigurationJwt) }

            // An intermediate that is also a Trust Anchor completes a chain here.
            if (prefix.isNotEmpty() && trustAnchors.contains(entityIdentifier)) {
                out.add(chainSoFar + entityConfigurationJwt)
            }

            val authorityHints = if (prefix.isEmpty() && startingAuthorityHints != null) {
                entityStatement.authorityHints.orEmpty().filter { it in startingAuthorityHints }
            } else {
                entityStatement.authorityHints
            }
            if (authorityHints.isNullOrEmpty()) {
                logger.debug("No authority_hints for $entityIdentifier")
                return
            }
            for (authority in authorityHints.sortedBy { hint -> if (trustAnchors.contains(hint)) 0 else 1 }) {
                if (authority in path) {
                    logger.debug("authority_hint $authority of $entityIdentifier loops back into the path; not used")
                    continue
                }
                follow(authority, entityIdentifier, trustAnchors, chainSoFar, decodedEntityConfiguration.header.kid, path, depth + 1, out)
            }
        }

        private suspend fun follow(
            authority: String,
            subjectEntityId: String,
            trustAnchors: Array<String>,
            chainPrefix: List<String>,
            lastStatementKid: String,
            path: Set<String>,
            depth: Int,
            out: MutableList<List<String>>,
        ) {
            try {
                val authorityConfigurationJwt = selfConfiguration(authority) ?: return
                val authorityEntityConfiguration =
                    mapEntityStatement(authorityConfigurationJwt, EntityConfigurationStatement::class) ?: return
                val (subordinateStatementJwt, _) = fetchAndVerifySubordinateStatement(
                    authority = authority,
                    authorityEntityConfiguration = authorityEntityConfiguration,
                    authorityConfigurationJwt = authorityConfigurationJwt,
                    subjectEntityId = subjectEntityId,
                    lastStatementKid = lastStatementKid,
                ) ?: return
                val extended = chainPrefix + subordinateStatementJwt
                if (trustAnchors.contains(authority)) {
                    out.add(extended + authorityConfigurationJwt)
                    return
                }
                if (authorityEntityConfiguration.authorityHints.isNullOrEmpty()) {
                    logger.debug("Authority $authority has no authority_hints and is not a trust anchor")
                    return
                }
                collect(authority, trustAnchors, extended, path + authority, depth, out)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("Failed to process authority: $authority", e)
            }
        }
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.debug("Failed to fetch EC for $entityIdentifier: ${e.message}")
            null
        }
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
