// CANDIDATE OUTSIDE SOURCE while five-test GREEN inputs are frozen.
/* Staged outside compiled source. Real library resolver/verifier/managed software-KMS signing, simulated HTTP only.
 * This is an owning regression fixture, never deployed production or external conformance acceptance. */
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
import kotlinx.serialization.json.JsonNull
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
class CoreResolveTrustMarkActivityTest {
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
    private var statusRequests = 0
    private var issuedStatusMark: String? = null
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
        val app = createExactSuperiorEvidenceTestGraph(this@CoreResolveTrustMarkActivityTest)
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
                    if (url.encodedPath == "/trust-mark-status") {
                        statusRequests++
                        val bytes = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()
                        val submittedMark = bytes?.decodeToString()?.let { io.ktor.http.parseQueryString(it)["trust_mark"] }
                        val contentType = (request.headers[io.ktor.http.HttpHeaders.ContentType] ?: request.body.contentType?.toString()).orEmpty().substringBefore(';').trim()
                        if (request.method != io.ktor.http.HttpMethod.Post || !contentType.equals("application/x-www-form-urlencoded", ignoreCase = true) || submittedMark != issuedStatusMark) {
                            return@addHandler respond("Wrong status method or mark binding", HttpStatusCode.BadRequest)
                        }
                    }
                    requested += key
                    if (key == cancelFetchKey) throw CancellationException("subordinate read cancelled")
                    published[key]?.let { body -> respond(body, HttpStatusCode.OK, io.ktor.http.headersOf(
                        io.ktor.http.HttpHeaders.ContentType,
                        if (body.trimStart().startsWith("{")) "application/json" else
                            if (url.encodedPath == "/trust-mark-status") "application/trust-mark-status-response+jwt" else
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
    private suspend fun actualSignedResolve(): String {
        val discovery = resolver()
        val verification = verifier()
        val configurations = configurations()
        val actualClient = FederationClientImpl(discovery, verification, configurations,
            VerifyTrustMarkCommandImpl(execution, context, configurations, discovery, verification),
            mockk(), mockk()) // Unused discovery/list endpoints; actual trust/mark paths are never mocked.
        val command = GetSignedResolveResponseJwtCommandImpl(execution,
            ResolveEntityCommandImpl(execution, tenants, actualClient), jwtService, tenants, SelectedKeys())
        val result = command.execute(GetSignedResolveResponseJwtArgs("op-account", rp, arrayOf(ta), null))
        assertTrue(result.isOk, "Actual resolver/signing must succeed: ${if(result.isErr) result.error else ""}")
        assertTrue(jwtService.verifyJwtSignature(result.value, opFederationKey.jwk), "Actual managed software-KMS signature must verify")
        return result.value
    }
    private suspend fun replaceLeafExpiry(rawNumber: String) {
        val leaf = JsonObject(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf(ta)) +
            ("exp" to json.parseToJsonElement(rawNumber)))
        rpConfiguration = sign(leaf, rpFederationKey, "entity-statement+jwt")
        assertEquals(rawNumber, decodeJWTComponents(rpConfiguration).payload.getValue("exp").jsonPrimitive.content,
            "The valid fractional signed fixture must retain its exact numeric token")
        published["$rp/.well-known/openid-federation"] = rpConfiguration
    }
    private fun wireExpiry(compact: String): BigDecimal = BigDecimal(decodeJWTComponents(compact).payload.getValue("exp").jsonPrimitive.content)

    private suspend fun publishRecognizedMarkAndAdvertisedAuthenticatedStatus(status: String): String {
        val type = "https://framework.example/activity"
        val statusEndpoint = "$ta/trust-mark-status"
        val mark = sign(buildJsonObject {
            put("iss",ta); put("sub",rp); put("trust_mark_type",type); put("iat",now - 10); put("exp",now + 600)
        },taKey,"trust-mark+jwt")
        issuedStatusMark = mark
        val statusJwt = sign(buildJsonObject {
            put("iss",ta); put("iat",now); put("trust_mark",mark); put("status",status)
        },taKey,"trust-mark-status-response+jwt")
        assertTrue(jwtService.verifyJwtSignature(mark, taKey.jwk), "Recognized mark has an actual valid federation signature")
        assertTrue(jwtService.verifyJwtSignature(statusJwt, taKey.jwk), "Issuer status has an actual authenticated federation signature")
        val statusClaims = decodeJWTComponents(statusJwt)
        assertEquals("trust-mark-status-response+jwt",statusClaims.header.typ)
        assertEquals(ta,statusClaims.payload.getValue("iss").jsonPrimitive.content)
        assertEquals(mark,statusClaims.payload.getValue("trust_mark").jsonPrimitive.content)
        assertEquals(status,statusClaims.payload.getValue("status").jsonPrimitive.content)
        taConfiguration = sign(JsonObject(statementClaims(ta,ta,jwks(taKey),buildJsonObject {
            put("federation_entity",buildJsonObject {
                put("federation_fetch_endpoint","$ta/fetch")
                put("federation_trust_mark_status_endpoint",statusEndpoint)
            })
        }) + ("trust_mark_issuers" to buildJsonObject { put(type,JsonArray(listOf(JsonPrimitive(ta)))) })),taKey,"entity-statement+jwt")
        rpConfiguration = sign(JsonObject(statementClaims(rp,rp,jwks(rpFederationKey),rpMetadata,listOf(ta)) +
            ("trust_marks" to JsonArray(listOf(buildJsonObject { put("trust_mark_type",type); put("trust_mark",mark) })))),rpFederationKey,"entity-statement+jwt")
        published["$ta/.well-known/openid-federation"] = taConfiguration
        published["$rp/.well-known/openid-federation"] = rpConfiguration
        published[statusEndpoint] = statusJwt
        return type
    }
    @Test fun recognizedMarkWithAdvertisedIssuerRevocationIsNotReturnedAsActive() = runTest {
        val type = publishRecognizedMarkAndAdvertisedAuthenticatedStatus("revoked")
        val response = actualSignedResolve()
        val marks = (decodeJWTComponents(response).payload["trust_marks"] as? JsonArray).orEmpty()
        assertTrue(marks.none { it.jsonObject["trust_mark_type"]?.jsonPrimitive?.content == type },
            "An unexpired correctly signed mark is not active when its authenticated advertised issuer status is revoked")
        assertTrue(statusRequests > 0,"This fixture supplies no other authoritative activity input; use the advertised issuer status")
    }
    @Test fun recognizedMarkWithAdvertisedAuthenticatedActiveStatusRemainsIncluded() = runTest {
        val type = publishRecognizedMarkAndAdvertisedAuthenticatedStatus("active")
        val response = actualSignedResolve()
        val marks = (decodeJWTComponents(response).payload["trust_marks"] as? JsonArray).orEmpty()
        assertEquals(1,marks.count { it.jsonObject["trust_mark_type"]?.jsonPrimitive?.content == type },
            "Activity verification must preserve a genuinely valid recognized active mark")
        assertTrue(statusRequests > 0,"Verify advertised status for this fixture; no requirement to POST when no status endpoint is advertised")
    }
    @Test fun advertisedStatusRequiresActualIssuerMarkBindingTypeKeyAndNumericTimes() = runTest {
        val type = publishRecognizedMarkAndAdvertisedAuthenticatedStatus("active")
        val endpoint = "$ta/trust-mark-status"
        val base = decodeJWTComponents(published.getValue(endpoint)).payload
        val actualMark = requireNotNull(issuedStatusMark)
        val otherMark = sign(JsonObject(decodeJWTComponents(actualMark).payload + ("iat" to JsonPrimitive(now - 11))), taKey, "trust-mark+jwt")
        val cases = listOf(
            "wrong-issuer" to JsonObject(base + ("iss" to JsonPrimitive("https://foreign.example"))),
            "wrong-mark" to JsonObject(base + ("trust_mark" to JsonPrimitive(otherMark))),
            "string-iat" to JsonObject(base + ("iat" to JsonPrimitive(now.toString()))),
            "null-iat" to JsonObject(base + ("iat" to JsonNull)),
            "boolean-iat" to JsonObject(base + ("iat" to JsonPrimitive(true))),
            "future-iat" to JsonObject(base + ("iat" to JsonPrimitive(now + 60))),
            "expired-exp" to JsonObject(base + ("exp" to JsonPrimitive(now - 1))),
        )
        for ((name, claims) in cases) {
            published[endpoint] = sign(claims, taKey, "trust-mark-status-response+jwt")
            val compact = actualSignedResolve()
            assertTrue((decodeJWTComponents(compact).payload["trust_marks"] as? JsonArray).orEmpty().isEmpty(), name)
        }
        for ((name, key, typ) in listOf(Triple("wrong-typ", taKey, "entity-statement+jwt"), Triple("foreign-key", rpFederationKey, "trust-mark-status-response+jwt"))) {
            published[endpoint] = sign(base, key, typ)
            assertTrue((decodeJWTComponents(actualSignedResolve()).payload["trust_marks"] as? JsonArray).orEmpty().isEmpty(), name)
        }
        val pristine = sign(base, taKey, "trust-mark-status-response+jwt")
        assertTrue(jwtService.verifyJwtSignature(pristine, taKey.jwk))
        val parts = pristine.split('.')
        val tamperedPayload = JsonObject(base + ("proof_nonce" to JsonPrimitive("tampered-after-signing"))).toString()
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedPayload.toByteArray())
        published[endpoint] = "${parts[0]}.$encoded.${parts[2]}"
        assertTrue((decodeJWTComponents(actualSignedResolve()).payload["trust_marks"] as? JsonArray).orEmpty().isEmpty(), "actual invalid signature with matching issuer/kid/mark/status must refuse")
        assertTrue(statusRequests >= cases.size + 3, "Each authoritative answer is actually queried using the bound POST form")
    }
    @Test fun recognizedValidMarkWithoutStatusEndpointPreservesSignatureAndTimeAcceptance() = runTest {
        val type = publishRecognizedMarkAndAdvertisedAuthenticatedStatus("active")
        val claims = decodeJWTComponents(taConfiguration).payload
        val metadata = claims.getValue("metadata").jsonObject
        val federation = metadata.getValue("federation_entity").jsonObject
        taConfiguration = sign(JsonObject(claims + ("metadata" to JsonObject(metadata +
            ("federation_entity" to JsonObject(federation - "federation_trust_mark_status_endpoint"))))), taKey, "entity-statement+jwt")
        published["$ta/.well-known/openid-federation"] = taConfiguration
        val marks = (decodeJWTComponents(actualSignedResolve()).payload["trust_marks"] as? JsonArray).orEmpty()
        assertEquals(1, marks.count { it.jsonObject["trust_mark_type"]?.jsonPrimitive?.content == type })
        assertEquals(0, statusRequests, "An optional status endpoint must not become a blanket requirement")
    }
    @Test fun unrecognizedCrossFederationMarkDoesNotQueryOrEnterResolveResponse() = runTest {
        publishRecognizedMarkAndAdvertisedAuthenticatedStatus("active")
        taConfiguration = sign(JsonObject(decodeJWTComponents(taConfiguration).payload - "trust_mark_issuers"), taKey, "entity-statement+jwt")
        published["$ta/.well-known/openid-federation"] = taConfiguration
        assertTrue((decodeJWTComponents(actualSignedResolve()).payload["trust_marks"] as? JsonArray).orEmpty().isEmpty())
        assertEquals(0, statusRequests, "Unrecognized marks remain cross-federation omissions before status queries")
    }
    @Test fun advertisedStatusCancellationPropagatesThroughTheActualResolveAndSigningCommands() = runTest {
        publishRecognizedMarkAndAdvertisedAuthenticatedStatus("active")
        cancelFetchKey = "$ta/trust-mark-status"
        assertFailsWith<CancellationException> { actualSignedResolve() }
        assertTrue(statusRequests > 0)
    }

}