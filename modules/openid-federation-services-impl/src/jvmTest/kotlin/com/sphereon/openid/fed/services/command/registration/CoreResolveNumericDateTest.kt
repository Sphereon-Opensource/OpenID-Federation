/* Staged outside compiled source. Real library resolver/verifier/managed software-KMS signing, simulated HTTP only.
 * This is an owning regression fixture, never deployed production or external conformance acceptance. */
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.sphereon.openid.fed.services.command.registration

import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkDelegationCommandImpl
import com.sphereon.openid.fed.services.command.trustMark.CreateTrustMarkDelegationArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationCommandImpl
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationArgs
import com.sphereon.openid.fed.client.command.trustChain.PublishedTrustAnchorKeyResolver
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommandImpl
import com.sphereon.openid.fed.services.command.trust.VerifyEntityTrustArgs
import com.sphereon.openid.fed.services.command.trust.VerifyEntityTrustCommandImpl
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheSerializers
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommandImpl
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainArgs
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommandImpl
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountArgs
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountCommand
import com.sphereon.openid.fed.services.command.jwk.AccountSigningKey
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.signPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.math.BigDecimal
import io.mockk.mockk
import com.sphereon.openid.fed.client.FederationClientImpl
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.services.command.resolution.ResolveEntityCommandImpl
import com.sphereon.openid.fed.services.command.resolution.GetSignedResolveResponseJwtCommandImpl
import com.sphereon.openid.fed.services.command.resolution.GetSignedResolveResponseJwtArgs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real software-KMS keys and signatures with the production Trust Chain resolver and verifier. HTTP is the only
 * simulated boundary: one Trust Anchor with a client and a provider as Immediate Subordinates.
 */
class CoreResolveNumericDateTest {
    private val ta = "https://ta.example"
    private val rp = "https://rp.example"
    private val op = "https://op.example"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var jwtService: JwtService
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private lateinit var execution: SessionExecution
    private lateinit var context: FederationContext
    private var httpClient: HttpClient? = null
    private val published = mutableMapOf<String, String>()
    private var networkRequests = 0
    private val requested = mutableListOf<String>()
    private var cancelFetchKey: String? = null
    private var now = 0L

    private data class Key(val kid: String, val alias: String, val provider: String, val public: JsonObject) {
        val jwk: Jwk get() = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(public)
    }

    private lateinit var taKey: Key
    private lateinit var rpFederationKey: Key
    private lateinit var rpClientKey: Key
    private lateinit var opFederationKey: Key
    private lateinit var rpConfiguration: String
    private lateinit var taAboutRp: String
    private lateinit var taConfiguration: String
    private lateinit var rpMetadata: JsonObject

