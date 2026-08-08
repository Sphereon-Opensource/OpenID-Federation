package com.sphereon.openid.fed.client.command.discovery

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Top-down discovery of federation entities via list endpoints (wallet profile §8.3).
 *
 * Walks [DiscoverEntitiesArgs.startEntityId], collecting subordinates matching [entityType]
 * and optionally descending Intermediate list endpoints.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DiscoverEntitiesCommand>())
class DiscoverEntitiesCommandImpl(
    execution: SessionExecution,
    private val listSubordinatesCommand: ListSubordinatesCommand,
    private val resolveTrustChainCommand: ResolveTrustChainCommand,
    private val verifyTrustChainCommand: VerifyTrustChainCommand,
) : ExecutionScopedCommandAdapter<DiscoverEntitiesArgs, DiscoverEntitiesResult, FederationError>(
    id = DiscoverEntitiesCommand.COMMAND_ID,
    execution = execution,
), DiscoverEntitiesCommand {

    private val logger = EntityConfigurationStatementServiceConst.LOG

    override suspend fun discoverEntities(
        args: DiscoverEntitiesArgs,
    ): IdkResult<DiscoverEntitiesResult, FederationError> = execute(args)

    override suspend fun doExecute(
        args: DiscoverEntitiesArgs,
        applyDuring: (DiscoverEntitiesArgs) -> DiscoverEntitiesArgs,
    ): IdkResult<DiscoverEntitiesResult, FederationError> {
        val a = applyDuring(args)
        if (a.startEntityId.isBlank()) {
            return IdkResult.err(InvalidRequestError("startEntityId is blank"))
        }
        if (a.verifyTrust && a.trustAnchors.isEmpty()) {
            return IdkResult.err(
                InvalidRequestError("trustAnchors required when verifyTrust is true")
            )
        }

        val warnings = mutableListOf<String>()
        val listedSuperiors = mutableListOf<String>()
        val discovered = linkedMapOf<String, DiscoveredEntity>()

        // BFS over superiors that expose list endpoints
        data class Node(val entityId: String, val depth: Int)
        val queue = ArrayDeque<Node>()
        val visitedSuperiors = mutableSetOf<String>()
        queue.add(Node(a.startEntityId, 0))

        while (queue.isNotEmpty()) {
            val (superiorId, depth) = queue.removeFirst()
            if (!visitedSuperiors.add(superiorId)) continue

            // Collect matching entity types at this superior
            val typedList = listSubordinatesCommand.listSubordinates(
                superiorEntityId = superiorId,
                entityType = a.entityType,
            )
            if (typedList.isErr) {
                warnings.add("list($superiorId, entityType=${a.entityType}): ${typedList.error.message.defaultMessage}")
                // still try intermediate listing if recursive
            } else {
                listedSuperiors.add(superiorId)
                for (id in typedList.value.entityIdentifiers) {
                    if (discovered.containsKey(id)) continue
                    val entry = if (a.verifyTrust) {
                        verifyMember(id, a, superiorId, warnings)
                    } else {
                        DiscoveredEntity(
                            entityIdentifier = id,
                            discoveredVia = superiorId,
                            trusted = null,
                        )
                    }
                    if (entry != null) {
                        discovered[id] = entry
                    }
                }
            }

            if (!a.recursive || depth >= a.maxDepth) continue

            // Descend into Intermediates
            val intermediates = listSubordinatesCommand.listSubordinates(
                superiorEntityId = superiorId,
                intermediate = true,
            )
            if (intermediates.isErr) {
                // Fallback: list all without filter and enqueue unknowns not already collected as leaves
                val all = listSubordinatesCommand.listSubordinates(superiorEntityId = superiorId)
                if (all.isOk) {
                    for (id in all.value.entityIdentifiers) {
                        if (id !in discovered && id !in visitedSuperiors) {
                            queue.add(Node(id, depth + 1))
                        }
                    }
                } else {
                    warnings.add(
                        "list intermediates($superiorId): ${intermediates.error.message.defaultMessage}"
                    )
                }
            } else {
                if (superiorId !in listedSuperiors) listedSuperiors.add(superiorId)
                for (id in intermediates.value.entityIdentifiers) {
                    if (id !in visitedSuperiors) {
                        queue.add(Node(id, depth + 1))
                    }
                }
            }
        }

        logger.info(
            "Discovery from ${a.startEntityId}: ${discovered.size} entities " +
                "(type=${a.entityType}, recursive=${a.recursive}, verifyTrust=${a.verifyTrust})"
        )

        return IdkResult.ok(
            DiscoverEntitiesResult(
                startEntityId = a.startEntityId,
                entityType = a.entityType,
                entities = discovered.values.toList(),
                listedSuperiors = listedSuperiors.distinct(),
                warnings = warnings,
            )
        )
    }

    private suspend fun verifyMember(
        entityId: String,
        args: DiscoverEntitiesArgs,
        discoveredVia: String,
        warnings: MutableList<String>,
    ): DiscoveredEntity? {
        val resolve = resolveTrustChainCommand.resolveTrustChain(
            entityIdentifier = entityId,
            trustAnchors = args.trustAnchors,
            maxDepth = args.maxDepthTrustChain,
        )
        if (resolve.isErr) {
            warnings.add("trust resolve $entityId: ${resolve.error.message.defaultMessage}")
            return null
        }
        val chain = resolve.value.trustChain
        val verify = verifyTrustChainCommand.verifyTrustChain(
            trustChain = chain.toTypedArray(),
            trustAnchor = null,
            currentTime = null,
            trustAnchorPublicKeys = null,
        )
        if (verify.isErr || !verify.value.isValid) {
            val detail = if (verify.isErr) {
                verify.error.message.defaultMessage
            } else {
                verify.value.errorMessage ?: "not verified"
            }
            warnings.add("trust verify $entityId: $detail")
            return null
        }
        val ta = try {
            // last EC iss when present
            val last = chain.lastOrNull()
            last // opaque; trustAnchor field filled when we can decode — keep start TA preference
            args.trustAnchors.firstOrNull()
        } catch (_: Exception) {
            args.trustAnchors.firstOrNull()
        }
        return DiscoveredEntity(
            entityIdentifier = entityId,
            discoveredVia = discoveredVia,
            trusted = true,
            trustChain = chain,
            trustAnchor = ta,
        )
    }
}
