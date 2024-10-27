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
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise

@JsExport
interface ITrustChainCallbackServiceJS : ITrustChainCallbackMarkerType {
    fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int = 5
    ): Promise<Array<String>?>
}

@JsExport.Ignore
interface ITrustChainServiceJS : ITrustChainMarkerType {
    fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int = 5
    ): Promise<Array<String>?>
}

private const val TRUST_CHAIN_SERVICE_JS_SCOPE = "TrustChainServiceJS"

@JsExport
class TrustChainServiceJS(override val platformCallback: ITrustChainCallbackServiceJS = DefaultCallbacks.trustChainService()) :
    AbstractTrustChainService<ITrustChainCallbackServiceJS>(platformCallback), ITrustChainServiceJS {

    override fun platform(): ITrustChainCallbackServiceJS {
        return this.platformCallback
    }

    override fun resolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): Promise<Array<String>?> {
        return CoroutineScope(context = CoroutineName(TRUST_CHAIN_SERVICE_JS_SCOPE)).async {
            return@async platformCallback.resolve(entityIdentifier, trustAnchors, maxDepth).await()
        }.asPromise()
    }
}

class TrustChainServiceJSAdapter(val trustChainCallbackJS: TrustChainServiceJS = TrustChainServiceJS()) :
    AbstractTrustChainService<ITrustChainCallbackServiceJS>(trustChainCallbackJS.platformCallback), ITrustChainService {

    override fun platform(): ITrustChainCallbackServiceJS = trustChainCallbackJS.platformCallback

    override suspend fun resolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): MutableList<String>? =
        this.trustChainCallbackJS.resolve(entityIdentifier, trustAnchors, maxDepth).await()?.toMutableList()
}

@JsExport.Ignore
actual fun trustChainService(platformCallback: ITrustChainCallbackMarkerType): ITrustChainService {
    val jsPlatformCallback = platformCallback.unsafeCast<ITrustChainCallbackServiceJS>()
    if (jsPlatformCallback === undefined) {
        throw IllegalStateException("Invalid platform callback supplied: Needs to be of type ITrustChainCallbackServiceJS, but is of type ${platformCallback::class::simpleName} instead")
    }
    return TrustChainServiceJSAdapter(TrustChainServiceJS(jsPlatformCallback))
}

@JsExport
actual external interface ITrustChainCallbackMarkerType

