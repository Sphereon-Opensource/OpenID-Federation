package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType
import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.admin.*
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import dev.zacsweers.metro.*
import kotlinx.serialization.json.*

private fun standardAdminHandlerId(operation: AdminOperation): String =
    "fed.admin-http." + operation.commandId.removePrefix("fed.admin-").replace('.', '-')

fun standardAdminEndpoint(operation: AdminOperation): HttpEndpointDescriptor = HttpEndpointDescriptor(
    method = HttpMethod.valueOf(operation.method), pathPattern = "/admin/v1" + operation.path,
    commandId = operation.commandId, handlerCommandId = standardAdminHandlerId(operation),
    operationId = operation.name.replaceFirstChar { it.lowercase() },
    consumes = if (operation.method == "PATCH") setOf(MediaType.Custom("application/merge-patch+json")) else if (operation.method in setOf("POST", "PUT")) setOf(MediaType.ApplicationJson) else emptySet(),
    produces = setOf(if (operation.path.endsWith("/statement")) MediaType.Custom("application/entity-statement+jwt")
        else if (operation.path.endsWith("/token")) MediaType.Custom("application/trust-mark+jwt") else MediaType.ApplicationJson),
    tags = setOf("federation-admin"), summary = operation.name,
)

/** HTTP translation only; every operation invokes its explicit reusable ServiceCommand. */
abstract class StandardAdminEndpointCommand(
    execution: SessionExecution,
    private val operation: AdminOperation,
    private val command: ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError>,
    private val tenantResolver: TenantContextResolver,
    private val guard: AdminMutationGuard,
) : HttpEndpointCommandAdapter(
    id = standardAdminHandlerId(operation), execution = execution,
    endpoint = standardAdminEndpoint(operation),
) {
    override val encodedPathParameters: Set<String> = setOf("entity_id", "kid", "type")
        .filter { "{$it}" in operation.path }.toSet()
    override suspend fun doExecute(args: GenericHttpRequest, applyDuring: (GenericHttpRequest) -> GenericHttpRequest): IdkResult<GenericHttpResponse, IdkError> {
        guard.denyIfUnauthorized()?.let { denial ->
            val status = if (denial.isOk) denial.value.statusCode else 403
            return Ok(problem(AdminError(if (status == 401) "unauthorized" else "forbidden", status, "Administration access denied")))
        }
        val request = applyDuring(args).withExtractedParams("/admin/v1" + operation.path)
        val tenantId = tenantResolver.resolveTenantId(request) ?: return Ok(problem(AdminError("not_found", 404, "Node not found")))
        val media = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(';')?.trim()
        val expectedMedia = if (operation.method == "PATCH") "application/merge-patch+json" else "application/json"
        if (operation.method in setOf("POST", "PUT", "PATCH") && !expectedMedia.equals(media, ignoreCase = true))
            return Ok(problem(AdminError("unsupported_media_type", 415, "${operation.method} requires $expectedMedia")))
        val body = request.body?.let {
            try { Json.parseToJsonElement(it) as? JsonObject } catch (_: Exception) { null }
        }
        if (operation.method in setOf("POST", "PUT", "PATCH") && body == null)
            return Ok(problem(AdminError("invalid_request", 400, "A JSON object is required")))
        val result = command.execute(AdminCommandArgs(
            scope = AdminNodeScope(tenantId, tenantResolver.resolveIdentifier(tenantId)),
            resourceId = request.pathParams["kid"] ?: request.pathParams["type"] ?: request.pathParams["entity_id"],
            subjectId = if (operation.path.contains("/subjects/")) request.pathParams["entity_id"] else null,
            document = body,
            ifMatch = request.headers.entries.firstOrNull { it.key.equals("If-Match", true) }?.value,
            query = request.queryParameters.mapValues { it.value.orEmpty() },
        ))
        if (result.isErr) return Ok(problem(result.error))
        val value = result.value
        return Ok(GenericHttpResponse(statusCode = value.status, headers = buildMap {
            put("Content-Type", value.mediaType); value.etag?.let { put("ETag", it) }; value.location?.let { put("Location", it) }
        }, body = value.artifact ?: value.document?.toString()))
    }
    private fun problem(error: AdminError) = GenericHttpResponse(statusCode = error.status,
        headers = mapOf("Content-Type" to "application/problem+json"), body = buildJsonObject {
            put("type", "urn:ietf:params:oidfed:admin:problem#${error.token}")
            put("title", error.token.replace('_', ' ').replaceFirstChar { it.uppercase() }); put("status", error.status)
            put("detail", error.detail); error.pointer?.let { put("pointer", it) }
        }.toString())
}

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.node-read")
class ReadAdminNodeEndpointCommand(
    execution: SessionExecution, command: ReadAdminNodeCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminNode, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.keys-list")
class ListAdminKeysEndpointCommand(
    execution: SessionExecution, command: ListAdminKeysCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ListAdminKeys, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.keys-create")
class CreateAdminKeyEndpointCommand(
    execution: SessionExecution, command: CreateAdminKeyCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.CreateAdminKey, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.keys-read")
class ReadAdminKeyEndpointCommand(
    execution: SessionExecution, command: ReadAdminKeyCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminKey, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.keys-delete")
class DeleteAdminKeyEndpointCommand(
    execution: SessionExecution, command: DeleteAdminKeyCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.DeleteAdminKey, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.keys-rotate")
class RotateAdminKeyEndpointCommand(
    execution: SessionExecution, command: RotateAdminKeyCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.RotateAdminKey, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.configuration-read")
class ReadAdminConfigurationEndpointCommand(
    execution: SessionExecution, command: ReadAdminConfigurationCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminConfiguration, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.configuration-replace")
class ReplaceAdminConfigurationEndpointCommand(
    execution: SessionExecution, command: ReplaceAdminConfigurationCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReplaceAdminConfiguration, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.configuration-patch")
class PatchAdminConfigurationEndpointCommand(
    execution: SessionExecution, command: PatchAdminConfigurationCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.PatchAdminConfiguration, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.configuration-statement")
class ReadAdminConfigurationStatementEndpointCommand(
    execution: SessionExecution, command: ReadAdminConfigurationStatementCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminConfigurationStatement, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-list")
class ListAdminSubordinatesEndpointCommand(
    execution: SessionExecution, command: ListAdminSubordinatesCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ListAdminSubordinates, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-create")
class CreateAdminSubordinateEndpointCommand(
    execution: SessionExecution, command: CreateAdminSubordinateCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.CreateAdminSubordinate, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-read")
class ReadAdminSubordinateEndpointCommand(
    execution: SessionExecution, command: ReadAdminSubordinateCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminSubordinate, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-replace")
class ReplaceAdminSubordinateEndpointCommand(
    execution: SessionExecution, command: ReplaceAdminSubordinateCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReplaceAdminSubordinate, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-patch")
class PatchAdminSubordinateEndpointCommand(
    execution: SessionExecution, command: PatchAdminSubordinateCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.PatchAdminSubordinate, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-delete")
class DeleteAdminSubordinateEndpointCommand(
    execution: SessionExecution, command: DeleteAdminSubordinateCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.DeleteAdminSubordinate, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-status")
class SetAdminSubordinateStatusEndpointCommand(
    execution: SessionExecution, command: SetAdminSubordinateStatusCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.SetAdminSubordinateStatus, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.subordinates-statement")
class ReadAdminSubordinateStatementEndpointCommand(
    execution: SessionExecution, command: ReadAdminSubordinateStatementCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminSubordinateStatement, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-marks-list")
class ListAdminTrustMarkTypesEndpointCommand(
    execution: SessionExecution, command: ListAdminTrustMarkTypesCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ListAdminTrustMarkTypes, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-marks-create")
class CreateAdminTrustMarkTypeEndpointCommand(
    execution: SessionExecution, command: CreateAdminTrustMarkTypeCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.CreateAdminTrustMarkType, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-marks-read")
class ReadAdminTrustMarkTypeEndpointCommand(
    execution: SessionExecution, command: ReadAdminTrustMarkTypeCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminTrustMarkType, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-marks-replace")
class ReplaceAdminTrustMarkTypeEndpointCommand(
    execution: SessionExecution, command: ReplaceAdminTrustMarkTypeCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReplaceAdminTrustMarkType, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-marks-delete")
class DeleteAdminTrustMarkTypeEndpointCommand(
    execution: SessionExecution, command: DeleteAdminTrustMarkTypeCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.DeleteAdminTrustMarkType, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-mark-subjects-list")
class ListAdminTrustMarkGrantsEndpointCommand(
    execution: SessionExecution, command: ListAdminTrustMarkGrantsCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ListAdminTrustMarkGrants, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-mark-subjects-replace")
class ReplaceAdminTrustMarkGrantEndpointCommand(
    execution: SessionExecution, command: ReplaceAdminTrustMarkGrantCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReplaceAdminTrustMarkGrant, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-mark-subjects-delete")
class DeleteAdminTrustMarkGrantEndpointCommand(
    execution: SessionExecution, command: DeleteAdminTrustMarkGrantCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.DeleteAdminTrustMarkGrant, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-mark-subjects-status")
class SetAdminTrustMarkGrantStatusEndpointCommand(
    execution: SessionExecution, command: SetAdminTrustMarkGrantStatusCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.SetAdminTrustMarkGrantStatus, command, tenantResolver, guard)

@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey("fed.admin-http.trust-mark-subjects-token")
class ReadAdminTrustMarkTokenEndpointCommand(
    execution: SessionExecution, command: ReadAdminTrustMarkTokenCommand, tenantResolver: TenantContextResolver, guard: AdminMutationGuard,
) : StandardAdminEndpointCommand(execution, AdminOperation.ReadAdminTrustMarkToken, command, tenantResolver, guard)

