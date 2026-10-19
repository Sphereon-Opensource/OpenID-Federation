package com.sphereon.openid.fed.admin

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.error.IdkErrorType
import com.sphereon.core.api.service.ServiceCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Already-authorized scope. HTTP bodies must never be deserialized into this object. */
@Serializable
data class AdminNodeScope(val accountId: String, val entityId: String? = null, val basePath: String = "/admin/v1", val subordinateLimit: Long? = null)

@Serializable
data class AdminCommandArgs(
    val scope: AdminNodeScope,
    val resourceId: String? = null,
    val subjectId: String? = null,
    val document: JsonObject? = null,
    val ifMatch: String? = null,
    val query: Map<String, String> = emptyMap(),
    val activationContext: JsonObject? = null,
)

@Serializable
data class AdminCommandResult(
    val document: JsonObject? = null,
    val etag: String? = null,
    val artifact: String? = null,
    val mediaType: String = "application/json",
    val status: Int = 200,
    val location: String? = null,
    val stageDigest: String? = null,
)

/** Typed protocol failure; transport adapters serialize this as RFC 9457 problem JSON. */
data class AdminError(val token: String, val status: Int, val detail: String, val pointer: String? = null) : IdkErrorType {
    override val code: String get() = token
    override val severity: IdkError.Severity get() = IdkError.Severity.ERROR
    override val exception: Throwable? get() = null
    override val causes: List<IdkErrorType> get() = emptyList()
    override val meta: Map<String, Any?> get() = buildMap {
        put("oidfedAdminToken", token); put("oidfedAdminStatus", status)
        pointer?.let { put("oidfedAdminPointer", it) }
    }
    override val message: IdkError.Message get() = IdkError.Message(
        i18nKey = "oidfed.admin.$token", defaultMessage = detail,
    )
}

interface AdminWorkflow {
    /** Internal owner-scoped maintenance; never a standard Node read or publication operation. */
    suspend fun recoverKeyPreparations(scope: AdminNodeScope, abortPending: Boolean = false): IdkResult<Unit, AdminError> = com.sphereon.core.api.Ok(Unit)

    suspend fun execute(operation: AdminOperation, args: AdminCommandArgs): IdkResult<AdminCommandResult, AdminError>
    suspend fun stage(operation: AdminOperation, args: AdminCommandArgs): IdkResult<AdminCommandResult, AdminError>
    suspend fun finalizeStage(scope: AdminNodeScope, digest: String, expectedStageEtag: String? = null): IdkResult<AdminCommandResult, AdminError>
    suspend fun stagedPublication(scope: AdminNodeScope, digest: String): IdkResult<AdminStagedPublication?, AdminError>
    suspend fun pendingStages(scope: AdminNodeScope): IdkResult<List<AdminStagedPublication>, AdminError>
    /** Internal host operation: null selects the newest active signing-capable key. */
    suspend fun selectSigningKey(scope: AdminNodeScope, keyId: String?, expectedRevision: Long): IdkResult<AdminCommandResult, AdminError>
    suspend fun withdrawSubordinate(scope: AdminNodeScope, entityId: String, edgeId: String? = null, expectedIntentVersion: Long? = null): IdkResult<AdminCommandResult, AdminError>
}

@Serializable
data class AdminStagedPublication(val digest: String, val collection: String, val resourceId: String, val subjectId: String, val artifact: String, val activationContext: JsonObject? = null, val committed: Boolean = false, val stageEtag: String? = null)

