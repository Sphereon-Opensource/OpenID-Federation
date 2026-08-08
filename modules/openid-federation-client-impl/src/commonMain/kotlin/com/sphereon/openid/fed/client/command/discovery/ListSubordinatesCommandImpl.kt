package com.sphereon.openid.fed.client.command.discovery

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.entityConfiguration.GetFederationEndpointsCommand
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.buildListEndpointUrl
import com.sphereon.openid.fed.client.services.entityConfigurationStatementService.EntityConfigurationStatementServiceConst
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationHttpError
import com.sphereon.openid.fed.core.error.InvalidEntityConfigurationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * OIDFed 1.1 §8.2: GET `federation_list_endpoint` → JSON array of Entity Identifiers.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListSubordinatesCommand>())
class ListSubordinatesCommandImpl(
    execution: SessionExecution,
    private val context: FederationContext,
    private val getEntityConfigurationCommand: GetEntityConfigurationCommand,
    private val getFederationEndpointsCommand: GetFederationEndpointsCommand,
) : ExecutionScopedCommandAdapter<ListSubordinatesArgs, ListSubordinatesResult, FederationError>(
    id = ListSubordinatesCommand.COMMAND_ID,
    execution = execution,
), ListSubordinatesCommand {

    private val logger = EntityConfigurationStatementServiceConst.LOG

    override suspend fun listSubordinates(
        superiorEntityId: String,
        entityType: String?,
        trustMarked: Boolean?,
        trustMarkType: String?,
        intermediate: Boolean?,
    ): IdkResult<ListSubordinatesResult, FederationError> =
        execute(
            ListSubordinatesArgs(
                superiorEntityId = superiorEntityId,
                entityType = entityType,
                trustMarked = trustMarked,
                trustMarkType = trustMarkType,
                intermediate = intermediate,
            )
        )

    override suspend fun doExecute(
        args: ListSubordinatesArgs,
        applyDuring: (ListSubordinatesArgs) -> ListSubordinatesArgs,
    ): IdkResult<ListSubordinatesResult, FederationError> {
        val a = applyDuring(args)
        if (a.superiorEntityId.isBlank()) {
            return IdkResult.err(InvalidRequestError("superiorEntityId is blank"))
        }

        val ecResult = getEntityConfigurationCommand.getEntityConfiguration(a.superiorEntityId)
        if (ecResult.isErr) return IdkResult.err(ecResult.error)

        val endpointsResult = getFederationEndpointsCommand.getFederationEndpoints(ecResult.value)
        if (endpointsResult.isErr) return IdkResult.err(endpointsResult.error)

        val listEndpoint = endpointsResult.value.federationListEndpoint
            ?: return IdkResult.err(
                InvalidEntityConfigurationError(
                    a.superiorEntityId,
                    "Missing federation_list_endpoint (required for listing subordinates)",
                )
            )

        val url = buildListEndpointUrl(
            listEndpoint = listEndpoint,
            entityType = a.entityType,
            trustMarked = a.trustMarked,
            trustMarkType = a.trustMarkType,
            intermediate = a.intermediate,
        )
        logger.debug("Listing subordinates from $url")

        return try {
            val body = context.httpResolver.get(url)
            val ids = parseEntityIdArray(body)
            IdkResult.ok(
                ListSubordinatesResult(
                    superiorEntityId = a.superiorEntityId,
                    listEndpoint = listEndpoint,
                    entityIdentifiers = ids,
                )
            )
        } catch (e: Exception) {
            logger.error("List subordinates failed for ${a.superiorEntityId}", e)
            IdkResult.err(
                FederationHttpError(
                    url = url,
                    statusCode = 0,
                    reason = e.message ?: "List endpoint request failed",
                    exception = e,
                )
            )
        }
    }

    private fun parseEntityIdArray(body: String): List<String> {
        val element = context.json.parseToJsonElement(body)
        val array: JsonArray = when (element) {
            is JsonArray -> element
            else -> element.jsonArray // may throw
        }
        return array.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
                else -> el.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }
    }
}
