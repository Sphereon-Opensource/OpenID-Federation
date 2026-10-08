package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.cache.CacheRequirements
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real software-KMS keys and signatures with the production Trust Chain resolver and verifier. HTTP is the only
 * simulated boundary: one Trust Anchor with a client and a provider as Immediate Subordinates.
 */
class FederationRegistrationCommandsTest {
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
        val app = createExactSuperiorEvidenceTestGraph(this@FederationRegistrationCommandsTest)
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
                    published[key]?.let { respond(it, HttpStatusCode.OK) }
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
        metadata?.let { put("metadata", it) }
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

    private val anchors get() = listOf(RegistrationTrustAnchor(ta, listOf(taKey.jwk)))

    private fun resolver() = ResolveTrustChainCommandImpl(execution, context)
    private fun verifier() = VerifyTrustChainCommandImpl(execution, context)

    private fun automatic(store: RegistrationProofJtiStore = InMemoryRegistrationProofJtiStore()) =
        VerifyAutomaticRegistrationCommandImpl(execution, resolver(), verifier(), context, jwtService, store)

    private suspend fun requestObject(
        signer: Key = rpClientKey,
        jti: String = UUID.randomUUID().toString(),
        trustChain: List<String>? = null,
        claims: JsonObject.() -> JsonObject = { this },
    ): String {
        val base = buildJsonObject {
            put("iss", rp)
            put("client_id", rp)
            put("aud", op)
            put("jti", jti)
            put("iat", now)
            put("exp", now + 120)
            put("response_type", "code")
            put("redirect_uri", "https://rp.example/cb")
            put("scope", "openid")
        }
        return sign(base.claims(), signer, "oauth-authz-req+jwt", trustChain = trustChain)
    }

    private fun automaticArgs(proof: AutomaticRegistrationProof, trustAnchors: List<RegistrationTrustAnchor> = anchors, algs: List<String> = listOf("ES256")) =
        VerifyAutomaticRegistrationArgs(op, rp, RegistrationProfile.OPENID_CONNECT, proof, trustAnchors, algs)

