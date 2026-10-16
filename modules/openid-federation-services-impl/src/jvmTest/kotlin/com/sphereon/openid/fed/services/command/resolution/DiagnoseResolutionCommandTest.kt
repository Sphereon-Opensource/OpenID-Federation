package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.KeyVisibility
import com.sphereon.crypto.core.ManagedKeyInfoType
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwsIdentifierMode
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.crypto.jose.jws.JwsValidationResult
import com.sphereon.crypto.jose.jws.command.CreateJwsArgs
import com.sphereon.crypto.jose.jws.command.CreateJwsOpts
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.core.api.error.IdkError
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.crypto.resolution.IdentifierContext
import com.sphereon.crypto.resolution.managed.ManagedOptsKeyInfo
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.FederationClient
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkDelegationCommandImpl
import com.sphereon.openid.fed.client.context.FederationContext
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.helpers.getEntityConfigurationEndpoint
import com.sphereon.openid.fed.client.helpers.getSubordinateStatementEndpoint
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.httpResolver.HttpMetadataCacheSerializers
import com.sphereon.openid.fed.httpResolver.HttpResolver
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

/** Real software-KMS signatures with HTTP responses supplied at the transport boundary. */
class DiagnoseResolutionCommandTest {
    private val leafId = "https://leaf.example"
    private val intermediateId = "https://intermediate.example"
    private val anchorId = "https://anchor.example"
    private val issuerId = "https://issuer.example"
    private val unlistedId = "https://unlisted.example"
    private val markType = "https://marks.example/type"
    private lateinit var execution: SessionExecution
    private lateinit var jwtService: JwtService
    private lateinit var keyManager: com.sphereon.crypto.core.kms.KeyManagerService
    private var now = 0L
    private val clients = mutableListOf<HttpClient>()
    private val responses = mutableMapOf<String, Response>()
    private val requests = mutableListOf<String>()
    private val leafMetadata = obj("""{"federation_entity":{},"openid_provider":{"organization_name":"leaf","unchanged":"same","removed":"old"}}""")

    private data class Key(val signing: ManagedKeyInfoType<*>, val publicJson: JsonObject)
    private data class Response(val body: String, val status: HttpStatusCode = HttpStatusCode.OK,
        val contentType: String = "application/entity-statement+jwt")
    private data class Topology(val chain: List<String>, val anchor: Key, val leaf: Key, val intermediate: Key)

