package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.fetch.Fetch
import com.sphereon.oid.fed.client.fetch.decodeJWTComponents
import com.sphereon.oid.fed.client.fetch.toEntityConfiguration
import com.sphereon.oid.fed.client.fetch.toSubordinateStatement
import io.ktor.client.engine.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.collections.set

class SimpleCache<K, V> {
    private val cacheMap = mutableMapOf<K, V>()

    fun get(key: K): V? = cacheMap[key]

    fun put(key: K, value: V) {
        cacheMap[key] = value
    }
}

class TrustChain(engine: HttpClientEngine) {
    private val fetchClient = Fetch(engine)

    suspend fun resolveTrustChain(entityIdentifier: String, trustAnchors: Array<String>): MutableList<String>? {
        val cache = SimpleCache<String, String>()
        val chain: MutableList<String> = arrayListOf()
        return buildTrustChainRecursive(entityIdentifier, trustAnchors, chain, cache)
    }

    private suspend fun buildTrustChainRecursive(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        cache: SimpleCache<String, String>
    ): MutableList<String>? {

        val entityConfigurationJwt =
            fetchClient.fetchStatement(fetchClient.getEntityConfigurationEndpoint(entityIdentifier)) ?: return null

        if (chain.isEmpty()) {
            chain.add(entityConfigurationJwt)
        }

        val decodedEntityConfiguration = entityConfigurationJwt.decodeJWTComponents()
        val entityConfiguration = entityConfigurationJwt.decodeJWTComponents().toEntityConfiguration()
        val authorityHints = entityConfiguration.authorityHints ?: return null

        for (authority in authorityHints) {
            val authorityConfigurationEndpoint = fetchClient.getEntityConfigurationEndpoint(authority)

            // avoid processing the same entity twice
            if (cache.get(authorityConfigurationEndpoint) != null) {
                continue
            }

            val authorityEntityConfigurationJwt =
                fetchClient.fetchStatement(authorityConfigurationEndpoint) ?: continue

            cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

            val decodedAuthorityEntityConfiguration = authorityEntityConfigurationJwt.decodeJWTComponents()

            val authorityEntityConfiguration = decodedAuthorityEntityConfiguration.toEntityConfiguration()

            val authorityMetadata = authorityEntityConfiguration.metadata

            val federationEntityMetadata =
                authorityMetadata?.get("federation_entity") as JsonObject?

            if (federationEntityMetadata != null) {
                if (!federationEntityMetadata.containsKey("federation_fetch_endpoint")) {
                    continue
                }
            }

            val authorityEntityFetchEndpoint =
                federationEntityMetadata?.get("federation_fetch_endpoint")?.toString()

            val subordinateStatementEndpoint =
                authorityEntityFetchEndpoint?.let { fetchClient.getSubordinateStatementEndpoint(it, entityIdentifier) }
                    ?: continue

            val subordinateStatementJwt =
                fetchClient.fetchStatement(subordinateStatementEndpoint) ?: continue
            val decodedSubordinateStatement = subordinateStatementJwt.decodeJWTComponents()
            val subordinateStatement = decodedSubordinateStatement.toSubordinateStatement()
            val jwks = subordinateStatement["jwks"] as JsonObject

            val keys = jwks["keys"] as JsonArray

            val entityKeyExistsInSubordinateStatement =
                checkKidInJwks(keys, decodedEntityConfiguration.header.kid)

            if (!entityKeyExistsInSubordinateStatement) {
                continue
            }

            if (trustAnchors.contains(authority)) {
                chain.add(subordinateStatementJwt)
                chain.add(authorityEntityConfigurationJwt)
                return chain
            }

            if ((authorityEntityConfiguration.authorityHints)?.isEmpty() == true) {
                continue
            }

            chain.add(subordinateStatementJwt)

            val result = buildTrustChainRecursive(authority, trustAnchors, chain, cache)
            if (result != null) {
                return result
            } else {
                chain.dropLast(1)
            }
        }

        return null
    }

    private fun checkKidInJwks(keys: JsonArray, kid: String): Boolean {
        for (keyElement in keys) {
            val keyObj = keyElement.jsonObject
            if (kid == keyObj["kid"].toString().trim('"')) {
                return true
            }
        }
        return false
    }
}
