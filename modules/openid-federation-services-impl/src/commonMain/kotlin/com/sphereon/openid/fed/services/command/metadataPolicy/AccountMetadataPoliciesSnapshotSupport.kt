package com.sphereon.openid.fed.services.command.metadataPolicy

import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class CapturedAccountMetadataPolicies(val rows: List<Pair<String, JsonObject>>, val snapshot: AccountMetadataPoliciesSnapshot)

/** All body rows and all durable role revisions are read while holding the same canonical account lock. */
internal fun captureAccountMetadataPolicies(accounts: AccountQueries, policies: MetadataPolicyQueries,
    revisions: AccountMetadataPolicyRevisionQueries, accountId: String, expectedIdentifier: String): CapturedAccountMetadataPolicies =
    accounts.transactionWithResult {
        val account = lockPolicyAccount(accounts, accountId, expectedIdentifier)
        val rows = policies.findByAccountId(account.id).executeAsList().map { it.key to Json.parseToJsonElement(it.policy).jsonObject }
        val versions = rows.associate { it.first to 0L }.toMutableMap()
        val generations = rows.associate { it.first to null as Long? }.toMutableMap()
        revisions.findByAccountId(account.id).executeAsList().forEach {
            versions[it.entity_type] = it.revision
            generations[it.entity_type] = it.last_operation_generation
        }
        CapturedAccountMetadataPolicies(rows, AccountMetadataPoliciesSnapshot(account.id, account.identifier.orEmpty(),
            JsonObject(rows.toMap()), versions.toMap(), generations.toMap()))
    }
