package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.service.ServiceCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AdminServiceCommandsTest {
    @Test fun everyRegisteredOperationExecutesDirectlyWithoutHttp() = runTest {
        val calls = mutableListOf<Pair<AdminOperation, AdminCommandArgs>>()
        val workflow = object : AdminWorkflow {
            override suspend fun execute(operation: AdminOperation, args: AdminCommandArgs): IdkResult<AdminCommandResult, AdminError> {
                calls += operation to args
                return IdkResult.ok(AdminCommandResult(etag = operation.commandId))
            }
            override suspend fun stage(operation: AdminOperation, args: AdminCommandArgs): IdkResult<AdminCommandResult, AdminError> = error("unused")
            override suspend fun finalizeStage(scope: AdminNodeScope, digest: String, expectedStageEtag: String?): IdkResult<AdminCommandResult, AdminError> = error("unused")
            override suspend fun stagedPublication(scope: AdminNodeScope, digest: String): IdkResult<AdminStagedPublication?, AdminError> = error("unused")
            override suspend fun pendingStages(scope: AdminNodeScope): IdkResult<List<AdminStagedPublication>, AdminError> = error("unused")
            override suspend fun selectSigningKey(scope: AdminNodeScope, keyId: String?, expectedRevision: Long): IdkResult<AdminCommandResult, AdminError> = error("unused")
            override suspend fun withdrawSubordinate(scope: AdminNodeScope, entityId: String, edgeId: String?, expectedIntentVersion: Long?): IdkResult<AdminCommandResult, AdminError> = error("unused")
        }
        val execution = testSessionExecution()
        val commands: List<Pair<AdminOperation, ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError>>> = listOf(
            AdminOperation.ReadAdminNode to ReadAdminNodeCommandImpl(execution, workflow),
            AdminOperation.ListAdminKeys to ListAdminKeysCommandImpl(execution, workflow),
            AdminOperation.CreateAdminKey to CreateAdminKeyCommandImpl(execution, workflow),
            AdminOperation.ReadAdminKey to ReadAdminKeyCommandImpl(execution, workflow),
            AdminOperation.DeleteAdminKey to DeleteAdminKeyCommandImpl(execution, workflow),
            AdminOperation.RotateAdminKey to RotateAdminKeyCommandImpl(execution, workflow),
            AdminOperation.ReadAdminConfiguration to ReadAdminConfigurationCommandImpl(execution, workflow),
            AdminOperation.ReplaceAdminConfiguration to ReplaceAdminConfigurationCommandImpl(execution, workflow),
            AdminOperation.PatchAdminConfiguration to PatchAdminConfigurationCommandImpl(execution, workflow),
            AdminOperation.ReadAdminConfigurationStatement to ReadAdminConfigurationStatementCommandImpl(execution, workflow),
            AdminOperation.ListAdminSubordinates to ListAdminSubordinatesCommandImpl(execution, workflow),
            AdminOperation.CreateAdminSubordinate to CreateAdminSubordinateCommandImpl(execution, workflow),
            AdminOperation.ReadAdminSubordinate to ReadAdminSubordinateCommandImpl(execution, workflow),
            AdminOperation.ReplaceAdminSubordinate to ReplaceAdminSubordinateCommandImpl(execution, workflow),
            AdminOperation.PatchAdminSubordinate to PatchAdminSubordinateCommandImpl(execution, workflow),
            AdminOperation.DeleteAdminSubordinate to DeleteAdminSubordinateCommandImpl(execution, workflow),
            AdminOperation.SetAdminSubordinateStatus to SetAdminSubordinateStatusCommandImpl(execution, workflow),
            AdminOperation.ReadAdminSubordinateStatement to ReadAdminSubordinateStatementCommandImpl(execution, workflow),
            AdminOperation.ListAdminTrustMarkTypes to ListAdminTrustMarkTypesCommandImpl(execution, workflow),
            AdminOperation.CreateAdminTrustMarkType to CreateAdminTrustMarkTypeCommandImpl(execution, workflow),
            AdminOperation.ReadAdminTrustMarkType to ReadAdminTrustMarkTypeCommandImpl(execution, workflow),
            AdminOperation.ReplaceAdminTrustMarkType to ReplaceAdminTrustMarkTypeCommandImpl(execution, workflow),
            AdminOperation.DeleteAdminTrustMarkType to DeleteAdminTrustMarkTypeCommandImpl(execution, workflow),
            AdminOperation.ListAdminTrustMarkGrants to ListAdminTrustMarkGrantsCommandImpl(execution, workflow),
            AdminOperation.ReplaceAdminTrustMarkGrant to ReplaceAdminTrustMarkGrantCommandImpl(execution, workflow),
            AdminOperation.DeleteAdminTrustMarkGrant to DeleteAdminTrustMarkGrantCommandImpl(execution, workflow),
            AdminOperation.SetAdminTrustMarkGrantStatus to SetAdminTrustMarkGrantStatusCommandImpl(execution, workflow),
            AdminOperation.ReadAdminTrustMarkToken to ReadAdminTrustMarkTokenCommandImpl(execution, workflow)
        )
        assertEquals(AdminOperation.entries.toSet(), commands.map { it.first }.toSet())
        val args = AdminCommandArgs(AdminNodeScope("trusted-account", "https://ta.example"), resourceId = "https://subject.example/path", ifMatch = "opaque-revision")
        commands.forEach { (operation, command) ->
            val result = command.execute(args)
            assertTrue(result.isOk, operation.commandId)
            assertEquals(operation.commandId, result.value.etag)
            assertEquals(operation to args, calls.last())
        }
        assertEquals(28, calls.size)
    }
}
