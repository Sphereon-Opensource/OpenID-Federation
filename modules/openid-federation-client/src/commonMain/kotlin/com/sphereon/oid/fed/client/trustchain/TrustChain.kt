package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.fetch.Fetch
import com.sphereon.oid.fed.client.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.*
import kotlinx.serialization.json.JsonObject
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
    private val mapper = JsonMapper()

    suspend fun resolve(entityIdentifier: String, trustAnchors: Array<String>): MutableList<String>? {
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

        val decodedEntityConfiguration = mapper.decodeJWTComponents(entityConfigurationJwt)

        // need to verify JWT

        val entityStatement: EntityConfigurationStatement =
            mapper.mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: return null

        if (chain.isEmpty()) {
            chain.add(entityConfigurationJwt)
        }

        val authorityHints = entityStatement.authorityHints ?: return null

        for (authority in authorityHints) {
            val result =
                processAuthority(
                    authority,
                    entityIdentifier,
                    trustAnchors,
                    chain,
                    decodedEntityConfiguration.header.kid,
                    cache
                )
            if (result != null) {
                return result
            }
        }

        return null
    }

    private suspend fun processAuthority(
        authority: String,
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        lastStatementKid: String,
        cache: SimpleCache<String, String>
    ): MutableList<String>? {

        try {
            val authorityConfigurationEndpoint = fetchClient.getEntityConfigurationEndpoint(authority)

            // Avoid processing the same entity twice
            if (cache.get(authorityConfigurationEndpoint) != null) return null

            val authorityEntityConfigurationJwt =
                fetchClient.fetchStatement(authorityConfigurationEndpoint) ?: return null
            cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

            val authorityEntityConfiguration: EntityConfigurationStatement =
                mapper.mapEntityStatement(authorityEntityConfigurationJwt, EntityConfigurationStatement::class)
                    ?: return null

            val federationEntityMetadata =
                authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
            if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return null

            val authorityEntityFetchEndpoint =
                federationEntityMetadata["federation_fetch_endpoint"]?.toString()?.trim('"') ?: return null

            val subordinateStatementEndpoint =
                fetchClient.getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)
                    ?: return null

            val subordinateStatementJwt = fetchClient.fetchStatement(subordinateStatementEndpoint) ?: return null
            val subordinateStatement: SubordinateStatement =
                mapper.mapEntityStatement(subordinateStatementJwt, SubordinateStatement::class)
                    ?: return null

            val jwks = subordinateStatement.jwks
            val keys = jwks.propertyKeys ?: return null

            // Check if the entity key exists in subordinate statement
            val entityKeyExistsInSubordinateStatement = checkKidInJwks(keys, lastStatementKid)
            if (!entityKeyExistsInSubordinateStatement) return null
            // If authority is in trust anchors, return the completed chain
            if (trustAnchors.contains(authority)) {
                chain.add(subordinateStatementJwt)
                chain.add(authorityEntityConfigurationJwt)
                return chain
            }

            // Recursively build trust chain if there are authority hints
            if (authorityEntityConfiguration.authorityHints?.isNotEmpty() == true) {
                chain.add(subordinateStatementJwt)
                val result = buildTrustChainRecursive(authority, trustAnchors, chain, cache)
                if (result != null) return result
                chain.removeLast()
            }
        } catch (_: Exception) {
            return null
        }

        return null
    }

    private fun checkKidInJwks(keys: Array<Jwk>, kid: String): Boolean {
        for (key in keys) {
            if (key.kid == kid) {
                return true
            }
        }
        return false
    }
}