    @Test
    fun automaticRequestObjectIsVerifiedThroughTheDiscoveredChainAndAcceptedOnce() = runTest {
        val command = automatic()
        val jwt = requestObject(jti = "once")
        val verified = command.execute(automaticArgs(AutomaticRegistrationProof.RequestObject(jwt)))

        assertTrue(verified.isOk, "request object must verify: ${if (verified.isErr) verified.error else ""}")
        assertEquals(rp, verified.value.client.clientId)
        assertEquals(ta, verified.value.client.trustAnchor)
        assertEquals(rpClientKey.kid, verified.value.proofKey.kid)
        assertEquals(listOf("authorization_code"), verified.value.client.clientMetadata["grant_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("once", verified.value.requestClaims!!["jti"]!!.jsonPrimitive.content)
        assertTrue(verified.value.client.validUntilEpochSeconds <= now + 3_600)

        val replay = command.execute(automaticArgs(AutomaticRegistrationProof.RequestObject(jwt)))
        assertIs<InvalidRegistrationError>(replay.error)
    }

    @Test
    fun automaticRequestObjectRefusesWrongAudienceSubjectAlgorithmAndUnpublishedKey() = runTest {
        val command = automatic()
        val extraAudience = requestObject { JsonObject(this + ("aud" to JsonArray(listOf(JsonPrimitive(op), JsonPrimitive("https://other.example"))))) }
        assertIs<InvalidRegistrationError>(command.execute(automaticArgs(AutomaticRegistrationProof.RequestObject(extraAudience))).error)

        val withSubject = requestObject { JsonObject(this + ("sub" to JsonPrimitive(rp))) }
        assertIs<InvalidRegistrationError>(command.execute(automaticArgs(AutomaticRegistrationProof.RequestObject(withSubject))).error)

        val wrongAlg = command.execute(automaticArgs(AutomaticRegistrationProof.RequestObject(requestObject()), algs = listOf("RS256")))
        assertIs<InvalidRegistrationError>(wrongAlg.error)

        val federationKeySigned = requestObject(signer = rpFederationKey)
        val unpublished = command.execute(automaticArgs(AutomaticRegistrationProof.RequestObject(federationKeySigned)))
        assertIs<InvalidMetadataError>(unpublished.error)
    }

    @Test
    fun automaticRegistrationOnlyAcceptsTheCallersTrustAnchors() = runTest {
        val otherAnchor = RegistrationTrustAnchor("https://other-ta.example", listOf(taKey.jwk))
        val discovered = automatic().execute(automaticArgs(AutomaticRegistrationProof.RequestObject(requestObject()), listOf(otherAnchor)))
        assertTrue(discovered.isErr)

        val chain = listOf(rpConfiguration, taAboutRp, taConfiguration)
        val headerChain = requestObject(trustChain = chain)
        val pinnedElsewhere = automatic().execute(automaticArgs(AutomaticRegistrationProof.RequestObject(headerChain), listOf(otherAnchor)))
        assertIs<InvalidTrustAnchorError>(pinnedElsewhere.error)
    }

    @Test
    fun resolvedClientCarriesOnlyPublishedClientSigningKeys() = runTest {
        val command = ResolveRegistrationClientCommandImpl(execution, resolver(), verifier(), context, jwtService)
        val resolved = command.execute(ResolveRegistrationClientArgs(rp, RegistrationProfile.OPENID_CONNECT, anchors))

        assertTrue(resolved.isOk, "client must resolve: ${if (resolved.isErr) resolved.error else ""}")
        assertEquals(listOf(rpClientKey.kid), resolved.value.signingKeys.map { it.kid })
        assertEquals(listOf(rpConfiguration, taAboutRp, taConfiguration), resolved.value.trustChain)

        val notAnEntity = command.execute(ResolveRegistrationClientArgs("client-123", RegistrationProfile.OPENID_CONNECT, anchors))
        assertIs<InvalidRegistrationError>(notAnEntity.error)
        val wrongProfile = command.execute(ResolveRegistrationClientArgs(rp, RegistrationProfile.OAUTH2, anchors))
        assertIs<InvalidMetadataError>(wrongProfile.error)
    }

    @Test
    fun automaticTrustChainHeaderIsVerifiedWithoutDiscovery() = runTest {
        val jwt = requestObject(trustChain = listOf(rpConfiguration, taAboutRp, taConfiguration))
        val before = networkRequests
        val verified = automatic().execute(automaticArgs(AutomaticRegistrationProof.RequestObject(jwt)))

        assertTrue(verified.isOk, "trust_chain header must verify: ${if (verified.isErr) verified.error else ""}")
        assertEquals(before, networkRequests)
        assertEquals(listOf(rpConfiguration, taAboutRp, taConfiguration), verified.value.client.trustChain)
    }

    @Test
    fun automaticPrivateKeyJwtAndSelfSignedCertificateProofs() = runTest {
        val assertion = sign(buildJsonObject {
            put("iss", rp)
            put("sub", rp)
            put("aud", op)
            put("jti", UUID.randomUUID().toString())
            put("exp", now + 60)
        }, rpClientKey, "JWT")
        val byAssertion = automatic().execute(automaticArgs(AutomaticRegistrationProof.PrivateKeyJwt(assertion)))
        assertTrue(byAssertion.isOk, "private_key_jwt must verify: ${if (byAssertion.isErr) byAssertion.error else ""}")
        assertNull(byAssertion.value.requestClaims)

        val unknownCertificate = automatic().execute(automaticArgs(AutomaticRegistrationProof.SelfSignedTlsClientCertificate("MIIBnotpublished")))
        assertIs<InvalidRegistrationError>(unknownCertificate.error)
    }

    @Test
    fun discoveryFromStartingAuthorityHintsOnlyFollowsPublishedSuperiors() = runTest {
        val unpublished = resolver().execute(ResolveTrustChainArgs(rp, arrayOf(ta), startingAuthorityHints = listOf("https://not-a-superior.example")))
        assertIs<TrustChainValidationFailedError>(unpublished.error)

        val none = resolver().execute(ResolveTrustChainArgs(rp, arrayOf(ta), startingAuthorityHints = emptyList()))
        assertIs<TrustChainValidationFailedError>(none.error)

        val restricted = resolver().execute(ResolveTrustChainArgs(rp, arrayOf(ta), startingAuthorityHints = listOf(ta)))
        assertTrue(restricted.isOk, "a published starting hint must resolve: ${if (restricted.isErr) restricted.error else ""}")
        assertEquals(listOf(rpConfiguration, taAboutRp, taConfiguration), restricted.value.trustChain)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Explicit Registration
    // ---------------------------------------------------------------------------------------------------------

    private val accounts get() = mapOf("rp-account" to (rp to rpFederationKey), "op-account" to (op to opFederationKey))

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
        override suspend fun doExecute(
            args: ResolveAccountSigningKeyArgs,
            applyDuring: (ResolveAccountSigningKeyArgs) -> ResolveAccountSigningKeyArgs,
        ): IdkResult<AccountSigningKey, FederationError> {
            val (identifier, key) = accounts[args.accountId] ?: return federationErr(AccountNotFoundError(args.accountId))
            if (identifier != args.expectedEntityIdentifier) return federationErr(AccountNotFoundError(args.accountId))
            return IdkResult.ok(AccountSigningKey(args.accountId, identifier, "key-${key.kid}", key.kid, "ES256", key.alias, key.provider, 1))
        }
    }

    private inner class OwnConfigurations : TypedServiceCommandAdapter<FindEntityConfigurationByAccountArgs, EntityConfigurationStatement, FederationError>(
        commandId = FindEntityConfigurationByAccountCommand.COMMAND_ID,
        execution = execution,
        inputTypeToken = typeToken<FindEntityConfigurationByAccountArgs>(),
        outputTypeToken = typeToken<EntityConfigurationStatement>(),
    ), FindEntityConfigurationByAccountCommand {
        override suspend fun doExecute(
            args: FindEntityConfigurationByAccountArgs,
            applyDuring: (FindEntityConfigurationByAccountArgs) -> FindEntityConfigurationByAccountArgs,
        ): IdkResult<EntityConfigurationStatement, FederationError> {
            if (args.tenantId != "rp-account") return federationErr(AccountNotFoundError(args.tenantId))
            return IdkResult.ok(
                json.decodeFromJsonElement<EntityConfigurationStatement>(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf(ta)))
            )
        }
    }

    private fun create() = CreateExplicitRegistrationRequestCommandImpl(
        execution, resolver(), verifier(), tenants, OwnConfigurations(), SelectedKeys(), jwtService,
    )
    private fun verifyRequest() = VerifyExplicitRegistrationRequestCommandImpl(execution, resolver(), verifier(), context, jwtService)
    private fun signResponse() = SignExplicitRegistrationResponseCommandImpl(execution, tenants, SelectedKeys(), jwtService)
    private fun verifyResponse() = VerifyExplicitRegistrationResponseCommandImpl(execution, resolver(), verifier(), jwtService)

    private suspend fun explicitRequest(includeTrustChains: Boolean): ExplicitRegistrationRequest {
        val created = create().execute(
            CreateExplicitRegistrationRequestArgs(
                accountId = "rp-account",
                providerEntityIdentifier = op,
                profile = RegistrationProfile.OPENID_CONNECT,
                trustAnchors = anchors,
                authorityHints = listOf(ta),
                metadata = rpMetadata,
                includeTrustChains = includeTrustChains,
            )
        )
        assertTrue(created.isOk, "request must be created: ${if (created.isErr) created.error else ""}")
        return created.value
    }

    private suspend fun verified(request: ExplicitRegistrationRequest): VerifiedExplicitRegistrationRequest {
        val verified = verifyRequest().execute(
            VerifyExplicitRegistrationRequestArgs(op, RegistrationProfile.OPENID_CONNECT, request.contentType, request.body, anchors)
        )
        assertTrue(verified.isOk, "request must verify at the provider: ${if (verified.isErr) verified.error else ""}")
        return verified.value
    }

    private fun registered(request: VerifiedExplicitRegistrationRequest) =
        JsonObject(request.clientMetadata + ("client_id" to JsonPrimitive("client-123")))

    @Test
    fun explicitRegistrationRoundTripsBetweenClientAndProvider() = runTest {
        for (includeTrustChains in listOf(false, true)) {
            val request = explicitRequest(includeTrustChains)
            assertEquals("$op/register", request.registrationEndpoint)
            assertEquals(FederationRegistration.ENTITY_STATEMENT_CONTENT_TYPE, request.contentType)
            assertEquals(ta, request.providerTrustAnchor)

            val atProvider = verified(request)
            assertEquals(rp, atProvider.clientEntityIdentifier)
            assertEquals(ta, atProvider.immediateSuperior)
            assertEquals(listOf("authorization_code"), atProvider.clientMetadata["grant_types"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(if (includeTrustChains) request.providerTrustChain else null, atProvider.peerTrustChain)

            val response = signResponse().execute(
                SignExplicitRegistrationResponseArgs("op-account", atProvider, registered(atProvider), atProvider.validUntilEpochSeconds - 5, includeClientJwks = true)
            )
            assertTrue(response.isOk, "response must be signed: ${if (response.isErr) response.error else ""}")
            assertEquals(FederationRegistration.EXPLICIT_RESPONSE_CONTENT_TYPE, response.value.contentType)
            val responseClaims = parseCompactJws(response.value.body)!!
            assertEquals(FederationRegistration.EXPLICIT_RESPONSE_TYP, responseClaims.header.text("typ"))
            assertEquals(atProvider.clientJwks, responseClaims.payload["jwks"])

            val atClient = verifyResponse().execute(VerifyExplicitRegistrationResponseArgs(request, response.value.body, anchors))
            assertTrue(atClient.isOk, "response must verify at the client: ${if (atClient.isErr) atClient.error else ""}")
            assertEquals("client-123", atClient.value.clientId)
            assertEquals(ta, atClient.value.trustAnchor)
            assertEquals(setOf("federation_entity", "openid_relying_party"), atClient.value.registeredMetadata.keys)
            assertTrue(atClient.value.validUntilEpochSeconds <= response.value.expiresAtEpochSeconds)
        }
    }

    @Test
    fun explicitTrustChainBodyIsAccepted() = runTest {
        val requestConfiguration = sign(
            JsonObject(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf(ta)) + ("aud" to JsonPrimitive(op))),
            rpFederationKey, "entity-statement+jwt",
        )
        val body = JsonArray(listOf(requestConfiguration, taAboutRp, taConfiguration).map(::JsonPrimitive)).toString()
        val verified = verifyRequest().execute(
            VerifyExplicitRegistrationRequestArgs(op, RegistrationProfile.OPENID_CONNECT, FederationRegistration.TRUST_CHAIN_CONTENT_TYPE, body, anchors)
        )
        assertTrue(verified.isOk, "trust chain body must verify: ${if (verified.isErr) verified.error else ""}")
        assertEquals(requestConfiguration, verified.value.trustChain.first())
    }

    @Test
    fun explicitRequestRefusesMissingAudienceAndKeysTheSuperiorDoesNotList() = runTest {
        val noAudience = sign(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf(ta)), rpFederationKey, "entity-statement+jwt")
        val refused = verifyRequest().execute(
            VerifyExplicitRegistrationRequestArgs(op, RegistrationProfile.OPENID_CONNECT, FederationRegistration.ENTITY_STATEMENT_CONTENT_TYPE, noAudience, anchors)
        )
        assertIs<InvalidRegistrationError>(refused.error)

        val unlisted = key()
        val selfVouched = sign(
            JsonObject(statementClaims(rp, rp, jwks(unlisted), rpMetadata, listOf(ta)) + ("aud" to JsonPrimitive(op))),
            unlisted, "entity-statement+jwt",
        )
        val notVouched = verifyRequest().execute(
            VerifyExplicitRegistrationRequestArgs(op, RegistrationProfile.OPENID_CONNECT, FederationRegistration.ENTITY_STATEMENT_CONTENT_TYPE, selfVouched, anchors)
        )
        assertIs<InvalidRegistrationError>(notVouched.error)

        val wrongType = verifyRequest().execute(
            VerifyExplicitRegistrationRequestArgs(op, RegistrationProfile.OPENID_CONNECT, "application/json", noAudience, anchors)
        )
        assertIs<InvalidRegistrationError>(wrongType.error)

        val unpublishedHint = sign(
            JsonObject(statementClaims(rp, rp, jwks(rpFederationKey), rpMetadata, listOf("https://not-a-superior.example")) + ("aud" to JsonPrimitive(op))),
            rpFederationKey, "entity-statement+jwt",
        )
        val notPublished = verifyRequest().execute(
            VerifyExplicitRegistrationRequestArgs(op, RegistrationProfile.OPENID_CONNECT, FederationRegistration.ENTITY_STATEMENT_CONTENT_TYPE, unpublishedHint, anchors)
        )
        assertIs<TrustChainValidationFailedError>(notPublished.error)
    }

    @Test
    fun explicitResponseIsBoundToTheProviderTheChainAndTheClient() = runTest {
        val request = explicitRequest(includeTrustChains = false)
        val atProvider = verified(request)

        val tooLong = signResponse().execute(
            SignExplicitRegistrationResponseArgs("op-account", atProvider, registered(atProvider), atProvider.validUntilEpochSeconds + 60, false)
        )
        assertIs<InvalidRegistrationError>(tooLong.error)

        val notTheProvider = signResponse().execute(
            SignExplicitRegistrationResponseArgs("rp-account", atProvider, registered(atProvider), atProvider.validUntilEpochSeconds - 5, false)
        )
        assertIs<InvalidRegistrationError>(notTheProvider.error)

        val withoutClientId = signResponse().execute(
            SignExplicitRegistrationResponseArgs("op-account", atProvider, atProvider.clientMetadata, atProvider.validUntilEpochSeconds - 5, false)
        )
        assertIs<InvalidRegistrationError>(withoutClientId.error)

        val forged = sign(buildJsonObject {
            put("iss", op)
            put("sub", rp)
            put("aud", rp)
            put("iat", now)
            put("exp", now + 600)
            put("trust_anchor", ta)
            put("authority_hints", JsonArray(listOf(JsonPrimitive(ta))))
            put("metadata", JsonObject(rpMetadata + ("openid_relying_party" to registered(atProvider))))
        }, rpFederationKey, FederationRegistration.EXPLICIT_RESPONSE_TYP)
        val notVouched = verifyResponse().execute(VerifyExplicitRegistrationResponseArgs(request, forged, anchors))
        assertIs<InvalidRegistrationError>(notVouched.error)

        val wrongType = sign(parseCompactJws(forged)!!.payload, opFederationKey, "entity-statement+jwt")
        assertIs<InvalidRegistrationError>(verifyResponse().execute(VerifyExplicitRegistrationResponseArgs(request, wrongType, anchors)).error)

        val otherAudience = sign(
            JsonObject(parseCompactJws(forged)!!.payload + ("aud" to JsonPrimitive("https://other.example"))),
            opFederationKey, FederationRegistration.EXPLICIT_RESPONSE_TYP,
        )
        assertIs<InvalidRegistrationError>(verifyResponse().execute(VerifyExplicitRegistrationResponseArgs(request, otherAudience, anchors)).error)

        val fewerTypes = sign(
            JsonObject(parseCompactJws(forged)!!.payload + ("metadata" to buildJsonObject { put("openid_relying_party", registered(atProvider)) })),
            opFederationKey, FederationRegistration.EXPLICIT_RESPONSE_TYP,
        )
        assertIs<InvalidMetadataError>(verifyResponse().execute(VerifyExplicitRegistrationResponseArgs(request, fewerTypes, anchors)).error)
    }
}