    @BeforeTest
    fun setUp() {
        val app = createExactSuperiorEvidenceTestGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "diagnosis-${hashCode()}", principalType = PrincipalType.ANONYMOUS,
        )
        execution = session.asCoreApiServiceGraph().serviceExecution
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "diagnosis-provider-${hashCode()}"), execution,
        )
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as JwtServiceImpl.Graph).jwtService
        now = getCurrentEpochTimeSeconds()
    }

    @AfterTest
    fun tearDown() { clients.forEach { it.close() } }

    private suspend fun key(): Key {
        val pair = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val public = pair.joseToManagedKeyInfo(KeyVisibility.PUBLIC).key as CryptoJwk
        return Key(pair.joseToManagedKeyInfo(KeyVisibility.PRIVATE),
            cryptoJsonSerializer.encodeToJsonElement(CryptoJwk.serializer(), public).jsonObject)
    }

    private suspend fun sign(issuer: String, key: Key, payload: JsonObject, typ: String = "entity-statement+jwt"): String {
        val signed = jwtService.createJwsCompact(CreateJwsArgs(
            issuer = ManagedOptsKeyInfo(identifier = key.signing,
                context = IdentifierContext(clientId = "diagnosis-test", clientIdScheme = "jwt_vc_json", issuer = issuer)),
            payload = payload, mode = JwsIdentifierMode.KID,
            opts = CreateJwsOpts(noIssPayloadUpdate = true, protectedHeader = buildJsonObject {
                put("typ", typ)
                put("kid", key.signing.kid ?: error("Generated key has no kid"))
            }),
        ))
        assertTrue(signed.isOk, "Signing failed: ${if (signed.isErr) signed.error else ""}")
        return signed.value.jwt
    }

    private suspend fun statement(issuer: String, subject: String, signer: Key, subjectKey: Key,
        expiry: Long = now + 7200, metadata: JsonObject? = null, hints: List<String>? = null,
        policy: JsonObject? = null, constraints: JsonObject? = null, extra: JsonObject = obj("{}"),
        typ: String = "entity-statement+jwt"): String = sign(issuer, signer, buildJsonObject {
        put("iss", issuer); put("sub", subject); put("iat", now - 100); put("exp", expiry)
        put("jwks", buildJsonObject { put("keys", JsonArray(listOf(subjectKey.publicJson))) })
        metadata?.let { put("metadata", it) }
        hints?.let { put("authority_hints", JsonArray(it.map(::JsonPrimitive))) }
        policy?.let { put("metadata_policy", it) }
        constraints?.let { put("constraints", it) }
        extra.forEach { (name, value) -> put(name, value) }
    }, typ)

    private fun publishEc(id: String, jwt: String) { responses[getEntityConfigurationEndpoint(id)] = Response(jwt) }
    private fun publishSs(issuer: String, subject: String, jwt: String) {
        responses[getSubordinateStatementEndpoint("$issuer/fetch", subject)] = Response(jwt)
    }
    private fun endpointMetadata(id: String) = obj("""{"federation_entity":{"federation_fetch_endpoint":"$id/fetch"}}""")

    private suspend fun topology(badSignature: Boolean = false, expired: Boolean = false, maximum: Int = 1,
        conflict: Boolean = false, marks: Boolean = false): Topology {
        val leaf = key(); val intermediate = key(); val anchor = key()
        val markClaims = if (marks) trustMarks(anchor) else obj("{}")
        val ec = statement(leafId, leafId, leaf, leaf, metadata = leafMetadata,
            hints = listOf(intermediateId), extra = markClaims)
        val lower = statement(intermediateId, leafId, if (badSignature) key() else intermediate, leaf,
            expiry = if (expired) now - 60 else now + 5400,
            policy = obj("""{"openid_provider":{"organization_name":{"value":"resolved"},"removed":{"value":null}}}"""),
            constraints = obj("""{"max_path_length":0,"allowed_entity_types":["openid_provider"],"naming_constraints":{"permitted":["leaf.example"]}}"""))
        val upper = statement(anchorId, intermediateId, anchor, intermediate, expiry = now + 3600,
            policy = obj("""{"openid_provider":{"organization_name":{"one_of":["${if (conflict) "other" else "resolved"}"]},"new":{"default":"set"}}}"""),
            constraints = obj("""{"max_path_length":$maximum,"allowed_entity_types":["openid_provider"],"naming_constraints":{"permitted":[".example"]}}"""))
        val anchorEc = statement(anchorId, anchorId, anchor, anchor, metadata = endpointMetadata(anchorId),
            extra = if (marks) obj("""{"trust_mark_issuers":{"$markType":["$issuerId"]}}""") else obj("{}"))
        publishEc(leafId, ec)
        publishEc(intermediateId, statement(intermediateId, intermediateId, intermediate, intermediate,
            metadata = endpointMetadata(intermediateId), hints = listOf(anchorId)))
        publishEc(anchorId, anchorEc)
        publishSs(intermediateId, leafId, lower); publishSs(anchorId, intermediateId, upper)
        return Topology(listOf(ec, lower, upper, anchorEc), anchor, leaf, intermediate)
    }

    private suspend fun trustMarks(anchor: Key): JsonObject {
        val marks = mutableListOf<JsonElement>()
        for (id in listOf(issuerId, unlistedId)) {
            val issuer = key()
            val mark = sign(id, issuer, buildJsonObject {
                put("iss", id); put("sub", leafId); put("iat", now - 60); put("exp", now + 1800)
                put("trust_mark_type", markType)
            }, "trust-mark+jwt")
            marks += buildJsonObject { put("trust_mark_type", markType); put("trust_mark", mark) }
            publishEc(id, statement(id, id, issuer, issuer, hints = listOf(anchorId),
                metadata = obj("""{"federation_entity":{"federation_trust_mark_status_endpoint":"$id/status"}}""")))
            publishSs(anchorId, id, statement(anchorId, id, anchor, issuer))
            responses["$id/status"] = Response(sign(id, issuer, buildJsonObject {
                put("iss", id); put("iat", now); put("trust_mark", mark); put("status", "active")
            }, "trust-mark-status-response+jwt"), contentType = "application/trust-mark-status-response+jwt")
        }
        return buildJsonObject { put("trust_marks", JsonArray(marks)) }
    }

    private fun command(verificationJwtService: JwtService = jwtService): DiagnoseResolutionCommandImpl {
        val http = HttpClient(MockEngine) {
            engine { addHandler { request ->
                requests += request.url.toString()
                if (request.url.encodedPath == "/status") assertEquals(HttpMethod.Post, request.method)
                val response = responses[request.url.toString()] ?: Response("missing", HttpStatusCode.NotFound)
                respond(response.body, response.status, headersOf(HttpHeaders.ContentType, response.contentType))
            } }
        }.also { clients += it }
        val cache = OidfCache.createStringKeyCache(manager = OidfCache.newManager(),
            requirements = CacheRequirements(namespace = "diagnosis-test-${clients.size}"),
            valueSerializer = HttpMetadataCacheSerializers.stringValue)
        val context = FederationContext(jwtService = verificationJwtService,
            httpResolver = HttpResolver(httpClient = http, cache = cache, responseMapper = { it.bodyAsText() }))
        return DiagnoseResolutionCommandImpl(execution, context, VerifyTrustMarkDelegationCommandImpl(execution, context))
    }

    private suspend fun diagnose(command: DiagnoseResolutionCommandImpl = command(),
        args: DiagnoseResolutionArgs = DiagnoseResolutionArgs(leafId, anchorId, includeTrustMarks = false)): ResolutionDiagnosis {
        val result = command.execute(args)
        assertTrue(result.isOk, "Diagnosis failed as a command: ${if (result.isErr) result.error else ""}")
        return result.value
    }

    @Test
    fun validResolutionMatchesServerPolicyAndDescribesEveryStep() = runTest {
        val topology = topology()
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome, diagnosis.errors.toString())
        assertEquals(topology.chain, diagnosis.chain.map { it.jwt })
        assertEquals(listOf(0, 1, 2, 3), diagnosis.chain.map { it.index })
        assertTrue(diagnosis.chain.all { it.signatureValid && it.verified })
        assertEquals(now + 3600, diagnosis.chainExpiresAt)
        assertEquals(diagnosis.chainExpiresAt, diagnosis.expiresAt)
        assertEquals(leafMetadata, diagnosis.selfAssertedMetadata)
        assertEquals(6, diagnosis.constraintChecks.size)
        assertTrue(diagnosis.constraintChecks.all { it.outcome == ConstraintOutcome.PASSED })
        val name = diagnosis.policyTrace.single { it.parameter == "organization_name" }
        assertEquals(listOf(anchorId, intermediateId, leafId), name.contributions.map { it.issuer })
        assertEquals(listOf(2, 1, 0), name.contributions.map { it.statementIndex })
        assertEquals(JsonPrimitive("leaf"), name.valueBefore)
        assertEquals(JsonPrimitive("resolved"), name.valueAfter)
        assertEquals(PolicyTraceOutcome.MODIFIED, name.outcome)
        assertEquals(PolicyTraceOutcome.SET, diagnosis.policyTrace.single { it.parameter == "new" }.outcome)
        assertEquals(PolicyTraceOutcome.REMOVED, diagnosis.policyTrace.single { it.parameter == "removed" }.outcome)
        val client = mockk<FederationClient>()
        coEvery { client.trustChainResolve(leafId, any()) } returns IdkResult.ok(TrustChainResolveResponse(trustChain = topology.chain))
        val tenant = mockk<TenantContextResolver>()
        coEvery { tenant.resolveIdentifier("diagnosis-tenant") } returns "https://resolver.example"
        val resolved = ResolveEntityCommandImpl(execution, tenant, client).execute(
            ResolveEntityArgs("diagnosis-tenant", leafId, arrayOf(anchorId), null))
        assertTrue(resolved.isOk)
        assertEquals(resolved.value.metadata, diagnosis.resolvedMetadata)
        assertEquals(diagnosis, Json.decodeFromString(ResolutionDiagnosis.serializer(),
            Json.encodeToString(ResolutionDiagnosis.serializer(), diagnosis)))
    }

    @Test
    fun failedSubordinateFetchIsIncompleteAtItsOriginalIndex() = runTest {
        topology()
        responses[getSubordinateStatementEndpoint("$intermediateId/fetch", leafId)] = Response("error", HttpStatusCode.InternalServerError)
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INCOMPLETE, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "FETCH_FAILED" && it.statementIndex == 1 })
        assertNull(diagnosis.resolvedMetadata)
        assertEquals(leafMetadata, diagnosis.selfAssertedMetadata)
        assertFalse(diagnosis.chain.any { it.verified })
    }

    @Test
    fun keyAbsentFromSuperiorJwksRejectsSignature() = runTest {
        topology(badSignature = true)
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "SIGNATURE_INVALID" && it.statementIndex == 1 })
        assertFalse(diagnosis.chain[1].signatureValid)
    }

    @Test
    fun expiredStatementDoesNotStopRemainingChecks() = runTest {
        topology(expired = true, maximum = 0)
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "STATEMENT_EXPIRED" && it.statementIndex == 1 })
        assertTrue(diagnosis.errors.any { it.code == "CONSTRAINT_VIOLATION" })
        assertTrue(diagnosis.chain.all { it.signatureValid })
    }

    @Test
    fun anchorPathLengthZeroRejectsIntermediate() = runTest {
        topology(maximum = 0)
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "CONSTRAINT_VIOLATION" && it.statementIndex == 2 })
        assertEquals(ConstraintOutcome.FAILED,
            diagnosis.constraintChecks.single { it.statementIndex == 2 && it.constraint == "max_path_length" }.outcome)
    }

    @Test
    fun mergedPolicyConflictHasAnErrorTraceEntry() = runTest {
        topology(conflict = true)
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "POLICY_CONFLICT" })
        assertEquals(PolicyTraceOutcome.ERROR,
            diagnosis.policyTrace.single { it.parameter == "organization_name" }.outcome)
        assertNull(diagnosis.resolvedMetadata)
    }

    @Test
    fun primaryPolicyInvalidPathDoesNotLabelItsStatementsVerified() = runTest {
        topology(conflict = true)
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "POLICY_CONFLICT" })
        assertTrue(diagnosis.chain.all { it.signatureValid })
        assertFalse(diagnosis.chain.any { it.verified })
    }

    @Test
    fun thumbprintsComparePublishedKeysAsSetsWithoutReplacingThem() = runTest {
        val topology = topology()
        val published = thumbprint(topology.anchor.publicJson)
        val matched = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            expectedTrustAnchorKeyThumbprints = listOf(published, published), includeTrustMarks = false))
        assertEquals(AnchorKeyComparison.MATCH, matched.trustAnchorKeyComparison)
        assertEquals(ResolutionOutcome.VALID, matched.outcome, matched.errors.toString())
        assertEquals(listOf(published), matched.trustAnchorKeyThumbprints)
        val mismatch = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            expectedTrustAnchorKeyThumbprints = listOf("different"), includeTrustMarks = false))
        assertEquals(AnchorKeyComparison.MISMATCH, mismatch.trustAnchorKeyComparison)
        assertEquals(ResolutionOutcome.INVALID, mismatch.outcome)
        assertTrue(mismatch.errors.any { it.code == "TRUST_ANCHOR_KEY_MISMATCH" })
        assertTrue(mismatch.chain.all { it.signatureValid && it.verified })
    }

    @Test
    fun recognizedAndUnlistedMarksKeepTheEntityValid() = runTest {
        topology(marks = true)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome, diagnosis.errors.toString())
        val accepted = diagnosis.trustMarks.single { it.issuer == issuerId }
        assertTrue(accepted.signatureValid && accepted.issuerChainValid && accepted.acceptedByTrustAnchor)
        assertEquals(TrustMarkStatus.ACTIVE, accepted.status)
        assertEquals(TrustMarkStatusSource.STATUS_ENDPOINT, accepted.statusSource)
        assertFalse(diagnosis.trustMarks.single { it.issuer == unlistedId }.acceptedByTrustAnchor)
        assertEquals(now + 1800, diagnosis.expiresAt)
    }

    @Test
    fun disabledStatusChecksUseSignatureAndExpiryOnly() = runTest {
        topology(marks = true)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId, checkTrustMarkStatus = false))
        assertEquals(TrustMarkStatus.ACTIVE, diagnosis.trustMarks.single { it.issuer == issuerId }.status)
        assertTrue(diagnosis.trustMarks.all { it.statusSource == TrustMarkStatusSource.SIGNATURE_ONLY })
        assertFalse(requests.any { it.endsWith("/status") })
    }

    @Test
    fun invalidAndUnacceptedTrustMarksDoNotCallTheirStatusEndpoints() = runTest {
        val topology = topology(marks = true)
        val leaf = com.sphereon.openid.fed.client.mapper.decodeJWTComponents(topology.chain.first()).payload
        val marks = (leaf["trust_marks"] as JsonArray).toMutableList()
        val accepted = marks[0].jsonObject
        val parts = accepted.getValue("trust_mark").jsonPrimitive.content.split('.')
        val signature = parts[2]
        marks[0] = JsonObject(accepted + ("trust_mark" to JsonPrimitive(
            "${parts[0]}.${parts[1]}.${if (signature[0] == 'A') 'B' else 'A'}${signature.drop(1)}",
        )))
        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf, metadata = leafMetadata,
            hints = listOf(intermediateId), extra = buildJsonObject { put("trust_marks", JsonArray(marks)) }))

        requests.clear()
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome)
        val invalid = diagnosis.trustMarks.single { it.issuer == issuerId }
        assertFalse(invalid.signatureValid)
        assertEquals(TrustMarkStatusSource.SIGNATURE_ONLY, invalid.statusSource)
        assertFalse(diagnosis.trustMarks.single { it.issuer == unlistedId }.acceptedByTrustAnchor)
        assertFalse(requests.any { it.endsWith("/status") }, "Invalid or unaccepted marks must not disclose JWTs to status endpoints")
    }

    @Test
    fun bypassCacheFetchesAgainWithoutChangingOtherCallersCacheDefaults() = runTest {
        topology()
        val command = command()
        val cachedArgs = DiagnoseResolutionArgs(leafId, anchorId, bypassCache = false, includeTrustMarks = false)
        assertEquals(ResolutionOutcome.VALID, diagnose(command, cachedArgs).outcome)
        responses[getEntityConfigurationEndpoint(leafId)] = Response("error", HttpStatusCode.InternalServerError)
        assertEquals(ResolutionOutcome.VALID, diagnose(command, cachedArgs).outcome)
        assertEquals(ResolutionOutcome.INCOMPLETE, diagnose(command, cachedArgs.copy(bypassCache = true)).outcome)
    }

    @Test
    fun unavailableAnchorAndWrongMediaTypeAreFetchDiagnostics() = runTest {
        topology()
        responses[getEntityConfigurationEndpoint(anchorId)] = Response("error", HttpStatusCode.NotFound)
        val unavailable = diagnose()
        assertEquals(ResolutionOutcome.INCOMPLETE, unavailable.outcome)
        assertEquals(AnchorKeyComparison.UNAVAILABLE, unavailable.trustAnchorKeyComparison)
        topology()
        val url = getSubordinateStatementEndpoint("$intermediateId/fetch", leafId)
        responses[url] = responses.getValue(url).copy(contentType = "text/plain")
        assertEquals(ResolutionOutcome.INCOMPLETE, diagnose().outcome)
    }

    @Test
    fun validAlternativeWinsOverFailedFetchAndDifferentValidMetadataWarns() = runTest {
        val topology = topology()
        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf, metadata = leafMetadata,
            hints = listOf(intermediateId, anchorId)))
        val directUrl = getSubordinateStatementEndpoint("$anchorId/fetch", leafId)
        responses[directUrl] = Response("error", HttpStatusCode.InternalServerError)
        val alternative = diagnose()
        assertEquals(ResolutionOutcome.VALID, alternative.outcome, alternative.errors.toString())
        assertEquals(4, alternative.chain.size)
        assertTrue(alternative.errors.isEmpty())

        publishSs(anchorId, leafId, statement(anchorId, leafId, topology.anchor, topology.leaf,
            policy = obj("""{"openid_provider":{"organization_name":{"value":"direct"}}}""")))
        val multiple = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId, entityTypes = listOf("federation_entity"), includeTrustMarks = false))
        assertEquals(ResolutionOutcome.VALID, multiple.outcome)
        assertEquals(setOf("federation_entity"), multiple.resolvedMetadata?.keys)
        assertTrue(multiple.warnings.any { it.code == "NON_TREE_TOPOLOGY" }, "Compare full metadata before caller filtering")
    }

    private suspend fun alternativeTopology(badSignature: Boolean = false, badFirstSignature: Boolean = false,
        policyConflict: Boolean = false): String {
        val first = topology(badSignature = badFirstSignature)
        val otherId = "https://intermediate-2.example"
        val other = key()
        publishEc(leafId, statement(leafId, leafId, first.leaf, first.leaf, metadata = leafMetadata,
            hints = listOf(intermediateId, otherId)))
        publishEc(otherId, statement(otherId, otherId, other, other,
            metadata = endpointMetadata(otherId), hints = listOf(anchorId)))
        publishSs(otherId, leafId, statement(otherId, leafId, if (badSignature) key() else other, first.leaf,
            policy = obj("""{"openid_provider":{"organization_name":{"value":"resolved"},"removed":{"value":null}}}""")))
        publishSs(anchorId, otherId, statement(anchorId, otherId, first.anchor, other, expiry = now + 3000,
            policy = if (policyConflict) obj("""{"openid_provider":{"organization_name":{"one_of":["other"]}}}""") else null))
        return otherId
    }

    @Test
    fun requestedAlternativeReportsTheOtherVerifiedPathToTheSameAnchor() = runTest {
        val otherId = alternativeTopology()
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome, diagnosis.errors.toString())
        assertEquals(listOf(leafId, intermediateId, anchorId, anchorId), diagnosis.chain.map { it.issuer })
        assertTrue(diagnosis.chain.all { it.verified })
        val alternative = assertNotNull(diagnosis.alternativePaths).single()
        assertEquals(listOf(leafId, otherId, anchorId), alternative.entities)
        assertEquals(ResolutionOutcome.VALID, alternative.outcome, alternative.errors.toString())
        assertTrue(alternative.errors.isEmpty())
        assertEquals(listOf(0, 1, 2, 3), alternative.chain.map { it.index })
        assertTrue(alternative.chain.all { it.signatureValid && it.verified })
        assertEquals(now + 3000, alternative.chainExpiresAt)
        assertNotNull(alternative.resolvedMetadata)
        val filtered = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            entityTypes = listOf("openid_provider"), includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(setOf("openid_provider"), assertNotNull(filtered.alternativePaths).single().resolvedMetadata?.keys)
        assertEquals(diagnosis, Json.decodeFromString(ResolutionDiagnosis.serializer(),
            Json.encodeToString(ResolutionDiagnosis.serializer(), diagnosis)))
    }

    @Test
    fun alternativesAreAbsentUnlessRequestedAndAnotherAnchorPathExists() = runTest {
        alternativeTopology()
        assertNull(diagnose().alternativePaths)
        topology()
        val single = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, single.outcome)
        assertNull(single.alternativePaths)
    }

    @Test
    fun invalidAlternativeDoesNotInvalidateTheSelectedPathOrExposeMetadata() = runTest {
        val otherId = alternativeTopology(badSignature = true)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome, diagnosis.errors.toString())
        val alternative = assertNotNull(diagnosis.alternativePaths).single()
        assertEquals(listOf(leafId, otherId, anchorId), alternative.entities)
        assertEquals(ResolutionOutcome.INVALID, alternative.outcome)
        assertTrue(alternative.errors.any { it.code == "SIGNATURE_INVALID" && it.statementIndex == 1 })
        assertEquals(4, alternative.chain.size)
        assertFalse(alternative.chain.any { it.verified })
        assertNull(alternative.resolvedMetadata)
    }

    @Test
    fun selectedValidCandidateIsExcludedEvenWhenItWasNotTriedFirst() = runTest {
        val otherId = alternativeTopology(badFirstSignature = true)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome)
        assertEquals(otherId, diagnosis.chain[1].issuer)
        val alternative = assertNotNull(diagnosis.alternativePaths).single()
        assertEquals(listOf(leafId, intermediateId, anchorId), alternative.entities)
        assertEquals(ResolutionOutcome.INVALID, alternative.outcome)
        assertTrue(alternative.errors.any { it.code == "SIGNATURE_INVALID" })
        assertFalse(alternative.chain.any { it.verified })
        assertNull(alternative.resolvedMetadata)
    }

    @Test
    fun policyInvalidAlternativeHasNoVerifiedLabelsEvenWhenItsSignaturesAreValid() = runTest {
        alternativeTopology(policyConflict = true)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome)
        val alternative = assertNotNull(diagnosis.alternativePaths).single()
        assertEquals(ResolutionOutcome.INVALID, alternative.outcome)
        assertTrue(alternative.errors.any { it.code == "POLICY_CONFLICT" })
        assertTrue(alternative.chain.all { it.signatureValid })
        assertFalse(alternative.chain.any { it.verified })
        assertNull(alternative.resolvedMetadata)
        assertEquals(now + 3000, alternative.chainExpiresAt)
    }

    @Test
    fun unavailableSubordinateOnAnAnchorPathIsAnIncompleteAlternative() = runTest {
        val otherId = alternativeTopology()
        responses[getSubordinateStatementEndpoint("$otherId/fetch", leafId)] = Response("error", HttpStatusCode.InternalServerError)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome)
        val alternative = assertNotNull(diagnosis.alternativePaths).single()
        assertEquals(listOf(leafId, otherId, anchorId), alternative.entities)
        assertEquals(ResolutionOutcome.INCOMPLETE, alternative.outcome)
        assertTrue(alternative.errors.any { it.code == "FETCH_FAILED" && it.statementIndex == 1 })
        assertEquals(4, alternative.chain.size)
        assertFalse(alternative.chain.any { it.verified })
        assertNull(alternative.chainExpiresAt)
        assertNull(alternative.resolvedMetadata)
    }

    @Test
    fun unavailableAnchorConfigurationKeepsTheKnownAnchorPathsIncomplete() = runTest {
        val otherId = alternativeTopology()
        responses[getEntityConfigurationEndpoint(anchorId)] = Response("error", HttpStatusCode.InternalServerError)
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.INCOMPLETE, diagnosis.outcome)
        val alternative = assertNotNull(diagnosis.alternativePaths).single()
        val selectedIntermediate = diagnosis.chain[1].issuer
        assertEquals(listOf(leafId, if (selectedIntermediate == intermediateId) otherId else intermediateId, anchorId), alternative.entities)
        assertEquals(ResolutionOutcome.INCOMPLETE, alternative.outcome)
        assertTrue(alternative.errors.any { it.code == "FETCH_FAILED" && it.statementIndex == 2 })
        assertFalse(alternative.chain.any { it.verified })
        assertNull(alternative.chainExpiresAt)
        assertNull(alternative.resolvedMetadata)
    }

    @Test
    fun deadEndsAndLoopsTowardAnotherAnchorAreNotAlternatives() = runTest {
        val otherId = alternativeTopology()
        val other = key()
        publishEc(otherId, statement(otherId, otherId, other, other, metadata = endpointMetadata(otherId)))
        val args = DiagnoseResolutionArgs(leafId, anchorId, includeTrustMarks = false, includeAlternativePaths = true)
        assertNull(diagnose(args = args).alternativePaths)
        publishEc(otherId, statement(otherId, otherId, other, other,
            metadata = endpointMetadata(otherId), hints = listOf(leafId)))
        assertNull(diagnose(args = args).alternativePaths)
    }

    @Test
    fun requestedAlternativeDiscoveryStopsAfterEightAnchorPathsInResolverOrder() = runTest {
        val first = topology()
        val others = (2..9).map { "https://intermediate-$it.example" }
        val deadEndId = "https://dead-end.example"
        val deadEnd = key()
        publishEc(deadEndId, statement(deadEndId, deadEndId, deadEnd, deadEnd, metadata = endpointMetadata(deadEndId)))
        publishSs(deadEndId, leafId, statement(deadEndId, leafId, deadEnd, first.leaf))
        publishEc(leafId, statement(leafId, leafId, first.leaf, first.leaf, metadata = leafMetadata,
            hints = listOf(deadEndId, intermediateId) + others))
        for (id in others) {
            val intermediate = key()
            publishEc(id, statement(id, id, intermediate, intermediate,
                metadata = endpointMetadata(id), hints = listOf(anchorId)))
            publishSs(id, leafId, statement(id, leafId, intermediate, first.leaf,
                policy = obj("""{"openid_provider":{"organization_name":{"value":"resolved"}}}""")))
            publishSs(anchorId, id, statement(anchorId, id, first.anchor, intermediate))
        }
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
            includeTrustMarks = false, includeAlternativePaths = true))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome)
        assertEquals(others.take(7), assertNotNull(diagnosis.alternativePaths).map { it.entities[1] })
        assertFalse(requests.any { it.contains("intermediate-9.example") }, "The ninth path must not be fetched")
        requests.clear()
        assertNull(diagnose().alternativePaths)
        assertTrue(requests.any { it.contains("intermediate-9.example") }, "Unrequested alternatives preserve existing discovery behavior")
    }

    @Test
    fun deadEndAuthorityFanoutIsBoundedWhetherAlternativesAreRequestedOrNot() = runTest {
        val topology = topology()
        val deadEnds = (1..150).map { "https://dead-end-$it.example" }
        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf,
            metadata = leafMetadata, hints = deadEnds))

        for (includeAlternatives in listOf(false, true)) {
            requests.clear()
            val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId,
                includeTrustMarks = false, includeAlternativePaths = includeAlternatives))
            assertEquals(ResolutionOutcome.INCOMPLETE, diagnosis.outcome)
            assertTrue(diagnosis.errors.any { it.code == "DISCOVERY_BUDGET_EXCEEDED" })
            assertTrue(requests.size <= DiagnosisPathResolver.MAX_FETCH_OPERATIONS,
                "Expected at most ${DiagnosisPathResolver.MAX_FETCH_OPERATIONS} fetch operations, got ${requests.size}")
        }
    }

    @Test
    fun noHintsLoopAndDepthEachProduceInvalidDiagnostics() = runTest {
        val topology = topology()
        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf, metadata = leafMetadata))
        val noHints = diagnose()
        assertEquals(ResolutionOutcome.INVALID, noHints.outcome)
        assertTrue(noHints.errors.any { it.code == "AUTHORITY_HINT_MISSING" })
        assertTrue(noHints.errors.any { it.code == "NO_PATH_TO_TRUST_ANCHOR" })

        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf, metadata = leafMetadata, hints = listOf(leafId)))
        val loop = diagnose()
        assertEquals(ResolutionOutcome.INVALID, loop.outcome)
        assertTrue(loop.errors.any { it.code == "LOOP_DETECTED" })

        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf, metadata = leafMetadata, hints = listOf(intermediateId)))
        val depth = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId, maxDepth = 1, includeTrustMarks = false))
        assertEquals(ResolutionOutcome.INVALID, depth.outcome)
        assertTrue(depth.errors.any { it.code == "MAX_DEPTH_EXCEEDED" && it.statementIndex == 2 })
    }

    @Test
    fun criticalOperatorAndPolicyViolationsRemainDiagnosticWithoutRequestedTrace() = runTest {
        val topology = topology()
        publishSs(intermediateId, leafId, statement(intermediateId, leafId, topology.intermediate, topology.leaf,
            policy = obj("""{"openid_provider":{"organization_name":{"extension":"unknown"}}}"""),
            extra = obj("""{"metadata_policy_crit":["extension"]}""")))
        val critical = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId, includePolicyTrace = false, includeTrustMarks = false))
        assertEquals(ResolutionOutcome.INVALID, critical.outcome)
        assertTrue(critical.errors.any { it.code == "UNKNOWN_CRITICAL_OPERATOR" })
        assertTrue(critical.policyTrace.isEmpty())

        publishSs(intermediateId, leafId, statement(intermediateId, leafId, topology.intermediate, topology.leaf))
        val violation = diagnose()
        assertEquals(ResolutionOutcome.INVALID, violation.outcome)
        assertTrue(violation.errors.any { it.code == "POLICY_VIOLATION" })
        assertEquals(PolicyTraceOutcome.ERROR, violation.policyTrace.single { it.parameter == "organization_name" }.outcome)
    }

    @Test
    fun wrongTypAndLinkIdentifiersAreInvalidEvenWithRealSignatures() = runTest {
        val topology = topology()
        publishSs(intermediateId, leafId, statement("https://wrong.example", leafId, topology.intermediate, topology.leaf,
            typ = "wrong+jwt"))
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "TYP_INVALID" && it.statementIndex == 1 })
        assertTrue(diagnosis.errors.any { it.code == "ISSUER_SUBJECT_MISMATCH" && it.statementIndex == 1 })
    }

    @Test
    fun syntacticallyMalformedTrustMarkInvalidatesTheEntityStatement() = runTest {
        val topology = topology()
        publishEc(leafId, statement(leafId, leafId, topology.leaf, topology.leaf, metadata = leafMetadata,
            hints = listOf(intermediateId), extra = buildJsonObject {
                put("trust_marks", JsonArray(listOf(obj("""{"trust_mark_type":"https://marks.example/malformed","trust_mark":"invalid"}"""))))
            }))
        val diagnosis = diagnose()
        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertTrue(diagnosis.errors.any { it.code == "SIGNATURE_INVALID" && it.statementIndex == 0 })
    }

    @Test
    fun invalidTrustMarkStatusSignatureDoesNotFailTheChain() = runTest {
        topology(marks = true)
        val status = responses.getValue("$issuerId/status")
        val parts = status.body.split('.')
        val signature = parts[2]
        responses["$issuerId/status"] = status.copy(body = "${parts[0]}.${parts[1]}.${if (signature[0] == 'A') 'B' else 'A'}${signature.drop(1)}")
        val diagnosis = diagnose(args = DiagnoseResolutionArgs(leafId, anchorId))
        assertEquals(ResolutionOutcome.VALID, diagnosis.outcome, diagnosis.errors.toString())
        assertEquals(TrustMarkStatus.UNKNOWN, diagnosis.trustMarks.single { it.issuer == issuerId }.status)
        assertEquals(now + 3600, diagnosis.expiresAt, "Only included, accepted active marks shorten resolution lifetime")
    }

    @Test
    fun verifierInfrastructureFaultIsACommandErrorAndCancellationEscapes() = runTest {
        topology()
        val failed = object : JwtService by jwtService {
            override suspend fun verifyJws(args: VerifyJwsArgs): IdkResult<JwsValidationResult, IdkError> {
                throw IllegalStateException("verification dependency failed")
            }
        }
        val args = DiagnoseResolutionArgs(leafId, anchorId, includeTrustMarks = false)
        assertTrue(command(failed).execute(args).isErr)
        val cancelled = object : JwtService by jwtService {
            override suspend fun verifyJws(args: VerifyJwsArgs): IdkResult<JwsValidationResult, IdkError> {
                throw CancellationException("verification cancelled")
            }
        }
        assertFailsWith<CancellationException> { command(cancelled).execute(args) }
    }

    @Test
    fun unreadableRequiredHeaderDoesNotHideLaterValidationFailures() = runTest {
        val topology = topology(maximum = 0, conflict = true)
        val parts = topology.chain.first().split('.')
        val header = Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8)).jsonObject
        val withoutKid = JsonObject(header.filterKeys { it != "kid" })
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(withoutKid.toString().toByteArray())
        publishEc(leafId, "$encoded.${parts[1]}.${parts[2]}")
        val diagnosis = diagnose()

        assertEquals(ResolutionOutcome.INVALID, diagnosis.outcome)
        assertEquals(4, diagnosis.chain.size)
        assertEquals(leafMetadata, diagnosis.selfAssertedMetadata)
        assertTrue(diagnosis.errors.any { it.code == "KID_NOT_FOUND" && it.statementIndex == 0 })
        assertTrue(diagnosis.errors.any { it.code == "CONSTRAINT_VIOLATION" && it.statementIndex == 2 })
        assertTrue(diagnosis.errors.any { it.code == "POLICY_CONFLICT" })
        assertFalse(diagnosis.chain.any { it.verified })
    }

    private fun thumbprint(key: JsonObject): String {
        val canonical = JsonObject(listOf("crv", "kty", "x", "y").associateWith { key.getValue(it) }).toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
    }
    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
