package com.sphereon.openid.fed.server.admin.ktor

import com.sphereon.core.api.*
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.http.*
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.openid.fed.admin.*
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.server.admin.api.http.command.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class StandardAdminEndpointCommandTest {
    private val execution = testSessionExecution()
    private val resolver = mockk<TenantContextResolver>().also {
        coEvery { it.resolveTenantId(any()) } returns "authorized-account"
        coEvery { it.resolveIdentifier("authorized-account") } returns "https://ta.example"
    }
    private val guard = mockk<AdminMutationGuard>().also { every { it.denyIfUnauthorized() } returns null }
    private inner class RecordingCommand : TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        "fed.admin-test.execute", execution, typeToken(), typeToken()) {
        var received: AdminCommandArgs? = null
        var result: IdkResult<AdminCommandResult, AdminError> = Ok(AdminCommandResult(document = buildJsonObject { put("ok", true) }, etag = "\"revision\""))
        override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs): IdkResult<AdminCommandResult, AdminError> {
            received = applyDuring(args); return result
        }
    }
    private fun endpoint(operation: AdminOperation, command: RecordingCommand) = object : StandardAdminEndpointCommand(execution, operation, command, resolver, guard) {}
    private fun request(op: AdminOperation, body: String? = null, media: String = if (op.method == "PATCH") "application/merge-patch+json" else "application/json", match: String? = null): GenericHttpRequest {
        val ids = mapOf("entity_id" to "https://subject.example/path%2Fpart", "type" to "https://types.example/type", "kid" to "kid/part")
            .filterKeys { "{$it}" in op.path }
        var path = "/admin/v1" + op.path
        ids.forEach { (key, value) -> path = path.replace("{$key}", java.net.URLEncoder.encode(value, "UTF-8")) }
        return GenericHttpRequest(method = op.method, path = path, pathParameters = ids,
            queryParameters = mapOf("limit" to "3"), headers = buildMap { put("Content-Type", media); match?.let { put("If-Match", it) } },
            bodyContent = body?.let { GenericHttpBody.Text(it) } ?: GenericHttpBody.Empty)
    }
    @Test fun everyStandardOperationDelegatesWithResolvedScopeAndDecodedIdentifiers() = runTest {
        AdminOperation.entries.forEach { op ->
            val command = RecordingCommand()
            val handler = endpoint(op, command)
            val response = handler.execute(request(op, if (op.method in setOf("POST", "PUT", "PATCH")) "{}" else null)).value
            val received = assertNotNull(command.received, op.name)
            assertEquals("authorized-account", received.scope.accountId)
            assertEquals("https://ta.example", received.scope.entityId)
            assertEquals(200, response.statusCode)
            assertEquals("3", received.query["limit"])
            assertEquals(setOf("entity_id", "kid", "type").filter { "{$it}" in op.path }.toSet(), handler.encodedPathParameters)
            if (op.path.contains("/subjects/")) {
                assertEquals("https://types.example/type", received.resourceId)
                assertEquals("https://subject.example/path%2Fpart", received.subjectId)
            } else if ("{entity_id}" in op.path) assertEquals("https://subject.example/path%2Fpart", received.resourceId)
        }
    }
    @Test fun patchesPreserveNullMembersAndOpaqueConditionalHeaders() = runTest {
        val command = RecordingCommand()
        val body = """{"scope":{"accountId":"attacker"},"metadata":{"openid_provider":{"logo_uri":null}}}"""
        val response = endpoint(AdminOperation.PatchAdminSubordinate, command).execute(request(AdminOperation.PatchAdminSubordinate, body, match = "\"incarnation:2\"")).value
        assertEquals<JsonElement?>(Json.parseToJsonElement(body), command.received?.document)
        assertEquals("authorized-account", command.received?.scope?.accountId)
        assertEquals("\"incarnation:2\"", command.received?.ifMatch)
        assertEquals("\"revision\"", response.headers["ETag"])
    }
    @Test fun publishedBytesAndTypedProblemsKeepTheirMediaAndStatus() = runTest {
        val command = RecordingCommand()
        command.result = Ok(AdminCommandResult(artifact = "exact.compact.bytes", mediaType = "application/trust-mark+jwt"))
        val response = endpoint(AdminOperation.ReadAdminTrustMarkToken, command).execute(request(AdminOperation.ReadAdminTrustMarkToken)).value
        assertEquals("exact.compact.bytes", response.body)
        assertEquals("application/trust-mark+jwt", response.headers["Content-Type"])
        command.result = Err(AdminError("precondition_failed", 412, "Revision changed", "/etag")).asResult()
        val error = endpoint(AdminOperation.ReadAdminConfiguration, command).execute(request(AdminOperation.ReadAdminConfiguration)).value
        assertEquals(412, error.statusCode)
        assertEquals("application/problem+json", error.headers["Content-Type"])
        assertEquals(JsonPrimitive("/etag"), Json.parseToJsonElement(error.body!!).jsonObject["pointer"])
    }
    @Test fun invalidMediaAndBodiesNeverInvokeTheServiceCommand() = runTest {
        val command = RecordingCommand()
        val handler = endpoint(AdminOperation.PatchAdminConfiguration, command)
        assertEquals(415, handler.execute(request(AdminOperation.PatchAdminConfiguration, "{}", "application/json")).value.statusCode)
        assertEquals(400, handler.execute(request(AdminOperation.PatchAdminConfiguration, "[]")).value.statusCode)
        assertEquals(400, handler.execute(request(AdminOperation.PatchAdminConfiguration, "{")).value.statusCode)
        assertEquals(415, endpoint(AdminOperation.ReplaceAdminConfiguration, command).execute(request(AdminOperation.ReplaceAdminConfiguration, "{}", "application/xml")).value.statusCode)
        assertNull(command.received)
        assertEquals(200, handler.execute(request(AdminOperation.PatchAdminConfiguration, "{}", "Application/Merge-Patch+Json")).value.statusCode)
        assertNotNull(command.received)
    }
}
