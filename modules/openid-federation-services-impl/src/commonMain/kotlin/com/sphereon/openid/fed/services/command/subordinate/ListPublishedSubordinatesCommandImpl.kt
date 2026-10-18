package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.Database
import dev.zacsweers.metro.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListPublishedSubordinatesCommand>())
class ListPublishedSubordinatesCommandImpl(
    execution: SessionExecution,
    private val subordinates: FindSubordinatesByAccountCommand,
    private val identifiers: FindSubordinatesByAccountAsArrayCommand,
    private val database: Database?,
) : TypedServiceCommandAdapter<ListPublishedSubordinatesArgs, Array<String>, FederationError>(
    ListPublishedSubordinatesCommand.COMMAND_ID, execution,
    inputTypeToken = typeToken<ListPublishedSubordinatesArgs>(), outputTypeToken = typeToken<Array<String>>(),
), ListPublishedSubordinatesCommand {
    @Inject constructor(execution: SessionExecution, subordinates: FindSubordinatesByAccountCommand, identifiers: FindSubordinatesByAccountAsArrayCommand) :
        this(execution, subordinates, identifiers, null)
    private val adminDocumentQueries get() = database?.adminDocumentQueries ?: Persistence.adminDocumentQueries
    private val subordinateMetadataQueries get() = database?.subordinateMetadataQueries ?: Persistence.subordinateMetadataQueries
    private val trustMarkQueries get() = database?.trustMarkQueries ?: Persistence.trustMarkQueries
    override suspend fun doExecute(args: ListPublishedSubordinatesArgs, applyDuring: (ListPublishedSubordinatesArgs) -> ListPublishedSubordinatesArgs): IdkResult<Array<String>, FederationError> = try {
        list(applyDuring(args))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { federationErr(ServerError("Could not list published subordinates", failure.message, failure)) }
    private fun isIntermediate(federation: JsonObject?): Boolean = federation != null &&
        "federation_fetch_endpoint" in federation && "federation_list_endpoint" in federation
    private suspend fun list(args: ListPublishedSubordinatesArgs): IdkResult<Array<String>, FederationError> {
        val entityType = args.entityType
        val trustMarked = args.trustMarked
        val trustMarkType = args.trustMarkType
        val intermediate = args.intermediate
    if (adminDocumentQueries.find(args.accountId, "configuration", "", "").executeAsOneOrNull() != null) {
        fun active(row: com.sphereon.openid.fed.persistence.models.AdminDocument): Boolean {
            val status = (Json.parseToJsonElement(row.document).jsonObject["status"] as? JsonPrimitive)?.content
            val artifact = row.artifact ?: return false
            if (status != "active") return false
            return runCatching {
                val claims = com.sphereon.openid.fed.client.mapper.decodeJWTComponents(artifact).payload
                (claims["exp"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { it > System.currentTimeMillis() / 1000 } == true
            }.getOrDefault(false)
        }
        val grants = adminDocumentQueries.list(args.accountId, "trust-mark-grant").executeAsList().filter(::active)
        val marked = grants.map { it.subject_id }.toSet()
        val result = adminDocumentQueries.list(args.accountId, "subordinate").executeAsList().filter(::active).filter { row ->
            val document = Json.parseToJsonElement(row.document).jsonObject
            val entityTypes = (document["metadata"] as? JsonObject)?.keys.orEmpty()
            val typeMatches = entityType == null || entityType in entityTypes
            val markedMatches = trustMarked == null || (row.resource_id in marked) == trustMarked
            val markTypeMatches = trustMarkType == null || grants.any { it.subject_id == row.resource_id && it.resource_id == trustMarkType }
            val isIntermediate = isIntermediate((document["metadata"] as? JsonObject)?.get("federation_entity") as? JsonObject)
            typeMatches && markedMatches && markTypeMatches && (intermediate == null || isIntermediate == intermediate)
        }.map { it.resource_id }.distinct().toTypedArray()
        return IdkResult.ok(result)
    }
    val hasFilters = entityType != null || trustMarked != null ||
            trustMarkType != null || intermediate != null

    if (!hasFilters) return identifiers.execute(FindSubordinatesByAccountAsArrayArgs(args.accountId))
    val result = subordinates.execute(FindSubordinatesByAccountArgs(args.accountId))
    if (result.isErr) return result.error.asErrorResult()

    var subordinates = result.value.toList()
    val metadataQueries = subordinateMetadataQueries
    val marks = trustMarkQueries

    // Filter by entity_type: subordinate must have metadata with matching key
    if (entityType != null) {
        subordinates = subordinates.filter { sub ->
            metadataQueries.findByAccountIdAndSubordinateIdAndKey(args.accountId, sub.id, entityType)
                .executeAsList()
                .isNotEmpty()
        }
    }

    // Filter by trust_mark_type: subordinate must have a trust mark with this type
    if (trustMarkType != null) {
        val trustMarkedSubs = marks
            .findAllDistinctSubsByAccountIdAndTrustMarkTypeIdentifier(args.accountId, trustMarkType)
            .executeAsList()
            .toSet()
        subordinates = subordinates.filter { it.identifier in trustMarkedSubs }
    }

    // Filter by trust_marked: if true, include only subordinates with any trust marks;
    // if false, include only subordinates without any trust marks
    if (trustMarked != null) {
        val allTrustMarks = marks.findByAccountId(args.accountId).executeAsList()
        val trustMarkedIdentifiers = allTrustMarks.map { it.sub }.toSet()
        subordinates = if (trustMarked) {
            subordinates.filter { it.identifier in trustMarkedIdentifiers }
        } else {
            subordinates.filter { it.identifier !in trustMarkedIdentifiers }
        }
    }

    if (intermediate != null) {
        subordinates = subordinates.filter { sub ->
            val federation = metadataQueries.findByAccountIdAndSubordinateIdAndKey(args.accountId, sub.id, "federation_entity")
                .executeAsList().firstOrNull()?.metadata?.let { raw ->
                    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                }
            isIntermediate(federation) == intermediate
        }
    }

    val identifiers = subordinates.map { it.identifier }.toTypedArray()
    return IdkResult.ok(identifiers)
    }
}