@JsExport
class DefaultTrustChainJSImpl(
    private val fetchService: IFetchCallbackMarkerType? = DefaultCallbacks.fetchService(),
    private val cryptoService: ICryptoCallbackMarkerType? = DefaultCallbacks.jwtService()
) : ITrustChainCallbackServiceJS, ITrustChainCallbackMarkerType {
    override fun resolve(
        entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int
    ): Promise<Array<String>?> = CoroutineScope(context = CoroutineName(TRUST_CHAIN_SERVICE_JS_SCOPE)).async {
        val cache = SimpleCache<String, String>()
        val chain: MutableList<String> = arrayListOf()
        return@async try {
            buildTrustChainRecursive(entityIdentifier, trustAnchors, chain, cache, 0, maxDepth).await()
        } catch (_: Exception) {
            // Log error
            null
        }
    }.asPromise()

    private fun buildTrustChainRecursive(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        cache: SimpleCache<String, String>,
        depth: Int,
        maxDepth: Int
    ): Promise<Array<String>?> = CoroutineScope(context = CoroutineName(TRUST_CHAIN_SERVICE_JS_SCOPE)).async {
        if (depth == maxDepth) return@async null

        val entityConfigurationJwt = fetchService(fetchService ?: DefaultCallbacks.fetchService()).fetchStatement(
            getEntityConfigurationEndpoint(entityIdentifier)
        )

        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)

        val key = findKeyInJwks(
            decodedEntityConfiguration.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return@async null,
            decodedEntityConfiguration.header.kid
        )

        if (key == null) return@async null

        if (!cryptoService(cryptoService ?: DefaultCallbacks.jwtService()).verify(entityConfigurationJwt, key)) {
            return@async null
        }

        val entityStatement: EntityConfigurationStatement =
            mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatement::class) ?: return@async null

        if (chain.isEmpty()) {
            chain[chain.size] = entityConfigurationJwt
        }

        val authorityHints = entityStatement.authorityHints ?: return@async null

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
            ).await()

            if (result != null) {
                return@async result
            }
        }

        return@async null
    }.asPromise()

    private fun processAuthority(
        authority: String,
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        lastStatementKid: String,
        cache: SimpleCache<String, String>,
        depth: Int,
        maxDepth: Int
    ): Promise<Array<String>?> = CoroutineScope(context = CoroutineName(TRUST_CHAIN_SERVICE_JS_SCOPE)).async {
        try {
            val authorityConfigurationEndpoint = getEntityConfigurationEndpoint(authority)

            // Avoid processing the same entity twice
            if (cache.get(authorityConfigurationEndpoint) != null) return@async null

            val authorityEntityConfigurationJwt =
                fetchService(fetchService ?: DefaultCallbacks.fetchService()).fetchStatement(
                    authorityConfigurationEndpoint
                )
            cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

            val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)
            val kid = decodedJwt.header.kid

            val key = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return@async null,
                kid
            )

            if (key == null) return@async null

            if (!cryptoService(cryptoService ?: DefaultCallbacks.jwtService()).verify(
                    authorityEntityConfigurationJwt,
                    key
                )
            ) {
                return@async null
            }

            val authorityEntityConfiguration: EntityConfigurationStatement =
                mapEntityStatement(authorityEntityConfigurationJwt, EntityConfigurationStatement::class)
                    ?: return@async null

            val federationEntityMetadata =
                authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
            if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return@async null

            val authorityEntityFetchEndpoint =
                federationEntityMetadata["federation_fetch_endpoint"]?.jsonPrimitive?.content ?: return@async null

            val subordinateStatementEndpoint =
                getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)

            val subordinateStatementJwt =
                fetchService(fetchService ?: DefaultCallbacks.fetchService()).fetchStatement(
                    subordinateStatementEndpoint
                )

            val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

            val subordinateStatementKey = findKeyInJwks(
                decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray
                    ?: return@async null,
                decodedSubordinateStatement.header.kid
            )

            if (subordinateStatementKey == null) return@async null

            if (!cryptoService(cryptoService ?: DefaultCallbacks.jwtService()).verify(
                    subordinateStatementJwt,
                    subordinateStatementKey
                )
            ) {
                return@async null
            }

            val subordinateStatement: SubordinateStatement =
                mapEntityStatement(subordinateStatementJwt, SubordinateStatement::class) ?: return@async null

            val jwks = subordinateStatement.jwks
            val keys = jwks.propertyKeys ?: return@async null

            // Check if the entity key exists in subordinate statement
            val entityKeyExistsInSubordinateStatement = checkKidInJwks(keys, lastStatementKid).await()
            if (!entityKeyExistsInSubordinateStatement) return@async null

            // If authority is in trust anchors, return the completed chain
            if (trustAnchors.contains(authority)) {
                chain.add(subordinateStatementJwt)
                chain.add(authorityEntityConfigurationJwt)
                return@async chain.toTypedArray()
            }

            // Recursively build trust chain if there are authority hints
            if (authorityEntityConfiguration.authorityHints?.isNotEmpty() == true) {
                chain.add(subordinateStatementJwt)
                val result =
                    buildTrustChainRecursive(authority, trustAnchors, chain, cache, depth, maxDepth).await()
                if (result != null) return@async result
                chain.removeLast()
            }
        } catch (_: Exception) {
            return@async null
        }

        return@async null
    }.asPromise()

    private fun checkKidInJwks(keys: Array<Jwk>, kid: String): Promise<Boolean> {
        for (key in keys) {
            if (key.kid == kid) {
                return Promise.resolve(true)
            }
        }
        return Promise.resolve(false)
    }
}
