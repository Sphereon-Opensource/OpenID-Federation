package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.crypto.ICryptoCallbackService
import com.sphereon.oid.fed.client.fetch.IFetchCallbackService
import com.sphereon.oid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.oid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.mapper.mapEntityStatement
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.set

class SimpleCache<K, V> {
    private val cacheMap = mutableMapOf<K, V>()

    fun get(key: K): V? = cacheMap[key]

    fun put(key: K, value: V) {
        cacheMap[key] = value
    }
}

class TrustChain(private val fetchService: IFetchCallbackService, private val cryptoService: ICryptoCallbackService) {
    suspend fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>
    ): MutableList<String>? {
        val cache = SimpleCache<String, String>()
        val chain: MutableList<String> = arrayListOf()
        return try {
            buildTrustChainRecursive(entityIdentifier, trustAnchors, chain, cache)
        } catch (_: Exception) {
            // Log error
            null
        }
    }

    private fun findKeyInJwks(keys: JsonArray, kid: String): Jwk? {
        val key = keys.firstOrNull { it.jsonObject["kid"]?.jsonPrimitive?.content == kid }

        if (key == null) return null

        return Json.decodeFromJsonElement(Jwk.serializer(), key)
    }

    private suspend fun buildTrustChainRecursive(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        cache: SimpleCache<String, String>
    ): MutableList<String>? {
        val entityConfigurationJwt = this.fetchService.fetchStatement(getEntityConfigurationEndpoint(entityIdentifier))
        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)

        val key = findKeyInJwks(
            decodedEntityConfiguration.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
            decodedEntityConfiguration.header.kid
        )

        if (key == null) return null

        if (!cryptoService.verify(entityConfigurationJwt, key)) {
            return null
        }

        val entityStatement: EntityConfigurationStatement =
            mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: return null

        if (chain.isEmpty()) {
            chain.add(entityConfigurationJwt)
        }

        val authorityHints = entityStatement.authorityHints ?: return null

        for (authority in authorityHints) {
            val result = processAuthority(
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
            val authorityConfigurationEndpoint = getEntityConfigurationEndpoint(authority)

            // Avoid processing the same entity twice
            if (cache.get(authorityConfigurationEndpoint) != null) return null

            val authorityEntityConfigurationJwt = fetchService.fetchStatement(authorityConfigurationEndpoint)
            cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

            val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)
            val kid = decodedJwt.header.kid

            val key = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
                kid
            )

            if (key == null) return null

            if (!cryptoService.verify(
                    authorityEntityConfigurationJwt,
                    key
                )
            ) {
                return null
            }

            val authorityEntityConfiguration: EntityConfigurationStatement =
                mapEntityStatement(authorityEntityConfigurationJwt, EntityConfigurationStatement::class) ?: return null

            val federationEntityMetadata =
                authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
            if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return null

            val authorityEntityFetchEndpoint =
                federationEntityMetadata["federation_fetch_endpoint"]?.jsonPrimitive?.content ?: return null

            val subordinateStatementEndpoint =
                getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)

            val subordinateStatementJwt = fetchService.fetchStatement(subordinateStatementEndpoint)

            val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

            val subordinateStatementKey = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
                    ?: return null,
                decodedSubordinateStatement.header.kid
            )

            if (subordinateStatementKey == null) return null

            if (!cryptoService.verify(subordinateStatementJwt, subordinateStatementKey)) {
                return null
            }

            val subordinateStatement: SubordinateStatement =
                mapEntityStatement(subordinateStatementJwt, SubordinateStatement::class) ?: return null

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
                val result =
                    buildTrustChainRecursive(authority, trustAnchors, chain, cache)
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
