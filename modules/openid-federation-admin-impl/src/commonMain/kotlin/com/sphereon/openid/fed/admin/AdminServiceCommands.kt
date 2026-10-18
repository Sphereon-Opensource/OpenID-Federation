package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.*

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminNodeCommand>())
class ReadAdminNodeCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminNodeCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminNodeCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminNode, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListAdminKeysCommand>())
class ListAdminKeysCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ListAdminKeysCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ListAdminKeysCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ListAdminKeys, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateAdminKeyCommand>())
class CreateAdminKeyCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = CreateAdminKeyCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), CreateAdminKeyCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.CreateAdminKey, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminKeyCommand>())
class ReadAdminKeyCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminKeyCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminKeyCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminKey, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteAdminKeyCommand>())
class DeleteAdminKeyCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = DeleteAdminKeyCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), DeleteAdminKeyCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.DeleteAdminKey, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RotateAdminKeyCommand>())
class RotateAdminKeyCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = RotateAdminKeyCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), RotateAdminKeyCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.RotateAdminKey, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminConfigurationCommand>())
class ReadAdminConfigurationCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminConfigurationCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminConfigurationCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminConfiguration, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReplaceAdminConfigurationCommand>())
class ReplaceAdminConfigurationCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReplaceAdminConfigurationCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReplaceAdminConfigurationCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReplaceAdminConfiguration, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PatchAdminConfigurationCommand>())
class PatchAdminConfigurationCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = PatchAdminConfigurationCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), PatchAdminConfigurationCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.PatchAdminConfiguration, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminConfigurationStatementCommand>())
class ReadAdminConfigurationStatementCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminConfigurationStatementCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminConfigurationStatementCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminConfigurationStatement, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListAdminSubordinatesCommand>())
class ListAdminSubordinatesCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ListAdminSubordinatesCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ListAdminSubordinatesCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ListAdminSubordinates, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateAdminSubordinateCommand>())
class CreateAdminSubordinateCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = CreateAdminSubordinateCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), CreateAdminSubordinateCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.CreateAdminSubordinate, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminSubordinateCommand>())
class ReadAdminSubordinateCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminSubordinateCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminSubordinateCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminSubordinate, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReplaceAdminSubordinateCommand>())
class ReplaceAdminSubordinateCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReplaceAdminSubordinateCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReplaceAdminSubordinateCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReplaceAdminSubordinate, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PatchAdminSubordinateCommand>())
class PatchAdminSubordinateCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = PatchAdminSubordinateCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), PatchAdminSubordinateCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.PatchAdminSubordinate, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteAdminSubordinateCommand>())
class DeleteAdminSubordinateCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = DeleteAdminSubordinateCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), DeleteAdminSubordinateCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.DeleteAdminSubordinate, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SetAdminSubordinateStatusCommand>())
class SetAdminSubordinateStatusCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = SetAdminSubordinateStatusCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), SetAdminSubordinateStatusCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.SetAdminSubordinateStatus, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminSubordinateStatementCommand>())
class ReadAdminSubordinateStatementCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminSubordinateStatementCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminSubordinateStatementCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminSubordinateStatement, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListAdminTrustMarkTypesCommand>())
class ListAdminTrustMarkTypesCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ListAdminTrustMarkTypesCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ListAdminTrustMarkTypesCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ListAdminTrustMarkTypes, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateAdminTrustMarkTypeCommand>())
class CreateAdminTrustMarkTypeCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = CreateAdminTrustMarkTypeCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), CreateAdminTrustMarkTypeCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.CreateAdminTrustMarkType, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminTrustMarkTypeCommand>())
class ReadAdminTrustMarkTypeCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminTrustMarkTypeCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminTrustMarkTypeCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminTrustMarkType, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReplaceAdminTrustMarkTypeCommand>())
class ReplaceAdminTrustMarkTypeCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReplaceAdminTrustMarkTypeCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReplaceAdminTrustMarkTypeCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReplaceAdminTrustMarkType, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteAdminTrustMarkTypeCommand>())
class DeleteAdminTrustMarkTypeCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = DeleteAdminTrustMarkTypeCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), DeleteAdminTrustMarkTypeCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.DeleteAdminTrustMarkType, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListAdminTrustMarkGrantsCommand>())
class ListAdminTrustMarkGrantsCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ListAdminTrustMarkGrantsCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ListAdminTrustMarkGrantsCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ListAdminTrustMarkGrants, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReplaceAdminTrustMarkGrantCommand>())
class ReplaceAdminTrustMarkGrantCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReplaceAdminTrustMarkGrantCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReplaceAdminTrustMarkGrantCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReplaceAdminTrustMarkGrant, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteAdminTrustMarkGrantCommand>())
class DeleteAdminTrustMarkGrantCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = DeleteAdminTrustMarkGrantCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), DeleteAdminTrustMarkGrantCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.DeleteAdminTrustMarkGrant, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SetAdminTrustMarkGrantStatusCommand>())
class SetAdminTrustMarkGrantStatusCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = SetAdminTrustMarkGrantStatusCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), SetAdminTrustMarkGrantStatusCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.SetAdminTrustMarkGrantStatus, applyDuring(args))
}

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReadAdminTrustMarkTokenCommand>())
class ReadAdminTrustMarkTokenCommandImpl(execution: SessionExecution, private val workflow: AdminWorkflow) :
    TypedServiceCommandAdapter<AdminCommandArgs, AdminCommandResult, AdminError>(
        commandId = ReadAdminTrustMarkTokenCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<AdminCommandArgs>(),
        outputTypeToken = typeToken<AdminCommandResult>(),
    ), ReadAdminTrustMarkTokenCommand {
    override suspend fun doExecute(args: AdminCommandArgs, applyDuring: (AdminCommandArgs) -> AdminCommandArgs):
        IdkResult<AdminCommandResult, AdminError> = workflow.execute(AdminOperation.ReadAdminTrustMarkToken, applyDuring(args))
}