/** Relative path catalog, shared by both HTTP mounts and command registration. */
@Serializable
enum class AdminOperation(val commandId: String, val method: String, val path: String) {
    ReadAdminNode("fed.admin-node.read", "GET", ""),
    ListAdminKeys("fed.admin-keys.list", "GET", "/keys"),
    CreateAdminKey("fed.admin-keys.create", "POST", "/keys"),
    ReadAdminKey("fed.admin-keys.read", "GET", "/keys/{kid}"),
    DeleteAdminKey("fed.admin-keys.delete", "DELETE", "/keys/{kid}"),
    RotateAdminKey("fed.admin-keys.rotate", "POST", "/keys/{kid}/rotate"),
    ReadAdminConfiguration("fed.admin-configuration.read", "GET", "/configuration"),
    ReplaceAdminConfiguration("fed.admin-configuration.replace", "PUT", "/configuration"),
    PatchAdminConfiguration("fed.admin-configuration.patch", "PATCH", "/configuration"),
    ReadAdminConfigurationStatement("fed.admin-configuration.statement", "GET", "/configuration/statement"),
    ListAdminSubordinates("fed.admin-subordinates.list", "GET", "/subordinates"),
    CreateAdminSubordinate("fed.admin-subordinates.create", "POST", "/subordinates"),
    ReadAdminSubordinate("fed.admin-subordinates.read", "GET", "/subordinates/{entity_id}"),
    ReplaceAdminSubordinate("fed.admin-subordinates.replace", "PUT", "/subordinates/{entity_id}"),
    PatchAdminSubordinate("fed.admin-subordinates.patch", "PATCH", "/subordinates/{entity_id}"),
    DeleteAdminSubordinate("fed.admin-subordinates.delete", "DELETE", "/subordinates/{entity_id}"),
    SetAdminSubordinateStatus("fed.admin-subordinates.status", "PUT", "/subordinates/{entity_id}/status"),
    ReadAdminSubordinateStatement("fed.admin-subordinates.statement", "GET", "/subordinates/{entity_id}/statement"),
    ListAdminTrustMarkTypes("fed.admin-trust-marks.list", "GET", "/trust-marks"),
    CreateAdminTrustMarkType("fed.admin-trust-marks.create", "POST", "/trust-marks"),
    ReadAdminTrustMarkType("fed.admin-trust-marks.read", "GET", "/trust-marks/{type}"),
    ReplaceAdminTrustMarkType("fed.admin-trust-marks.replace", "PUT", "/trust-marks/{type}"),
    DeleteAdminTrustMarkType("fed.admin-trust-marks.delete", "DELETE", "/trust-marks/{type}"),
    ListAdminTrustMarkGrants("fed.admin-trust-mark-subjects.list", "GET", "/trust-marks/{type}/subjects"),
    ReplaceAdminTrustMarkGrant("fed.admin-trust-mark-subjects.replace", "PUT", "/trust-marks/{type}/subjects/{entity_id}"),
    DeleteAdminTrustMarkGrant("fed.admin-trust-mark-subjects.delete", "DELETE", "/trust-marks/{type}/subjects/{entity_id}"),
    SetAdminTrustMarkGrantStatus("fed.admin-trust-mark-subjects.status", "PUT", "/trust-marks/{type}/subjects/{entity_id}/status"),
    ReadAdminTrustMarkToken("fed.admin-trust-mark-subjects.token", "GET", "/trust-marks/{type}/subjects/{entity_id}/token"),
}

interface ReadAdminNodeCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-node.read" }
}

interface ListAdminKeysCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-keys.list" }
}

interface CreateAdminKeyCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-keys.create" }
}

interface ReadAdminKeyCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-keys.read" }
}

interface DeleteAdminKeyCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-keys.delete" }
}

interface RotateAdminKeyCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-keys.rotate" }
}

interface ReadAdminConfigurationCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-configuration.read" }
}

interface ReplaceAdminConfigurationCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-configuration.replace" }
}

interface PatchAdminConfigurationCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-configuration.patch" }
}

interface ReadAdminConfigurationStatementCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-configuration.statement" }
}

interface ListAdminSubordinatesCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.list" }
}

interface CreateAdminSubordinateCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.create" }
}

interface ReadAdminSubordinateCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.read" }
}

interface ReplaceAdminSubordinateCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.replace" }
}

interface PatchAdminSubordinateCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.patch" }
}

interface DeleteAdminSubordinateCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.delete" }
}

interface SetAdminSubordinateStatusCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.status" }
}

interface ReadAdminSubordinateStatementCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-subordinates.statement" }
}

interface ListAdminTrustMarkTypesCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-marks.list" }
}

interface CreateAdminTrustMarkTypeCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-marks.create" }
}

interface ReadAdminTrustMarkTypeCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-marks.read" }
}

interface ReplaceAdminTrustMarkTypeCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-marks.replace" }
}

interface DeleteAdminTrustMarkTypeCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-marks.delete" }
}

interface ListAdminTrustMarkGrantsCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-mark-subjects.list" }
}

interface ReplaceAdminTrustMarkGrantCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-mark-subjects.replace" }
}

interface DeleteAdminTrustMarkGrantCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-mark-subjects.delete" }
}

interface SetAdminTrustMarkGrantStatusCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-mark-subjects.status" }
}

interface ReadAdminTrustMarkTokenCommand : ServiceCommand<AdminCommandArgs, AdminCommandResult, AdminError> {
    companion object { const val COMMAND_ID = "fed.admin-trust-mark-subjects.token" }
}

