package com.sphereon.oid.fed.client.services.trustChainService

import com.sphereon.oid.fed.client.helpers.*
import com.sphereon.oid.fed.client.mapper.decodeJWTComponents
import com.sphereon.oid.fed.client.mapper.mapEntityStatement
import com.sphereon.oid.fed.client.types.ICryptoService
import com.sphereon.oid.fed.client.types.IFetchService
import com.sphereon.oid.fed.client.types.TrustChainResolveResponse
import com.sphereon.oid.fed.client.types.VerifyTrustChainResponse
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import com.sphereon.oid.fed.openapi.models.JWT
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.set

/*
 * TrustChain is a class that implements the logic to resolve and validate a trust chain.
 */
class TrustChainService
    (
    private val fetchService: IFetchService,
    private val cryptoService: ICryptoService
) {
    /*
     * This function verifies a trust chain.
     * The function follows the steps defined in the OpenID Federation 1.0 specification.
     *
     * @param chain: List<String> - A list of statements in the trust chain.
     * @param trustAnchors: Array<String> - An array of trust anchors.
     * @param currentTime: Long - The current time for validation.
     * @return com.sphereon.oid.fed.client.types.com.sphereon.oid.fed.client.types.VerifyTrustChainResponse - The response of the trust chain verification.
     *
     * @see <a href="https://openid.net/specs/openid-federation-1_0.html#section-10.2">OpenID Federation 1.0 - 10.2. Validating a Trust Chain</a>
     */
    suspend fun verify(
        chain: List<String>,
        trustAnchor: String?,
        currentTime: Long? = null
    ): VerifyTrustChainResponse {
        val timeToUse = currentTime ?: getCurrentEpochTimeSeconds()
        if (chain.size < 3) {
            TrustChainServiceConst.LOG.error("Trust chain too short: ${chain.size} statements (minimum 3 required)")
            return VerifyTrustChainResponse(false, "Trust chain must contain at least 3 elements")
        }

        try {
            // Decode all statements in the chain
            TrustChainServiceConst.LOG.debug("Decoding all statements in the chain")
            val statements = chain.map { decodeJWTComponents(it) }
            TrustChainServiceConst.LOG.debug("Current time for validation: $currentTime")

            // Verify each statement in the chain
            for (j in statements.indices) {
                val statement = statements[j]
                TrustChainServiceConst.LOG.debug("Verifying statement at position $j")
                TrustChainServiceConst.LOG.debug("Statement $j - Issuer: ${statement.payload["iss"]?.jsonPrimitive?.content}")
                TrustChainServiceConst.LOG.debug("Statement $j - Subject: ${statement.payload["sub"]?.jsonPrimitive?.content}")

                // 1. Verify required claims (sub, iss, exp, iat, jwks)
                TrustChainServiceConst.LOG.debug("Checking required claims for statement $j")
                if (!hasRequiredClaims(statement)) {
                    TrustChainServiceConst.LOG.error("Statement at position $j missing required claims")
                    return VerifyTrustChainResponse(false, "Statement at position $j missing required claims")
                }

                // 2. Verify iat is in the past
                val iat = statement.payload["iat"]?.jsonPrimitive?.content?.toLongOrNull()
                TrustChainServiceConst.LOG.debug("Statement $j - Issued at (iat): $iat")
                if (iat == null || iat > timeToUse) {
                    TrustChainServiceConst.LOG.error("Statement $j has invalid iat: $iat")
                    return VerifyTrustChainResponse(false, "Statement at position $j has invalid iat")
                }

                // 3. Verify exp is in the future
                val exp = statement.payload["exp"]?.jsonPrimitive?.content?.toLongOrNull()
                TrustChainServiceConst.LOG.debug("Statement $j - Expires at (exp): $exp")
                if (exp == null || exp <= timeToUse) {
                    TrustChainServiceConst.LOG.error("Statement $j has expired: $exp")
                    return VerifyTrustChainResponse(false, "Statement at position $j has expired")
                }

                // 4. For ES[0], verify iss == sub
                if (j == 0) {
                    TrustChainServiceConst.LOG.debug("Verifying first statement (ES[0]) specific rules")
                    val iss = statement.payload["iss"]?.jsonPrimitive?.content
                    val sub = statement.payload["sub"]?.jsonPrimitive?.content
                    TrustChainServiceConst.LOG.debug("ES[0] - Comparing iss ($iss) with sub ($sub)")
                    if (iss != sub) {
                        TrustChainServiceConst.LOG.error("First statement iss ($iss) does not match sub ($sub)")
                        return VerifyTrustChainResponse(false, "First statement must have iss == sub")
                    }

                    // 5. For ES[0], verify signature with its own jwks
                    TrustChainServiceConst.LOG.debug("Verifying ES[0] signature with its own JWKS")
                    if (!verifySignatureWithOwnJwks(chain[j])) {
                        TrustChainServiceConst.LOG.error("First statement signature verification failed")
                        return VerifyTrustChainResponse(false, "First statement signature verification failed")
                    }
                }

                // 6. For each j = 0,...,i-1, verify ES[j]["iss"] == ES[j+1]["sub"]
                if (j < statements.size - 1) {
                    TrustChainServiceConst.LOG.debug("Verifying chain continuity between statements $j and ${j + 1}")
                    val currentIss = statement.payload["iss"]?.jsonPrimitive?.content
                    val nextSub = statements[j + 1].payload["sub"]?.jsonPrimitive?.content
                    TrustChainServiceConst.LOG.debug("Comparing current iss ($currentIss) with next sub ($nextSub)")
                    if (currentIss != nextSub) {
                        TrustChainServiceConst.LOG.error("Chain broken: statement $j iss ($currentIss) does not match statement ${j + 1} sub ($nextSub)")
                        return VerifyTrustChainResponse(
                            false,
                            "Statement chain broken between positions $j and ${j + 1}"
                        )
                    }

                    // 7. Verify signature with next statement's jwks
                    TrustChainServiceConst.LOG.debug("Verifying statement $j signature with statement ${j + 1}'s JWKS")
                    if (!verifySignatureWithNextJwks(chain[j], chain[j + 1])) {
                        TrustChainServiceConst.LOG.error("Signature verification failed between statements $j and ${j + 1}")
                        return VerifyTrustChainResponse(
                            false,
                            "Signature verification failed for statement $j with next statement's keys"
                        )
                    }
                }

                // 8. For last statement (Trust Anchor), verify issuer matches trust anchor
                if (j == statements.size - 1) {
                    TrustChainServiceConst.LOG.debug("Verifying trust anchor (last statement)")
                    val lastIss = statement.payload["iss"]?.jsonPrimitive?.content

                    if (trustAnchor != null && lastIss != trustAnchor) {
                        TrustChainServiceConst.LOG.error("Last statement issuer ($lastIss) does not match trust anchor ($trustAnchor)")
                        return VerifyTrustChainResponse(false, "Last statement issuer does not match trust anchor")
                    }

                    // 9. Verify last statement signature with its own jwks
                    TrustChainServiceConst.LOG.debug("Verifying trust anchor signature with its own JWKS")
                    if (!verifySignatureWithOwnJwks(chain[j])) {
                        TrustChainServiceConst.LOG.error("Trust anchor signature verification failed")
                        return VerifyTrustChainResponse(false, "Trust anchor signature verification failed")
                    }
                }
            }

            TrustChainServiceConst.LOG.debug("Trust chain verification completed successfully")
            return VerifyTrustChainResponse(true)
        } catch (e: Exception) {
            TrustChainServiceConst.LOG.error("Chain verification failed with exception", e)
            return VerifyTrustChainResponse(false, "Chain verification failed: ${e.message}")
        }
    }

    /*
     * This function tries to resolve a trust chain.
     * The function follows the steps defined in the OpenID Federation 1.0 specification.
     * It recursively builds the trust chain by fetching and verifying entity configurations and subordinate statements.
     * It returns the first trust chain that is successfully built.
     *
     * @param entityIdentifier: String - The entity identifier for which to resolve the trust chain.
     * @param trustAnchors: Array<String> - An array of trust anchors.
     * @param maxDepth: Int - The maximum depth to resolve the trust chain.
     * @return TrustChainResolveResponse - The response of the trust chain resolution.
     */
    suspend fun resolve(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        maxDepth: Int
    ): TrustChainResolveResponse {
        TrustChainServiceConst.LOG.info("Resolving trust chain for entity: $entityIdentifier with max depth: $maxDepth")
        val cache = SimpleCache<String, String>()
        val chain: MutableList<String> = arrayListOf()
        return try {
            val trustChain = buildTrustChain(entityIdentifier, trustAnchors, chain, cache, 0, maxDepth)
            if (trustChain != null) {
                TrustChainServiceConst.LOG.info(
                    "Successfully resolved trust chain for entity: $entityIdentifier",
                    context = mapOf("trustChain" to trustChain.toString())
                )
                TrustChainResolveResponse(trustChain, false, null)
            } else {
                TrustChainServiceConst.LOG.error("Could not establish trust chain for entity: $entityIdentifier")
                TrustChainResolveResponse(null, true, "A Trust chain could not be established")
            }
        } catch (e: Throwable) {
            TrustChainServiceConst.LOG.error("Trust chain resolution failed for entity: $entityIdentifier", e)
            TrustChainResolveResponse(null, true, e.message)
        }
    }

    private suspend fun buildTrustChain(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        cache: SimpleCache<String, String>,
        depth: Int,
        maxDepth: Int
    ): MutableList<String>? {
        TrustChainServiceConst.LOG.debug("Building trust chain for entity: $entityIdentifier at depth: $depth")
        if (depth == maxDepth) {
            TrustChainServiceConst.LOG.debug("Maximum depth reached: $maxDepth")
            return null
        }

        val entityConfigurationEndpoint = getEntityConfigurationEndpoint(entityIdentifier)
        TrustChainServiceConst.LOG.debug("Fetching entity configuration from: $entityConfigurationEndpoint")
        val entityConfigurationJwt = this.fetchService.fetchStatement(entityConfigurationEndpoint)
        val decodedEntityConfiguration = decodeJWTComponents(entityConfigurationJwt)
        TrustChainServiceConst.LOG.debug("Decoded entity configuration JWT header kid: ${decodedEntityConfiguration.header.kid}")

        val key = findKeyInJwks(
            decodedEntityConfiguration.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: run {
                TrustChainServiceConst.LOG.debug("No JWKS found in entity configuration payload")
                return null
            },
            decodedEntityConfiguration.header.kid
        ) ?: run {
            TrustChainServiceConst.LOG.debug("Could not find key with kid: ${decodedEntityConfiguration.header.kid} in JWKS")
            return null
        }

        if (!this.cryptoService.verify(entityConfigurationJwt, key)) {
            TrustChainServiceConst.LOG.debug("Entity configuration JWT signature verification failed")
            return null
        }

        val entityStatement: EntityConfigurationStatementDTO =
            mapEntityStatement(entityConfigurationJwt, EntityConfigurationStatementDTO::class) ?: run {
                TrustChainServiceConst.LOG.debug("Could not map JWT to EntityConfigurationStatementDTO")
                return null
            }

        if (chain.isEmpty()) {
            TrustChainServiceConst.LOG.debug("Adding entity configuration JWT to empty chain")
            chain.add(entityConfigurationJwt)
        }

        val authorityHints = entityStatement.authorityHints ?: run {
            TrustChainServiceConst.LOG.debug("No authority hints found in entity statement")
            return null
        }

        TrustChainServiceConst.LOG.debug("Processing ${authorityHints.size} authority hints")
        val reorderedAuthorityHints = authorityHints.sortedBy { hint ->
            if (trustAnchors.contains(hint)) 0 else 1
        }

        for (authority in reorderedAuthorityHints) {
            TrustChainServiceConst.LOG.debug("Processing authority: $authority")
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
                TrustChainServiceConst.LOG.debug("Successfully built trust chain through authority: $authority")
                return result
            }
            TrustChainServiceConst.LOG.debug("Failed to build trust chain through authority: $authority, trying next authority")
        }

        TrustChainServiceConst.LOG.debug("Could not build trust chain through any authority")
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
        TrustChainServiceConst.LOG.debug("Processing authority: $authority for entity: $entityIdentifier at depth: $depth")
        try {
            val (authorityEntityConfigurationJwt, authorityEntityConfiguration) = fetchAndVerifyAuthorityConfiguration(
                authority,
                cache
            ) ?: run {
                TrustChainServiceConst.LOG.debug("Failed to fetch and verify authority configuration for: $authority")
                return null
            }

            val authorityEntityFetchEndpoint = getAuthorityFetchEndpoint(authorityEntityConfiguration) ?: run {
                TrustChainServiceConst.LOG.debug("No federation fetch endpoint found in authority configuration for: $authority")
                return null
            }
            TrustChainServiceConst.LOG.debug("Found authority fetch endpoint: $authorityEntityFetchEndpoint")

            val (subordinateStatementJwt, subordinateStatement) = fetchAndVerifySubordinateStatement(
                authorityEntityFetchEndpoint,
                entityIdentifier,
                authorityEntityConfigurationJwt,
                lastStatementKid
            ) ?: run {
                TrustChainServiceConst.LOG.debug("Failed to fetch and verify subordinate statement from authority: $authority")
                return null
            }

            // If authority is in trust anchors, return the completed chain
            if (trustAnchors.contains(authority)) {
                TrustChainServiceConst.LOG.debug("Authority $authority is a trust anchor, completing chain")
                return completeChainWithAuthority(chain, subordinateStatementJwt, authorityEntityConfigurationJwt)
            }

            // Recursively build trust chain if there are authority hints
            TrustChainServiceConst.LOG.debug("Authority $authority is not a trust anchor, processing its authority hints")
            return processAuthorityHints(
                authorityEntityConfiguration,
                authority,
                trustAnchors,
                chain,
                subordinateStatementJwt,
                cache,
                depth,
                maxDepth
            )
        } catch (e: Exception) {
            TrustChainServiceConst.LOG.error("Failed to process authority: $authority", e)
            return null
        }
    }

    private suspend fun fetchAndVerifyAuthorityConfiguration(
        authority: String,
        cache: SimpleCache<String, String>
    ): Pair<String, EntityConfigurationStatementDTO>? {
        val authorityConfigurationEndpoint = getEntityConfigurationEndpoint(authority)

        // Avoid processing the same entity twice
        if (cache.get(authorityConfigurationEndpoint) != null) return null

        val authorityEntityConfigurationJwt = fetchService.fetchStatement(authorityConfigurationEndpoint)
        cache.put(authorityConfigurationEndpoint, authorityEntityConfigurationJwt)

        val decodedJwt = decodeJWTComponents(authorityEntityConfigurationJwt)
        val key = findKeyInJwks(
            decodedJwt.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
            decodedJwt.header.kid
        ) ?: return null

        if (!cryptoService.verify(authorityEntityConfigurationJwt, key)) return null

        val authorityEntityConfiguration = mapEntityStatement(
            authorityEntityConfigurationJwt,
            EntityConfigurationStatementDTO::class
        ) ?: return null

        return Pair(authorityEntityConfigurationJwt, authorityEntityConfiguration)
    }

    private fun getAuthorityFetchEndpoint(
        authorityEntityConfiguration: EntityConfigurationStatementDTO
    ): String? {
        val federationEntityMetadata = authorityEntityConfiguration.metadata?.get("federation_entity") as? JsonObject
        if (federationEntityMetadata == null || !federationEntityMetadata.containsKey("federation_fetch_endpoint")) return null

        return federationEntityMetadata["federation_fetch_endpoint"]?.jsonPrimitive?.content
    }

    private suspend fun fetchAndVerifySubordinateStatement(
        authorityEntityFetchEndpoint: String,
        entityIdentifier: String,
        authorityConfigurationJwt: String,
        lastStatementKid: String
    ): Pair<String, SubordinateStatement>? {
        val subordinateStatementEndpoint =
            getSubordinateStatementEndpoint(authorityEntityFetchEndpoint, entityIdentifier)
        val subordinateStatementJwt = fetchService.fetchStatement(subordinateStatementEndpoint)
        val decodedSubordinateStatement = decodeJWTComponents(subordinateStatementJwt)

        // Find and verify the key for the subordinate statement
        val decodedAuthorityConfiguration = decodeJWTComponents(authorityConfigurationJwt)
        val subordinateStatementKey = findKeyInJwks(
            decodedAuthorityConfiguration.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return null,
            decodedSubordinateStatement.header.kid
        ) ?: return null

        if (!cryptoService.verify(subordinateStatementJwt, subordinateStatementKey)) return null

        val subordinateStatement = mapEntityStatement(
            subordinateStatementJwt,
            SubordinateStatement::class
        ) ?: return null

        // Verify the entity key exists in the subordinate statement
        val jwks = subordinateStatement.jwks
        val keys = jwks.propertyKeys ?: return null
        if (!checkKidInJwks(keys, lastStatementKid)) return null

        return Pair(subordinateStatementJwt, subordinateStatement)
    }

    private fun completeChainWithAuthority(
        chain: MutableList<String>,
        subordinateStatementJwt: String,
        authorityEntityConfigurationJwt: String
    ): MutableList<String> {
        chain.add(subordinateStatementJwt)
        chain.add(authorityEntityConfigurationJwt)
        return chain
    }

    private suspend fun processAuthorityHints(
        authorityEntityConfiguration: EntityConfigurationStatementDTO,
        authority: String,
        trustAnchors: Array<String>,
        chain: MutableList<String>,
        subordinateStatementJwt: String,
        cache: SimpleCache<String, String>,
        depth: Int,
        maxDepth: Int
    ): MutableList<String>? {
        if (authorityEntityConfiguration.authorityHints?.isNotEmpty() == true) {
            chain.add(subordinateStatementJwt)
            val result = buildTrustChain(authority, trustAnchors, chain, cache, depth, maxDepth)
            if (result != null) return result
            chain.removeLast()
        }
        return null
    }


    private fun hasRequiredClaims(statement: JWT): Boolean {
        return statement.payload["sub"] != null &&
                statement.payload["iss"] != null &&
                statement.payload["exp"] != null &&
                statement.payload["iat"] != null &&
                statement.payload["jwks"] != null
    }

    private suspend fun verifySignatureWithOwnJwks(jwt: String): Boolean {
        val decoded = decodeJWTComponents(jwt)
        val key = findKeyInJwks(
            decoded.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return false,
            decoded.header.kid
        ) ?: return false
        return cryptoService.verify(jwt, key)
    }

    private suspend fun verifySignatureWithNextJwks(jwt: String, nextJwt: String): Boolean {
        val decoded = decodeJWTComponents(jwt)
        val decodedNext = decodeJWTComponents(nextJwt)
        val key = findKeyInJwks(
            decodedNext.payload["jwks"]?.jsonObject?.get("keys")?.jsonArray ?: return false,
            decoded.header.kid
        ) ?: return false
        return cryptoService.verify(jwt, key)
    }
}

class SimpleCache<K, V> {
    private val cacheMap = mutableMapOf<K, V>()

    fun get(key: K): V? = cacheMap[key]

    fun put(key: K, value: V) {
        cacheMap[key] = value
    }
}