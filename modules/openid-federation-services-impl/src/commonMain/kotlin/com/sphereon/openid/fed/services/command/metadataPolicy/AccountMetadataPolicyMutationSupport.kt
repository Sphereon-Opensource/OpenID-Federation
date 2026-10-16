package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.core.api.http.query.QueryParamUtils
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun policyRefusal(reason: String): Nothing = throw FederationErrorException(InvalidRequestError(reason))

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun policyAccountId(input: String): String = QueryParamUtils.parseUuid(input)?.toString()
    ?: policyRefusal("Account ID must be a UUID")

/** Call only inside the owning synchronous account transaction, before any policy/fence reads. */
internal fun lockPolicyAccount(accounts: AccountQueries, accountId: String, expectedIdentifier: String? = null): com.sphereon.openid.fed.persistence.models.Account {
    val account = accounts.findActiveForUpdate(accountId).executeAsOneOrNull()
        ?: throw FederationErrorException(AccountNotFoundError(accountId))
    if (expectedIdentifier != null && (expectedIdentifier.isBlank() || account.identifier != expectedIdentifier)) {
        policyRefusal("Account Entity Identifier does not match the expected superior")
    }
    return account
}

internal fun validateRolePolicy(role: String, policy: JsonObject?) {
    if (role.isBlank()) policyRefusal("Entity Type Identifier must not be blank")
    if (policy == null) return
    val checked = MetadataPolicyOperators.mergePolicies(JsonObject(emptyMap()), JsonObject(mapOf(role to policy)))
    if (checked is MetadataPolicyOperators.PolicyMergeResult.Error) policyRefusal("Invalid metadata policy for $role: ${checked.reason}")
}

internal fun effectiveRolePolicy(policies: MetadataPolicyQueries, accountId: String, role: String): JsonObject? =
    policies.findByAccountId(accountId).executeAsList().lastOrNull { it.key == role }
        ?.let { Json.parseToJsonElement(it.policy).jsonObject }

internal fun policyState(accounts: com.sphereon.openid.fed.persistence.models.Account, role: String,
    policies: MetadataPolicyQueries, revisions: AccountMetadataPolicyRevisionQueries): AccountMetadataPolicySnapshot {
    val fence = revisions.findByAccountIdAndEntityType(accounts.id, role).executeAsOneOrNull()
    return AccountMetadataPolicySnapshot(accounts.id, accounts.identifier.orEmpty(), role, fence?.revision ?: 0L,
        effectiveRolePolicy(policies, accounts.id, role), fence?.last_operation_generation)
}

internal fun advanceLegacyPolicyRevision(revisions: AccountMetadataPolicyRevisionQueries, accountId: String, role: String) {
    val old = revisions.findByAccountIdAndEntityType(accountId, role).executeAsOneOrNull()
    val revision = old?.revision ?: 0L
    if (revision == Long.MAX_VALUE) policyRefusal("Metadata policy revision is exhausted")
    val saved = revisions.upsert(accountId, role, revision + 1, old?.last_operation_generation, null, null).executeAsOne()
    if (saved.revision != revision + 1 || saved.account_id != accountId || saved.entity_type != role) {
        policyRefusal("Metadata policy revision write returned inconsistent coordinates")
    }
}
