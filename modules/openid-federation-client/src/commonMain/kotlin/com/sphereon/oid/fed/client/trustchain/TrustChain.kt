package com.sphereon.oid.fed.client.trustchain

import TrustChainResolveResponse
import com.sphereon.oid.fed.client.crypto.ICryptoService
import com.sphereon.oid.fed.client.fetch.IFetchService
import com.sphereon.oid.fed.client.helpers.checkKidInJwks
import com.sphereon.oid.fed.client.helpers.findKeyInJwks
import com.sphereon.oid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.oid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.mapper.mapEntityStatement
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.set

/*
 * TrustChain is a class that implements the logic to resolve and validate a trust chain.
 */
class TrustChain
    (
    private val fetchService: IFetchService,
    private val cryptoService: ICryptoService
) {
    suspend fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int
    ): TrustChainResolveResponse {
        val cache = SimpleCache<String, String>()
        val chain: MutableList<String> = arrayListOf()
        return try {
            val trustChain = buildTrustChainRecursive(entityIdentifier, trustAnchors, chain, cache, 0, maxDepth)
            if (trustChain != null) {
                TrustChainResolveResponse(trustChain, false, null)
            } else {
                TrustChainResolveResponse(null, true, "A Trust chain could not be established")
            }
        } catch (e: Throwable) {
            TrustChainConst.LOG.error("buildTrustChainRecursive failed", e)
            TrustChainResolveResponse(null, true, e.message)
        }
    }

    private suspend fun buildTrustChainRecursive(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        cache: SimpleCache<String, String>,
        depth: Int,
        maxDepth: Int
    ): MutableList<String>? {
        if (depth == maxDepth) return null

        val entityConfigurationJwt = this.fetchService.fetchStatement(
            getEntityConfigurationEndpoint(entityIdentifier)
        )
        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)

        val key = findKeyInJwks(
            decodedEntityConfiguration.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
            decodedEntityConfiguration.header.kid
        )

        if (key == null) return null

        if (!this.cryptoService.verify(entityConfigurationJwt, key)) {
            return null
        }

        val entityStatement: EntityConfigurationStatementDTO =
            mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatementDTO::class) ?: return null

        if (chain.isEmpty()) {
            chain.add(entityConfigurationJwt)
        }

        val authorityHints = entityStatement.authorityHints ?: return null

        val reorderedAuthorityHints = authorityHints.sortedBy { hint ->
            if (trustAnchors.contains(hint)) 0 else 1
        }

        for (authority in reorderedAuthorityHints) {
            val result = processAuthority(
                authority,
                entityIdentifier,
                trustAnchors,
                chain,
                decodedEntityConfiguration.header.kid,
                cache,
                depth + 1,
                maxDepth
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
        cache: SimpleCache<String, String>,
        depth: Int,
        maxDepth: Int
    ): MutableList<String>? {

        try {
            val authorityConfigurationEndpoint = getEntityConfigurationEndpoint(authority)

            // Avoid processing the same entity twice
            if (cache.get(authorityConfigurationEndpoint) != null) return null

            val authorityEntityConfigurationJwt =
                this.fetchService.fetchStatement(
                    authorityConfigurationEndpoint
                )
            cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

            val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)
            val kid = decodedJwt.header.kid

            val key = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
                kid
            )

            if (key == null) return null

            if (!this.cryptoService.verify(
                    authorityEntityConfigurationJwt,
                    key
                )
            ) {
                return null
            }

            val authorityEntityConfiguration: EntityConfigurationStatementDTO =
                mapEntityStatement(authorityEntityConfigurationJwt, EntityConfigurationStatementDTO::class) ?: return null

            val federationEntityMetadata =
                authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
            if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return null

            val authorityEntityFetchEndpoint =
                federationEntityMetadata["federation_fetch_endpoint"]?.jsonPrimitive?.content ?: return null

            val subordinateStatementEndpoint =
                getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)

            val subordinateStatementJwt =
                this.fetchService.fetchStatement(
                    subordinateStatementEndpoint
                )

            val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

            val subordinateStatementKey = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
                    ?: return null,
                decodedSubordinateStatement.header.kid
            )

            if (subordinateStatementKey == null) return null

            if (!this.cryptoService.verify(
                    subordinateStatementJwt,
                    subordinateStatementKey
                )
            ) {
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
                    buildTrustChainRecursive(authority, trustAnchors, chain, cache, depth, maxDepth)
                if (result != null) return result
                chain.removeLast()
            }
        } catch (e: Exception) { // TODO distinguish between remote HTTP errors and internal errors
            TrustChainConst.LOG.error("no trust", e)
            return null
        }

        return null
    }
}

class SimpleCache<K, V> {
    private val cacheMap = mutableMapOf<K, V>()

    fun get(key: K): V? = cacheMap[key]

    fun put(key: K, value: V) {
        cacheMap[key] = value
    }
}
