package com.sphereon.oid.fed.client.trustchain

import com.sphereon.oid.fed.client.crypto.ICryptoCallbackMarkerType
import com.sphereon.oid.fed.client.crypto.cryptoService
import com.sphereon.oid.fed.client.crypto.findKeyInJwks
import com.sphereon.oid.fed.client.fetch.IFetchCallbackMarkerType
import com.sphereon.oid.fed.client.fetch.fetchService
import com.sphereon.oid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.oid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.mapper.mapEntityStatement
import com.sphereon.oid.fed.client.service.DefaultCallbacks
import com.sphereon.oid.fed.client.service.ICallbackService
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.set
import kotlin.js.JsExport

expect interface ITrustChainCallbackMarkerType
interface ITrustChainMarkerType

@JsExport.Ignore
interface ITrustChainCallbackService: ITrustChainMarkerType {
    suspend fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int = 5
    ): MutableList<String>?
}

@JsExport.Ignore
interface ITrustChainService: ITrustChainMarkerType {
    suspend fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int = 5
    ): MutableList<String>?
}

expect fun trustChainService(platformCallback: ITrustChainCallbackMarkerType = DefaultCallbacks.trustChainService()): ITrustChainService

abstract class AbstractTrustChainService<CallbackServiceType>(open val platformCallback: CallbackServiceType): ICallbackService<CallbackServiceType> {
    private var disabled = false

    override fun isEnabled(): Boolean {
        return !this.disabled
    }

    override fun disable() = apply {
        this.disabled = true
    }

    override fun enable() = apply {
        this.disabled = false
    }

    protected fun assertEnabled() {
        if (!isEnabled()) {
            TrustChainConst.LOG.info("TRUST CHAIN verify has been disabled")
            throw IllegalStateException("TRUST CHAIN service is disable; cannot verify")
        } else if (this.platformCallback == null) {
            TrustChainConst.LOG.error("TRUST CHAIN callback is not registered")
            throw IllegalStateException("TRUST CHAIN has not been initialized. Please register your TrustChainCallback implementation, or register a default implementation")
        }
    }
}

class TrustChainService(override val platformCallback: ITrustChainCallbackService = DefaultCallbacks.trustChainService()): AbstractTrustChainService<ITrustChainCallbackService>(platformCallback), ITrustChainService {

    override fun platform(): ITrustChainCallbackService {
        return this.platformCallback
    }

    override suspend fun resolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): MutableList<String>? {
        assertEnabled()
        return platformCallback.resolve(entityIdentifier, trustAnchors, maxDepth)
    }
}

class SimpleCache<K, V> {
    private val cacheMap = mutableMapOf<K, V>()

    fun get(key: K): V? = cacheMap[key]

    fun put(key: K, value: V) {
        cacheMap[key] = value
    }
}

class DefaultTrustChainImpl(private val fetchService: IFetchCallbackMarkerType?, private val cryptoService: ICryptoCallbackMarkerType?): ITrustChainCallbackService, ITrustChainCallbackMarkerType {
    override suspend fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int
    ): MutableList<String>? {
        val cache = SimpleCache<String, String>()
        val chain: MutableList<String> = arrayListOf()
        return try {
            buildTrustChainRecursive(entityIdentifier, trustAnchors, chain, cache, 0, maxDepth)
        } catch (_: Exception) {
            // Log error
            null
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
        if(depth == maxDepth) return null

        val entityConfigurationJwt = fetchService(fetchService ?: DefaultCallbacks.fetchService()).fetchStatement(getEntityConfigurationEndpoint(entityIdentifier))
        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)

        val key = findKeyInJwks(
            decodedEntityConfiguration.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
            decodedEntityConfiguration.header.kid
        )

        if (key == null) return null

        if (!cryptoService(this.cryptoService ?: DefaultCallbacks.jwtService()).verify(entityConfigurationJwt, key)) {
            return null
        }

        val entityStatement: EntityConfigurationStatement =
            mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: return null

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

            val authorityEntityConfigurationJwt = fetchService(this.fetchService ?: DefaultCallbacks.fetchService()).fetchStatement(authorityConfigurationEndpoint)
            cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

            val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)
            val kid = decodedJwt.header.kid

            val key = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
                kid
            )

            if (key == null) return null

            if (!cryptoService(this.cryptoService ?: DefaultCallbacks.jwtService()).verify(
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

            val subordinateStatementJwt = fetchService(this.fetchService ?: DefaultCallbacks.fetchService()).fetchStatement(subordinateStatementEndpoint)

            val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

            val subordinateStatementKey = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
                    ?: return null,
                decodedSubordinateStatement.header.kid
            )

            if (subordinateStatementKey == null) return null

            if (!cryptoService(this.cryptoService ?: DefaultCallbacks.jwtService()).verify(subordinateStatementJwt, subordinateStatementKey)) {
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
