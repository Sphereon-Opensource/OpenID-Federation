package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.client.httpclient.OidFederationClient
import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.ExperimentalJsExport

expect class TrustChainValidation {
    fun validateTrustChains(
        jwts: List<List<String>>,
        knownTrustChainIds: List<String>
    ): List<List<Any>>
}


class TrustChainValidationCommon {

    @ExperimentalJsExport
    suspend fun readAuthorityHints(
        partyBId: String,
        engine: HttpClientEngine,
        trustChains: MutableList<List<EntityConfigurationStatement>> = mutableListOf(),
        trustChain: MutableSet<EntityConfigurationStatement> = mutableSetOf()
    ): List<List<EntityConfigurationStatement>> {
        OidFederationClient(engine).fetchEntityStatement(partyBId).run {
            JsonMapper().mapEntityConfigurationStatement(this).let {
                if (it.authorityHints.isNullOrEmpty()) {
                    trustChain.add(it)
                    trustChains.add(trustChain.map { content -> content.copy() })
                    trustChain.last().also { trustChain.remove(it) }
                } else {
                    it.authorityHints?.forEach { hint ->
                        trustChain.add(it)
                        readAuthorityHints(
                            hint,
                            engine,
                            trustChains,
                            trustChain
                        )
                    }
                }
            }
        }
        return trustChains
    }

    suspend fun fetchSubordinateStatements(
        entityConfigurationStatementsList: List<List<EntityConfigurationStatement>>,
        engine: HttpClientEngine
    ): List<List<String>> {
        val trustChains: MutableList<List<String>> = mutableListOf()
        val trustChain: MutableList<String> = mutableListOf()
        entityConfigurationStatementsList.forEach { entityConfigurationStatements ->
            entityConfigurationStatements.forEach { it ->
                it.metadata?.jsonObject?.get("federation_entity")?.jsonObject?.get("federation_fetch_endpoint")?.jsonPrimitive?.content.let { url ->
                    OidFederationClient(engine).fetchEntityStatement(url.toString()).run {
                        trustChain.add(this)
                    }
                }
            }
            trustChains.add(trustChain.map { content -> content.substring(0) })
            trustChain.clear()
        }
        return trustChains
    }
}