    @BeforeTest
    fun setUp() = runTest {
        val app = createExactSuperiorEvidenceTestGraph(this@CoreResolveNumericDateTest)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "registration-${hashCode()}", principalType = PrincipalType.ANONYMOUS
        )
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "registration-provider-${hashCode()}"),
            session.asCoreApiServiceGraph().serviceExecution,
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as JwtServiceImpl.Graph).jwtService
        execution = session.asCoreApiServiceGraph().serviceExecution
        httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    networkRequests++
                    val url = request.url
                    val key = if (url.encodedPath == "/fetch") {
                        "${url.protocol.name}://${url.host}/fetch?sub=${url.parameters["sub"]}"
                    } else {
                        "${url.protocol.name}://${url.host}${url.encodedPath}"
                    }
                    requested += key
                    if (key == cancelFetchKey) throw CancellationException("subordinate read cancelled")
                    published[key]?.let { body -> respond(body, HttpStatusCode.OK, io.ktor.http.headersOf(
                        io.ktor.http.HttpHeaders.ContentType,
                        if (body.trimStart().startsWith("{")) "application/json" else
                            "application/" + (parseCompactJws(body)?.header?.text("typ") ?: "entity-statement+jwt"),
                    )) }
                        ?: respond("not published", HttpStatusCode.NotFound)
                }
            }
        }
        val cache = OidfCache.createStringKeyCache(
            manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "registration-${hashCode()}"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue,
        )
        context = FederationContext(
            jwtService = jwtService,
            httpResolver = HttpResolver(httpClient = requireNotNull(httpClient), cache = cache, responseMapper = { it.bodyAsText() }),
        )
        now = getCurrentEpochTimeSeconds()
        publishFederation()
    }

    @AfterTest
    fun tearDown() {
        httpClient?.close()
    }

    private suspend fun key(): Key {
        val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val kid = requireNotNull(generated.kid)
        val public = JsonObject(json.parseToJsonElement(generated.jose.publicJwk.toJsonString()).jsonObject + ("kid" to JsonPrimitive(kid)))
        return Key(kid, requireNotNull(generated.alias), generated.providerId, public)
    }

    private fun jwks(vararg keys: Key) = buildJsonObject { put("keys", JsonArray(keys.map { it.public })) }

    private suspend fun sign(
        payload: JsonObject,
        key: Key,
        typ: String,
        trustChain: List<String>? = null,
        peerTrustChain: List<String>? = null,
    ): String {
        val signed = jwtService.signPayload(
            payload,
            JwtHeader(alg = "ES256", kid = key.kid, typ = typ, trustChain = trustChain, peerTrustChain = peerTrustChain),
            key.kid,
            key.alias,
            key.provider,
        )
        assertTrue(signed.isOk, "real signing failed: ${if (signed.isErr) signed.error else ""}")
        return signed.value
    }

    private fun statementClaims(
        iss: String,
        sub: String,
        keys: JsonObject,
        metadata: JsonObject? = null,
        authorityHints: List<String>? = null,
        policy: JsonObject? = null,
    ) = buildJsonObject {
        put("iss", iss)
        put("sub", sub)
        put("iat", now - 10)
        put("exp", now + 3_600)
        put("jwks", keys)
        (metadata ?: if (iss == sub) buildJsonObject { put("federation_entity", JsonObject(emptyMap())) } else null)
            ?.let { put("metadata", it) }
        authorityHints?.let { put("authority_hints", JsonArray(it.map(::JsonPrimitive))) }
        policy?.let { put("metadata_policy", it) }
    }

    private suspend fun publishFederation() {
        taKey = key()
        rpFederationKey = key()
        rpClientKey = key()
        opFederationKey = key()
        rpMetadata = json.parseToJsonElement(
            """{"federation_entity":{"organization_name":"RP"},
               "openid_relying_party":{"redirect_uris":["https://rp.example/cb"],
                 "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
                 "token_endpoint_auth_method":"private_key_jwt","client_registration_types":["automatic","explicit"],
                 "jwks":${jwks(rpClientKey)}}}"""
        ).jsonObject
        val opMetadata = json.parseToJsonElement(
            """{"openid_provider":{"issuer":"$op","authorization_endpoint":"$op/authorize","token_endpoint":"$op/token",
                 "client_registration_types_supported":["automatic","explicit"],
                 "federation_registration_endpoint":"$op/register"}}"""
        ).jsonObject
        val rpPolicy = json.parseToJsonElement(
            """{"openid_relying_party":{"grant_types":{"subset_of":["authorization_code"]}}}"""
        ).jsonObject

        taConfiguration = sign(
            statementClaims(ta, ta, jwks(taKey), metadata = buildJsonObject {
                put("federation_entity", buildJsonObject { put("federation_fetch_endpoint", "$ta/fetch") })
            }),
            taKey, "entity-statement+jwt",
        )
        rpConfiguration = sign(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf(ta)), rpFederationKey, "entity-statement+jwt")
        val opConfiguration = sign(statementClaims(op, op, jwks(opFederationKey), opMetadata, listOf(ta)), opFederationKey, "entity-statement+jwt")
        taAboutRp = sign(statementClaims(ta, rp, jwks(rpFederationKey), policy = rpPolicy), taKey, "entity-statement+jwt")
        val taAboutOp = sign(statementClaims(ta, op, jwks(opFederationKey)), taKey, "entity-statement+jwt")

        published["$ta/.well-known/openid-federation"] = taConfiguration
        published["$rp/.well-known/openid-federation"] = rpConfiguration
        published["$op/.well-known/openid-federation"] = opConfiguration
        published["$ta/fetch?sub=$rp"] = taAboutRp
        published["$ta/fetch?sub=$op"] = taAboutOp
    }

    private val anchors get() = listOf(RegistrationTrustAnchor(ta))

    private fun resolver() = ResolveTrustChainCommandImpl(execution, context, verifier(), anchorKeys())
    private fun verifier() = VerifyTrustChainCommandImpl(execution, context)
    private fun configurations() = GetEntityConfigurationCommandImpl(execution, context)

    private fun anchorKeys() = PublishedTrustAnchorKeyResolver(configurations())
    private val accounts get() = mapOf("op-account" to (op to opFederationKey))
    private val tenants = object : TenantContextResolver {
        override suspend fun resolveTenantId(request: GenericHttpRequest): String? = null
        override suspend fun resolveTenantIdByName(name: String): String? = null
        override suspend fun resolveIdentifier(tenantId: String): String? = accounts[tenantId]?.first
    }
    private inner class SelectedKeys : TypedServiceCommandAdapter<ResolveAccountSigningKeyArgs, AccountSigningKey, FederationError>(
        commandId = ResolveAccountSigningKeyCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<ResolveAccountSigningKeyArgs>(),
        outputTypeToken = typeToken<AccountSigningKey>(),
    ), ResolveAccountSigningKeyCommand {
        override suspend fun doExecute(args: ResolveAccountSigningKeyArgs,
            applyDuring: (ResolveAccountSigningKeyArgs) -> ResolveAccountSigningKeyArgs): IdkResult<AccountSigningKey, FederationError> {
            val (identifier, key) = accounts[args.accountId] ?: return federationErr(AccountNotFoundError(args.accountId))
            if(identifier != args.expectedEntityIdentifier) return federationErr(AccountNotFoundError(args.accountId))
            return IdkResult.ok(AccountSigningKey(args.accountId, identifier, "key-${key.kid}", key.kid, "ES256", key.alias, key.provider, 1))
        }
    }
    private suspend fun actualSignedResolve(audience: String? = null): String {
        val discovery = resolver()
        val verification = verifier()
        val configurations = configurations()
        val actualClient = FederationClientImpl(discovery, verification, configurations,
            VerifyTrustMarkCommandImpl(execution, context, configurations, discovery, verification),
            mockk(), mockk()) // Unused discovery/list endpoints; actual trust/mark paths are never mocked.
        val command = GetSignedResolveResponseJwtCommandImpl(execution,
            ResolveEntityCommandImpl(execution, tenants, actualClient), jwtService, tenants, SelectedKeys())
        val result = command.execute(GetSignedResolveResponseJwtArgs("op-account", rp, arrayOf(ta), null, audience))
        assertTrue(result.isOk, "Actual resolver/signing must succeed: ${if(result.isErr) result.error else ""}")
        assertTrue(jwtService.verifyJwtSignature(result.value, opFederationKey.jwk), "Actual managed software-KMS signature must verify")
        return result.value
    }
    private suspend fun replaceLeafExpiry(rawNumber: String) {
        val leaf = JsonObject(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf(ta)) +
            ("exp" to JsonUnquotedLiteral(rawNumber)))
        rpConfiguration = sign(leaf, rpFederationKey, "entity-statement+jwt")
        assertEquals(0, BigDecimal(rawNumber).compareTo(BigDecimal(decodeJWTComponents(rpConfiguration).payload.getValue("exp").jsonPrimitive.content)),
            "The actual signed fixture must preserve the exact NumericDate value, independent of equivalent exponent notation")
        published["$rp/.well-known/openid-federation"] = rpConfiguration
    }
    private fun wireExpiry(compact: String): BigDecimal = BigDecimal(decodeJWTComponents(compact).payload.getValue("exp").jsonPrimitive.content)

    // OpenID Federation 1.1 section 8.3.2: aud only when the requesting party authenticated, and then exactly its
    // Entity Identifier.
    @Test fun signedResolveCarriesTheAuthenticatedRequesterAsItsOnlyAudience() = runTest {
        replaceLeafExpiry("${now + 120}")
        val requester = "https://requester.example"
        val claims = decodeJWTComponents(actualSignedResolve(audience = requester)).payload
        assertEquals(requester, claims.getValue("aud").jsonPrimitive.content)
    }
    @Test fun signedResolveForAnUnauthenticatedRequestHasNoAudience() = runTest {
        replaceLeafExpiry("${now + 120}")
        assertTrue("aud" !in decodeJWTComponents(actualSignedResolve()).payload)
    }
    @Test fun signedResolvePreservesTheActualQuarterSecondChainMinimum() = runTest {
        val expected = "${now + 120}.25"
        replaceLeafExpiry(expected)
        val compact = actualSignedResolve()
        assertEquals(0, wireExpiry(compact).compareTo(BigDecimal(expected)),
            "Core 1.1 section8.3.2 requires the actual minimum; valid fractional NumericDate must not be truncated")
    }
    @Test fun signedResolveNeverRoundsABoundaryFractionBeyondItsSignedChainExpiry() = runTest {
        val expected = "${now + 120}.9999999999999999"
        replaceLeafExpiry(expected)
        val compact = actualSignedResolve()
        assertTrue(wireExpiry(compact) <= BigDecimal(expected),
            "Compare actual signed response and original exact JSON decimals; expiration cannot be rounded later")
    }
    @Test fun signedResolvePreservesTheActualQuarterSecondVerifiedTrustMarkMinimum() = runTest {
        val type = "https://framework.example/quarter-expiry"
        val expected = "${now + 90}.25"
        val mark = sign(buildJsonObject {
            put("iss",ta); put("sub",rp); put("trust_mark_type",type); put("iat",now - 10)
            put("exp",JsonUnquotedLiteral(expected))
        },taKey,"trust-mark+jwt")
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(decodeJWTComponents(mark).payload.getValue("exp").jsonPrimitive.content)),
            "The actual signed mark fixture must preserve the exact NumericDate value")
        taConfiguration = sign(JsonObject(statementClaims(ta,ta,jwks(taKey),buildJsonObject {
            put("federation_entity",buildJsonObject { put("federation_fetch_endpoint","$ta/fetch") })
        }) + ("trust_mark_issuers" to buildJsonObject { put(type,JsonArray(listOf(JsonPrimitive(ta)))) })),taKey,"entity-statement+jwt")
        rpConfiguration = sign(JsonObject(statementClaims(rp,rp,jwks(rpFederationKey),rpMetadata,listOf(ta)) +
            ("trust_marks" to JsonArray(listOf(buildJsonObject { put("trust_mark_type",type); put("trust_mark",mark) })))),rpFederationKey,"entity-statement+jwt")
        published["$ta/.well-known/openid-federation"] = taConfiguration
        published["$rp/.well-known/openid-federation"] = rpConfiguration
        val compact = actualSignedResolve()
        val verifiedMarks = decodeJWTComponents(compact).payload.getValue("trust_marks").jsonArray
        assertEquals(1,verifiedMarks.size,"The actual cryptographic verifier must include the recognized valid mark")
        assertEquals(0,wireExpiry(compact).compareTo(BigDecimal(expected)),
            "The real verified mark is the earliest lifetime and its valid fraction must be preserved")
    }
}