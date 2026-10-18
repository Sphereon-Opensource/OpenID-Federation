package com.sphereon.openid.fed.server.admin.ktor

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.TrustMarkTypeNotFoundError
import com.sphereon.openid.fed.core.error.TrustMarkTypeVersionConflictError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.IdentityMode
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.TrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeDefinition
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeRevision
import com.sphereon.openid.fed.server.admin.api.http.command.AdminMutationGuard
import com.sphereon.openid.fed.server.admin.api.http.command.ListTrustMarkTypeRevisionsEndpointCommandImpl
import com.sphereon.openid.fed.server.admin.api.http.command.UpdateTrustMarkTypeDefinitionEndpointCommandImpl
import com.sphereon.openid.fed.server.admin.ktor.di.createAdminServerAppGraph
import com.sphereon.openid.fed.services.command.trustMark.ListTrustMarkTypeRevisionsArgs
import com.sphereon.openid.fed.services.command.trustMark.ListTrustMarkTypeRevisionsCommand
import com.sphereon.openid.fed.services.command.trustMark.UpdateTrustMarkTypeDefinitionArgs
import com.sphereon.openid.fed.services.command.trustMark.UpdateTrustMarkTypeDefinitionCommand
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The update and revisions endpoints map the commands' results and errors onto HTTP. */
class TrustMarkTypeDefinitionEndpointTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val tenantId = "11111111-1111-1111-1111-111111111111"
    private val typeId = "22222222-2222-2222-2222-222222222222"

    private val tenants = object : TenantContextResolver {
        override suspend fun resolveTenantId(request: GenericHttpRequest): String = tenantId
        override suspend fun resolveTenantIdByName(name: String): String = tenantId
        override suspend fun resolveIdentifier(tenantId: String): String = "https://issuer.example"
    }

    private fun type(version: Long, definition: TrustMarkTypeDefinition?) = TrustMarkType(
        id = typeId,
        identifier = "https://issuer.example/trust-marks/certified",
        createdAt = "2026-01-01T00:00",
        version = version,
        definition = definition,
    )

    private fun <T> withExecution(block: suspend (SessionExecution, OidfConfigBinder) -> T): T {
        val app = createAdminServerAppGraph(Any())
        try {
            val session = app.userContextManager.getAnonymous().sessionContextManager
                .createOrGetFromId("trust-mark-type-definition-endpoints", principalType = PrincipalType.USER)
            val binder = object : OidfConfigBinder by app.configBinder {
                override fun getIdentityConfig() = app.configBinder.getIdentityConfig().copy(mode = IdentityMode.ACCOUNT)
            }
            return kotlinx.coroutines.runBlocking { block(session.asCoreApiServiceGraph().serviceExecution, binder) }
        } finally {
            app.destroy()
        }
    }

    private class FakeUpdate(
        execution: SessionExecution,
        val answer: (UpdateTrustMarkTypeDefinitionArgs) -> IdkResult<TrustMarkType, FederationError>,
    ) : TypedServiceCommandAdapter<UpdateTrustMarkTypeDefinitionArgs, TrustMarkType, FederationError>(
        commandId = UpdateTrustMarkTypeDefinitionCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<UpdateTrustMarkTypeDefinitionArgs>(),
        outputTypeToken = typeToken<TrustMarkType>(),
    ), UpdateTrustMarkTypeDefinitionCommand {
        var received: UpdateTrustMarkTypeDefinitionArgs? = null
        override suspend fun doExecute(
            args: UpdateTrustMarkTypeDefinitionArgs,
            applyDuring: (UpdateTrustMarkTypeDefinitionArgs) -> UpdateTrustMarkTypeDefinitionArgs,
        ): IdkResult<TrustMarkType, FederationError> = applyDuring(args).also { received = it }.let(answer)
    }

    private class FakeList(
        execution: SessionExecution,
        val answer: (ListTrustMarkTypeRevisionsArgs) -> IdkResult<List<TrustMarkTypeRevision>, FederationError>,
    ) : TypedServiceCommandAdapter<ListTrustMarkTypeRevisionsArgs, List<TrustMarkTypeRevision>, FederationError>(
        commandId = ListTrustMarkTypeRevisionsCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<ListTrustMarkTypeRevisionsArgs>(),
        outputTypeToken = typeToken<List<TrustMarkTypeRevision>>(),
    ), ListTrustMarkTypeRevisionsCommand {
        override suspend fun doExecute(
            args: ListTrustMarkTypeRevisionsArgs,
            applyDuring: (ListTrustMarkTypeRevisionsArgs) -> ListTrustMarkTypeRevisionsArgs,
        ): IdkResult<List<TrustMarkTypeRevision>, FederationError> = answer(applyDuring(args))
    }

    private fun put(body: String?) = GenericHttpRequest(
        method = "PUT",
        path = "/trust-mark-types/$typeId",
        headers = mapOf("Content-Type" to "application/json"),
        bodySupplier = { body },
    )

    private suspend fun update(
        execution: SessionExecution,
        binder: OidfConfigBinder,
        body: String?,
        answer: (UpdateTrustMarkTypeDefinitionArgs) -> IdkResult<TrustMarkType, FederationError>,
    ): Pair<GenericHttpResponse, FakeUpdate> {
        val command = FakeUpdate(execution, answer)
        val endpoint = UpdateTrustMarkTypeDefinitionEndpointCommandImpl(
            execution, AdminMutationGuard(execution, binder), command, tenants, json,
        )
        val result = endpoint.execute(put(body))
        return result.value to command
    }

    @Test
    fun updateReturnsTheUpdatedTypeAndPassesTheBodyToTheCommand() {
        withExecution { execution, binder ->
            val body = """{"expectedVersion":3,"definition":{"name":"Certified","lifetimeSeconds":3600,""" +
                """"applicableEntityTypes":["openid_relying_party"],"extensions":{"audience":"public"}}}"""
            val (response, command) = update(execution, binder, body) { args ->
                IdkResult.ok(type(args.expectedVersion + 1, args.definition))
            }
            assertEquals(200, response.statusCode)
            val received = command.received!!
            assertEquals(tenantId, received.tenantId)
            assertEquals(typeId, received.id)
            assertEquals(3L, received.expectedVersion)
            assertEquals("Certified", received.definition.name)
            assertEquals(buildJsonObject { put("audience", "public") }, received.definition.extensions)
            val returned = json.decodeFromString(TrustMarkType.serializer(), response.body!!)
            assertEquals(4L, returned.version)
            assertEquals(3600L, returned.definition!!.lifetimeSeconds)
        }
    }

    @Test
    fun updateMapsConflictNotFoundAndInvalidDefinitionToHttpStatus() {
        withExecution { execution, binder ->
            val body = """{"expectedVersion":1,"definition":{"name":"Certified"}}"""
            assertEquals(409, update(execution, binder, body) {
                federationErr(TrustMarkTypeVersionConflictError(typeId, 1L, 2L))
            }.first.statusCode)
            assertEquals(404, update(execution, binder, body) { federationErr(TrustMarkTypeNotFoundError(typeId)) }.first.statusCode)
            assertEquals(400, update(execution, binder, body) {
                federationErr(InvalidRequestError("Invalid trust mark type definition: name must not be blank"))
            }.first.statusCode)
        }
    }

    @Test
    fun malformedUpdateBodiesAreRejectedBeforeTheCommandRuns() {
        withExecution { execution, binder ->
            for (body in listOf(null, "", "[]", """{"definition":{"name":"Certified"}}""", """{"expectedVersion":1}""",
                """{"expectedVersion":1,"definition":{"name":"Certified","extensions":[1]}}""")) {
                val (response, command) = update(execution, binder, body) { error("must not run") }
                assertEquals(400, response.statusCode, body)
                assertNull(command.received, body)
            }
        }
    }

    @Test
    fun revisionsAreListedInCommandOrderAndUnknownTypesAre404() {
        withExecution { execution, _ ->
            val revisions = listOf(
                TrustMarkTypeRevision(version = 1L, definition = null, createdAt = "2026-01-01T00:00"),
                TrustMarkTypeRevision(version = 2L, definition = TrustMarkTypeDefinition(name = "Certified"), createdAt = "2026-01-02T00:00"),
            )
            val request = GenericHttpRequest(method = "GET", path = "/trust-mark-types/$typeId/revisions")
            val ok = ListTrustMarkTypeRevisionsEndpointCommandImpl(execution, FakeList(execution) { args ->
                assertEquals(typeId, args.id)
                assertEquals(tenantId, args.tenantId)
                IdkResult.ok(revisions)
            }, tenants, json).execute(request).value
            assertEquals(200, ok.statusCode)
            assertEquals(revisions, json.decodeFromString(ListSerializer(TrustMarkTypeRevision.serializer()), ok.body!!))

            val missing = ListTrustMarkTypeRevisionsEndpointCommandImpl(execution, FakeList(execution) {
                federationErr(TrustMarkTypeNotFoundError(typeId))
            }, tenants, json).execute(request).value
            assertEquals(404, missing.statusCode)
        }
    }
}
