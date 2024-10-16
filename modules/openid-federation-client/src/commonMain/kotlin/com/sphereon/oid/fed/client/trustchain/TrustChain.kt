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
import kotlinx.serialization.json.JsonObject
import kotlin.collections.set

class SimpleCache<K, V> {
    private val cacheMap = mutableMapOf<K, V>()

    fun get(key: K): V? = cacheMap[key]

    fun put(key: K, value: V) {
        cacheMap[key] = value
    }
}

suspend fun resolve(
    entityIdentifier: String,
    trustAnchors: Array<String>,
    fetchService: IFetchCallbackService,
    cryptoService: ICryptoCallbackService
): MutableList<String>? {
    val cache = SimpleCache<String, String>()
    val chain: MutableList<String> = arrayListOf()
    return buildTrustChainRecursive(entityIdentifier, trustAnchors, chain, cache, fetchService, cryptoService)
}

private suspend fun buildTrustChainRecursive(
    entityIdentifier: String,
    trustAnchors: Array<String>,
    chain: MutableList<String>,
    cache: SimpleCache<String, String>,
    fetchService: IFetchCallbackService,
    cryptoService: ICryptoCallbackService
): MutableList<String>? {

    val entityConfigurationJwt =
        fetchService.fetchStatement(getEntityConfigurationEndpoint(entityIdentifier))

    val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)

    if (!cryptoService.verify(entityConfigurationJwt)) {
        return null
    }

    val entityStatement: EntityConfigurationStatement =
        mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: return null

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
                cache,
                fetchService,
                cryptoService
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
    fetchService: IFetchCallbackService,
    cryptoService: ICryptoCallbackService
): MutableList<String>? {

    try {
        val authorityConfigurationEndpoint = getEntityConfigurationEndpoint(authority)

        // Avoid processing the same entity twice
        if (cache.get(authorityConfigurationEndpoint) != null) return null

        val authorityEntityConfigurationJwt =
            fetchService.fetchStatement(authorityConfigurationEndpoint)
        cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

        if (!cryptoService.verify(authorityEntityConfigurationJwt)) {
            return null
        }

        val authorityEntityConfiguration: EntityConfigurationStatement =
            mapEntityStatement(authorityEntityConfigurationJwt, EntityConfigurationStatement::class)
                ?: return null

        val federationEntityMetadata =
            authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
        if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return null

        val authorityEntityFetchEndpoint =
            federationEntityMetadata["federation_fetch_endpoint"]?.toString()?.trim('"') ?: return null

        val subordinateStatementEndpoint =
            getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)

        val subordinateStatementJwt = fetchService.fetchStatement(subordinateStatementEndpoint)

        if (!cryptoService.verify(subordinateStatementJwt)) {
            return null
        }

        val subordinateStatement: SubordinateStatement =
            mapEntityStatement(subordinateStatementJwt, SubordinateStatement::class)
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
            val result = buildTrustChainRecursive(authority, trustAnchors, chain, cache, fetchService, cryptoService)
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