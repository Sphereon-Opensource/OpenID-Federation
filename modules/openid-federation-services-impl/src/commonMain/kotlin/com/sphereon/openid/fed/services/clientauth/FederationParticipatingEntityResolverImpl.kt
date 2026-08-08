package com.sphereon.openid.fed.services.clientauth

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.core.config.FederationClientAuthMembershipPolicy
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.core.error.UnauthorizedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.persistence.Persistence
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Federation ClientRegistry analogue: membership via subordinate store and/or trust chain,
 * credentials via Entity Configuration Federation Entity Keys.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FederationParticipatingEntityResolver>())
class FederationParticipatingEntityResolverImpl(
    execution: SessionExecution,
    private val federationClient: FederationClient,
) : FederationParticipatingEntityResolver {

    private val logger = execution.federationLogger("FederationParticipatingEntityResolver")
    private val subordinateQueries = Persistence.subordinateQueries

    override suspend fun resolve(
        args: ResolveParticipatingEntityArgs,
    ): FederationResult<FederationParticipatingEntity> {
        val clientId = args.clientEntityId.trim()
        if (clientId.isEmpty()) {
            return federationErr(UnauthorizedError("client Entity Identifier is blank"))
        }

        val anchors = args.trustAnchors.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(args.hostEntityId) }

        return when (args.policy) {
            FederationClientAuthMembershipPolicy.ANY_FETCHABLE ->
                resolveAnyFetchable(clientId)

            FederationClientAuthMembershipPolicy.SUBORDINATE_OF_SELF ->
                resolveSubordinate(clientId, args.hostTenantId)

            FederationClientAuthMembershipPolicy.TRUST_CHAIN_TO_TA ->
                resolveTrustChain(clientId, anchors)

            FederationClientAuthMembershipPolicy.HYBRID -> {
                val local = trySubordinate(clientId, args.hostTenantId)
                if (local != null) {
                    IdkResult.ok(local)
                } else {
                    logger.debug(
                        "Client $clientId is not an immediate subordinate of ${args.hostTenantId}; " +
                            "trying trust chain to ${anchors.joinToString()}"
                    )
                    resolveTrustChain(clientId, anchors)
                }
            }
        }
    }

    private suspend fun resolveAnyFetchable(clientId: String): FederationResult<FederationParticipatingEntity> {
        val keys = loadKeys(clientId) ?: return notParticipant(clientId, "Entity Configuration not available")
        return IdkResult.ok(
            FederationParticipatingEntity(
                entityId = clientId,
                federationEntityKeys = keys,
                membership = FederationMembershipEvidence.AnyFetchable,
            )
        )
    }

    private suspend fun resolveSubordinate(
        clientId: String,
        hostTenantId: String,
    ): FederationResult<FederationParticipatingEntity> {
        val entity = trySubordinate(clientId, hostTenantId)
            ?: return notParticipant(
                clientId,
                "not an Immediate Subordinate of host tenant $hostTenantId",
            )
        return IdkResult.ok(entity)
    }

    private suspend fun trySubordinate(
        clientId: String,
        hostTenantId: String,
    ): FederationParticipatingEntity? {
        val row = subordinateQueries
            .findByAccountIdAndIdentifier(hostTenantId, clientId)
            .executeAsOneOrNull()
            ?: return null
        val keys = loadKeys(clientId) ?: return null
        logger.debug("Accepted client ${row.identifier} as Immediate Subordinate of $hostTenantId")
        return FederationParticipatingEntity(
            entityId = clientId,
            federationEntityKeys = keys,
            membership = FederationMembershipEvidence.ImmediateSubordinate(hostTenantId),
        )
    }

    private suspend fun resolveTrustChain(
        clientId: String,
        trustAnchors: List<String>,
    ): FederationResult<FederationParticipatingEntity> {
        logger.debug("Resolving trust chain for client $clientId to anchors ${trustAnchors.joinToString()}")
        val chainResult = federationClient.trustChainResolve(
            entityIdentifier = clientId,
            trustAnchors = trustAnchors.toTypedArray(),
        )
        if (chainResult.isErr) {
            return notParticipant(
                clientId,
                "no valid Trust Chain to configured Trust Anchor(s): ${chainResult.error.message.defaultMessage}",
            )
        }
        val chain = chainResult.value.trustChain
        if (chain.isEmpty()) {
            return notParticipant(clientId, "empty Trust Chain")
        }
        val keys = loadKeys(clientId)
            ?: return notParticipant(clientId, "Entity Configuration keys unavailable after trust chain resolution")
        val usedAnchor = trustAnchors.firstOrNull() ?: clientId
        return IdkResult.ok(
            FederationParticipatingEntity(
                entityId = clientId,
                federationEntityKeys = keys,
                membership = FederationMembershipEvidence.TrustChainToAnchor(
                    trustAnchor = usedAnchor,
                    chainStatementCount = chain.size,
                ),
            )
        )
    }

    private suspend fun loadKeys(clientId: String): List<Jwk>? {
        val ec = federationClient.entityConfigurationStatementGet(clientId)
        if (ec.isErr) {
            logger.debug("Failed to load EC for $clientId: ${ec.error.message.defaultMessage}")
            return null
        }
        val keys = ec.value.jwks.propertyKeys
        if (keys.isNullOrEmpty()) {
            logger.debug("Entity $clientId has no Federation Entity Keys in jwks")
            return null
        }
        return keys
    }

    private fun notParticipant(clientId: String, reason: String): FederationResult<FederationParticipatingEntity> {
        logger.info("Rejecting federation client auth for $clientId: $reason")
        // Map to invalid_request-shaped code that HTTP layer remaps to invalid_client
        return federationErr(
            UnauthorizedError("Federation participant check failed for '$clientId': $reason")
        )
    }
}
