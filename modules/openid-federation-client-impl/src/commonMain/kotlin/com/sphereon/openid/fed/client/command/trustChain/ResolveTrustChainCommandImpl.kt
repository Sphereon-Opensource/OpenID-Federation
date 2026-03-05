package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.Log
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.crypto.fetchAndVerifyJwt
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.client.helpers.checkKidInJwks
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.mapper.mapEntityStatement
import com.sphereon.openid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementServiceConst
import com.sphereon.openid.fed.client.services.trustChainService.TrustChainServiceConst
import com.sphereon.openid.fed.core.cache.ScopedCache
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.NoTrustChainFoundError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Implementation of the ResolveTrustChainCommand.
 * Resolves trust chains for entities according to the OpenID Federation specification.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveTrustChainCommand::class)
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
        val (entityIdentifier, trustAnchors, maxDepth) = applyDuring(args)

        logger.info("Resolving trust chain for entity: $entityIdentifier with max depth: $maxDepth")
        val chain: MutableList<String> = arrayListOf()

        return try {
            val trustChain = buildTrustChain(entityIdentifier, trustAnchors, chain, 0, maxDepth)
            if (trustChain != null) {
                logger.info(
                    "Successfully resolved trust chain for entity: $entityIdentifier",
                    metadata = mapOf("trustChain" to trustChain.toString())
                )
                IdkResult.ok(TrustChainResolveResponse(trustChain, errorMessage = null))
            } else {
                logger.error("Could not establish trust chain for entity: $entityIdentifier")
                IdkResult.ok(TrustChainResolveResponse(emptyList(), errorMessage = "A Trust chain could not be established"))
            }
        } catch (e: Throwable) {
            logger.error("Trust chain resolution failed for entity: $entityIdentifier", e)
            IdkResult.ok(TrustChainResolveResponse(emptyList(), errorMessage = e.message))
        }
    }

    private suspend fun markAuthorityProcessed(authorityConfigurationEndpoint: String, jwt: String) {
        trustChainCache?.putApp(authorityConfigurationEndpoint, jwt)
    }

    private suspend fun buildTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        depth: Int,
        maxDepth: Int
    ): List<String>? {
        logger.debug("Building trust chain for entity: $entityIdentifier at depth: $depth")
        if (depth == maxDepth) {
            logger.debug("Maximum depth reached: $maxDepth")
            return null
        }

        val entityConfigurationEndpoint = getEntityConfigurationEndpoint(entityIdentifier)
        logger.debug("Fetching entity configuration from: $entityConfigurationEndpoint")
        val entityConfigurationJwt = context.jwtService.fetchAndVerifyJwt(entityConfigurationEndpoint, context.httpResolver)
        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)
        logger.debug("Decoded entity configuration JWT header kid: ${decodedEntityConfiguration.header.kid}")

        val jwks: Array<Jwk> =
            context.json.decodeFromString(
                decodedEntityConfiguration.payload["jwks"]?.jsonObject?.get("keys").toString()
            )

        val key = jwks.find { it.kid == decodedEntityConfiguration.header.kid } ?: run {
            logger.debug("No JWKS found in entity configuration payload")
            return null
        }

        if (!context.jwtService.verifyJwtSignature(entityConfigurationJwt, key)) {
            logger.error("Entity configuration JWT signature verification failed")
            return null
        }

        val entityStatement: EntityConfigurationStatement =
            mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: run {
                logger.debug("Could not map JWT to EntityConfigurationStatement")
                return null
            }

        if (chain.isEmpty()) {
            logger.debug("Adding entity configuration JWT to empty chain")
            chain.add(entityConfigurationJwt)
        }

        val authorityHints = entityStatement.authorityHints ?: run {
            logger.debug("No authority hints found in entity statement")
            return null
        }

        logger.debug("Processing ${authorityHints.size} authority hints")
        val reorderedAuthorityHints = authorityHints.sortedBy { hint ->
            if (trustAnchors.contains(hint)) 0 else 1
        }

        for (authority in reorderedAuthorityHints) {
            logger.debug("Processing authority: $authority")
            val result = processAuthority(
                authority,
                entityIdentifier,
                trustAnchors,
                chain,
                decodedEntityConfiguration.header.kid,
                depth + 1,
                maxDepth
            )

            if (result != null) {
                logger.debug("Successfully built trust chain through authority: $authority")
                return result
            }
            logger.debug("Failed to build trust chain through authority: $authority, trying next authority")
        }

        logger.debug("Could not build trust chain through any authority")
        return null
    }

    private suspend fun processAuthority(
        authority: String,
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        lastStatementKid: String,
        depth: Int,
        maxDepth: Int
    ): MutableList<String>? {
        logger.debug("Processing authority: $authority for entity: $entityIdentifier at depth: $depth")
        try {
            val (authorityEntityConfigurationJwt, authorityEntityConfiguration) = fetchAndVerifyAuthorityConfiguration(
                authority
            ) ?: run {
                logger.debug("Failed to fetch and verify authority configuration for: $authority")
                return null
            }

            val authorityEntityFetchEndpoint = getAuthorityFetchEndpoint(authorityEntityConfiguration) ?: run {
                logger.debug("No federation fetch endpoint found in authority configuration for: $authority")
                return null
            }
            logger.debug("Found authority fetch endpoint: $authorityEntityFetchEndpoint")

            val (subordinateStatementJwt, subordinateStatement) = fetchAndVerifySubordinateStatement(
                authorityEntityFetchEndpoint,
                entityIdentifier,
                authorityEntityConfigurationJwt,
                lastStatementKid
            ) ?: run {
                logger.debug("Failed to fetch and verify subordinate statement from authority: $authority")
                return null
            }

            if (trustAnchors.contains(authority)) {
                logger.debug("Authority $authority is a trust anchor, completing chain")
                return completeChainWithAuthority(chain, subordinateStatementJwt, authorityEntityConfigurationJwt)
            }

            logger.debug("Authority $authority is not a trust anchor, processing its authority hints")
            return processAuthorityHints(
                authorityEntityConfiguration,
                authority,
                trustAnchors,
                chain,
                subordinateStatementJwt,
                depth,
                maxDepth
            )
        } catch (e: Exception) {
            logger.error("Failed to process authority: $authority", e)
            return null
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
            markAuthorityProcessed(authorityConfigurationEndpoint, jwt)
            jwt
        }

        val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)

        val jwks: Array<Jwk> =
            context.json.decodeFromString(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys").toString()
            )

        context.logger.debug("Decoded jwks: $jwks")

        val key = jwks.find { it.kid == decodedJwt.header.kid }
            ?: throw IllegalStateException("No matching key found for kid: $decodedJwt.header.kid")

        if (!context.jwtService.verifyJwtSignature(authorityEntityConfigurationJwt, key)) {
            throw IllegalStateException("Authority configuration JWT signature verification failed")
        }

        val authorityEntityConfiguration = mapEntityStatement(
            authorityEntityConfigurationJwt,
            EntityConfigurationStatement::class
        ) ?: return null

        return Pair(authorityEntityConfigurationJwt, authorityEntityConfiguration)
    }

    private fun getAuthorityFetchEndpoint(
        authorityEntityConfiguration: EntityConfigurationStatement
    ): String? {
        val federationEntityMetadata = authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
        if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return null

        return federationEntityMetadata["federation_fetch_endpoint"]?.jsonPrimitive?.content
    }

    private suspend fun fetchAndVerifySubordinateStatement(
        authorityEntityFetchEndpoint: String,
        entityIdentifier: String,
        authorityConfigurationJwt: String,
        lastStatementKid: String
    ): Pair<String, SubordinateStatement>? {
        val decodedAuthorityConfiguration = decodeJWTComponents(authorityConfigurationJwt)

        val subordinateStatementEndpoint =
            getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)

        val subordinateStatementJwt = context.httpResolver.get(subordinateStatementEndpoint)
        val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

        val subordinateStatementJwks: Array<Jwk> =
            context.json.decodeFromString(
                decodedAuthorityConfiguration.payload["jwks"]?.jsonObject?.get("keys").toString()
            )

        context.logger.debug("Decoded subordinateStatementJwks: $subordinateStatementJwks")

        val subordinateStatementKey = subordinateStatementJwks.find { it.kid == decodedSubordinateStatement.header.kid }
            ?: return null

        if (!context.jwtService.verifyJwtSignature(subordinateStatementJwt, subordinateStatementKey)) {
            return null
        }

        val subordinateStatement = mapEntityStatement(
            subordinateStatementJwt,
            SubordinateStatement::class
        ) ?: return null

        val jwks = subordinateStatement.jwks
        val keys = jwks.propertyKeys ?: return null
        if (!checkKidInJwks(keys, lastStatementKid)) return null
        return Pair(subordinateStatementJwt, subordinateStatement)
    }

    private fun completeChainWithAuthority(
        chain: MutableList<String>,
        subordinateStatementJwt: String,
        authorityEntityConfigurationJwt: String
    ): MutableList<String> {
        chain.add(subordinateStatementJwt)
        chain.add(authorityEntityConfigurationJwt)
        return chain
    }

    private suspend fun processAuthorityHints(
        authorityEntityConfiguration: EntityConfigurationStatement,
        authority: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        subordinateStatementJwt: String,
        depth: Int,
        maxDepth: Int
    ): MutableList<String>? {
        if (authorityEntityConfiguration.authorityHints?.isNotEmpty() == true) {
            chain.add(subordinateStatementJwt)
            val result = buildTrustChain(authority, trustAnchors, chain, depth, maxDepth)
            if (result != null) return result.toMutableList()
            chain.removeLast()
        }
        return null
    }
}
