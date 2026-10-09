package com.sphereon.openid.fed.server.admin.api.http.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.core.api.http.response.errorResponse
import com.sphereon.core.api.http.response.jsonResponse
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.CreateKey
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.JwkService
import com.sphereon.openid.fed.services.mappers.toTenantJwksResponse
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionArgs
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionCommand
import com.sphereon.openid.fed.services.command.jwk.SetAccountSigningKeySelectionArgs
import com.sphereon.openid.fed.services.command.jwk.SetAccountSigningKeySelectionCommand
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

// ==================== List Keys Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListKeysEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(ListKeysEndpointCommand.COMMAND_ID)
class ListKeysEndpointCommandImpl(
    execution: SessionExecution,
    private val jwkService: JwkService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = ListKeysEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListKeysEndpointCommand.ENDPOINT
), ListKeysEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = jwkService.getKeys(tenantId, includeRevoked = false)

        return if (result.isOk) {
            val keys = result.value.toTenantJwksResponse()
            Ok(jsonResponse(200, json.encodeToString(keys)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Create Key Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateKeyEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(CreateKeyEndpointCommand.COMMAND_ID)
class CreateKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val jwkService: JwkService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = CreateKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = CreateKeyEndpointCommand.ENDPOINT
), CreateKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        // PLATFORM: reject anonymous admin mutations (no-op in LEGACY)
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val createKey = if (request.body.isNullOrBlank()) {
            CreateKey()
        } else {
            try {
                json.decodeFromString<CreateKey>(request.body!!)
            } catch (e: Exception) {
                return Ok(errorResponse(400, "Invalid request body: ${e.message}"))
            }
        }

        val result = jwkService.createKey(tenantId, CreateKeyArgs.fromModel(createKey))

        return if (result.isOk) {
            Ok(GenericHttpResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(result.value)
            ))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Revoke Key Endpoint Implementation ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RevokeKeyEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(RevokeKeyEndpointCommand.COMMAND_ID)
class RevokeKeyEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val jwkService: JwkService,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = RevokeKeyEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = RevokeKeyEndpointCommand.ENDPOINT
), RevokeKeyEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        // PLATFORM: reject anonymous admin mutations (no-op in LEGACY)
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val req = request.withExtractedParams(RevokeKeyEndpointCommand.ENDPOINT.pathPattern)
        val keyId = req.pathParams["keyId"]
            ?: return Ok(errorResponse(400, "Missing path parameter: keyId"))

        val reason = request.queryParameters["reason"]

        val result = jwkService.revokeKey(tenantId, keyId, reason)

        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

// ==================== Signing Key Selection Endpoint Implementations ====================

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetSigningKeySelectionEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(GetSigningKeySelectionEndpointCommand.COMMAND_ID)
class GetSigningKeySelectionEndpointCommandImpl(
    execution: SessionExecution,
    private val findSelection: FindAccountSigningKeySelectionCommand,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = GetSigningKeySelectionEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetSigningKeySelectionEndpointCommand.ENDPOINT
), GetSigningKeySelectionEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))
        val identifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val result = findSelection.execute(FindAccountSigningKeySelectionArgs(tenantId, identifier))
        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}

/**
 * Selects the account's signing key. The body names the key (`selectedKeyId`, or null to clear the selection) and
 * the selection revision the caller read (`expectedRevision`), so a concurrent change is refused instead of
 * overwritten.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SetSigningKeySelectionEndpointCommand>())
@ContributesIntoMap(SessionScope::class, binding = binding<HttpEndpointCommand>())
@StringKey(SetSigningKeySelectionEndpointCommand.COMMAND_ID)
class SetSigningKeySelectionEndpointCommandImpl(
    execution: SessionExecution,
    private val adminMutationGuard: AdminMutationGuard,
    private val setSelection: SetAccountSigningKeySelectionCommand,
    private val tenantContextResolver: TenantContextResolver,
    private val json: Json
) : HttpEndpointCommandAdapter(
    id = SetSigningKeySelectionEndpointCommand.COMMAND_ID,
    execution = execution,
    endpoint = SetSigningKeySelectionEndpointCommand.ENDPOINT
), SetSigningKeySelectionEndpointCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest
    ): IdkResult<GenericHttpResponse, IdkError> {
        adminMutationGuard.denyIfUnauthorized()?.let { return it }

        val request = applyDuring(args)

        val tenantId = tenantContextResolver.resolveTenantId(request)
            ?: return Ok(errorResponse(404, "Tenant not found"))
        val identifier = tenantContextResolver.resolveIdentifier(tenantId)
            ?: return Ok(errorResponse(404, "Tenant not found"))

        val body = runCatching { json.parseToJsonElement(request.body.orEmpty()) as? JsonObject }.getOrNull()
            ?: return Ok(errorResponse(400, "Invalid request body: a JSON object is required"))
        val expectedRevision = (body["expectedRevision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: return Ok(errorResponse(400, "Invalid request body: expectedRevision is required"))
        val selectedKeyId = when (val value = body["selectedKeyId"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
                ?: return Ok(errorResponse(400, "Invalid request body: selectedKeyId must be a string or null"))
            else -> return Ok(errorResponse(400, "Invalid request body: selectedKeyId must be a string or null"))
        }

        val result = setSelection.execute(SetAccountSigningKeySelectionArgs(tenantId, identifier, expectedRevision, selectedKeyId))
        return if (result.isOk) {
            Ok(jsonResponse(200, json.encodeToString(result.value)))
        } else {
            val error = result.error
            Ok(errorResponse(error.httpStatusValue, error.message.defaultMessage))
        }
    }
}
