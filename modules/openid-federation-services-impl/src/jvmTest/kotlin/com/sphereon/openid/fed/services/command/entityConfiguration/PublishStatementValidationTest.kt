package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.services.command.jwk.AccountSigningKey
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.services.command.renewal.PublishedStatementCacheInvalidator
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import com.sphereon.openid.fed.services.command.subordinate.GetSubordinateStatementCommand
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementArgs
import com.sphereon.openid.fed.services.command.subordinate.PublishSubordinateStatementCommandImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertTrue

/**
 * OpenID Federation 1.1 section 3: an entity must not sign a statement that verifiers, this Library's own resolver
 * included, reject. The publish commands run the same structural rules before the signing call. Signing itself is
 * a stub that records whether it was reached; dry runs keep persistence out of the test.
 */
class PublishStatementValidationTest {
    private val issuer = "https://superior.example"
    private val subject = "https://subordinate.example"
    private val tenantId = "tenant-under-test"
    private val now = System.currentTimeMillis() / 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val key = """{"kty":"EC","crv":"P-256","kid":"k1","x":"WbbaSStuD-Zy3WkzJc5w2eFIMrYXWGQnkDuEFbCMS8E","y":"1MAp39Sqy5TaxWDmp9Jn4bdeOu-Vt-0OADq4GNGXdPY"}"""

    private lateinit var session: SessionInstance
    private lateinit var execution: SessionExecution
    private lateinit var jwtService: JwtService
    private lateinit var tenants: TenantContextResolver
    private lateinit var signingKeys: ResolveAccountSigningKeyCommand
    private val invalidator = object : PublishedStatementCacheInvalidator {
        override suspend fun published(statementJwt: String) = Unit
    }

    @Before
    fun setUp() {
        val app = createExactSuperiorEvidenceTestGraph(this)
        session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "publish-validation-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        execution = session.asCoreApiServiceGraph().serviceExecution
        jwtService = mockk()
        coEvery { jwtService.createJwsCompact(any()) } throws IllegalStateException("signing reached")
        tenants = mockk()
        coEvery { tenants.resolveIdentifier(tenantId) } returns issuer
        signingKeys = mockk()
        coEvery { signingKeys.execute(any()) } returns IdkResult.ok(
            AccountSigningKey(tenantId, issuer, "key-1", "k1", "ES256", "alias-1", "software", 1L))
    }

    @After
    fun tearDown() {
        if (::session.isInitialized) session.destroy()
    }

    private fun claims(extra: String): JsonObject = json.parseToJsonElement(
        """{"iat":$now,"exp":${now + 3600},"jwks":{"keys":[$key]}$extra}""").jsonObject

    private suspend fun publishEc(extra: String): IdkResult<String, FederationError> {
        val statement = json.decodeFromJsonElement(EntityConfigurationStatement.serializer(),
            claims(""","iss":"$issuer","sub":"$issuer"$extra"""))
        val find = mockk<FindEntityConfigurationByAccountCommand>()
        coEvery { find.execute(any()) } returns IdkResult.ok(statement)
        return PublishEntityConfigurationCommandImpl(execution, find, jwtService, tenants, signingKeys, invalidator)
            .execute(PublishEntityConfigurationArgs(tenantId, dryRun = true))
    }

    private suspend fun publishSs(extra: String, jwks: String = """{"keys":[$key]}"""): IdkResult<String, FederationError> {
        val payload = json.parseToJsonElement(
            """{"iss":"$issuer","sub":"$subject","iat":$now,"exp":${now + 3600},"jwks":$jwks$extra}""").jsonObject
        val statement = json.decodeFromJsonElement(SubordinateStatement.serializer(), payload)
        val get = mockk<GetSubordinateStatementCommand>()
        coEvery { get.execute(any()) } returns IdkResult.ok(statement)
        return PublishSubordinateStatementCommandImpl(execution, tenants, jwtService, get, signingKeys, invalidator)
            .execute(PublishSubordinateStatementArgs(tenantId, "subordinate-1", dryRun = true))
    }

    private fun assertRefused(result: IdkResult<String, FederationError>, why: String) {
        assertTrue(result.isErr && result.error is InvalidRequestError, "$why must be refused before signing, got $result")
        coVerify(exactly = 0) { jwtService.createJwsCompact(any()) }
    }

    private fun assertSigningReached(result: IdkResult<String, FederationError>, why: String) {
        assertTrue(result.isErr && result.error !is InvalidRequestError, "$why must reach signing, got $result")
        coVerify(exactly = 1) { jwtService.createJwsCompact(any()) }
    }

    @Test
    fun validEntityConfigurationReachesSigning() = runTest {
        assertSigningReached(publishEc(""","metadata":{"federation_entity":{"organization_name":"Superior"}}"""),
            "an Entity Configuration with an Entity Type")
    }

    @Test
    fun entityConfigurationWithoutEntityTypeIsNotSigned() = runTest {
        // Section 1.2: an Entity MUST be of at least one type.
        assertRefused(publishEc(""","metadata":{}"""), "an Entity Configuration without an Entity Type")
    }

    @Test
    fun entityConfigurationWithNullMetadataValueIsNotSigned() = runTest {
        // Section 5: metadata parameters MUST NOT be null.
        assertRefused(publishEc(""","metadata":{"federation_entity":{},"openid_relying_party":{"client_name":null}}"""),
            "a null metadata value")
    }

    @Test
    fun federationEntityWithProtocolKeysIsNotSigned() = runTest {
        // Section 5.1.1: federation_entity metadata MUST NOT carry jwks, jwks_uri or signed_jwks_uri.
        assertRefused(publishEc(""","metadata":{"federation_entity":{"jwks":{"keys":[$key]}}}"""),
            "protocol JWK Set parameters in federation_entity")
    }

    @Test
    fun entityConfigurationWithInvalidAuthorityHintIsNotSigned() = runTest {
        // Section 3.1.2: authority_hints entries are Entity Identifiers.
        assertRefused(publishEc(""","metadata":{"federation_entity":{}},"authority_hints":["http://superior.example"]"""),
            "an authority hint that is not an Entity Identifier")
    }

    @Test
    fun trustAnchorHintsWithoutSuperiorsAreNotSigned() = runTest {
        // Section 3.1.2: trust_anchor_hints MUST NOT be present in the Entity Configuration of a Trust Anchor
        // with no Superiors.
        assertRefused(publishEc(""","metadata":{"federation_entity":{}},"trust_anchor_hints":["https://anchor.example"]"""),
            "trust_anchor_hints without authority_hints")
    }

    @Test
    fun trustAnchorHintsWithSuperiorsReachSigning() = runTest {
        assertSigningReached(publishEc(""","metadata":{"federation_entity":{}},"authority_hints":["https://anchor.example"],"trust_anchor_hints":["https://anchor.example"]"""),
            "trust_anchor_hints next to authority_hints")
    }

    @Test
    fun noneAsEndpointAuthenticationAlgorithmIsNotSigned() = runTest {
        // Section 5.1.1: the value none MUST NOT be used in endpoint_auth_signing_alg_values_supported.
        assertRefused(publishEc(""","metadata":{"federation_entity":{"endpoint_auth_signing_alg_values_supported":["ES256","none"]}}"""),
            "none as an endpoint authentication algorithm")
    }

    @Test
    fun validSubordinateStatementReachesSigning() = runTest {
        assertSigningReached(publishSs(""), "a valid Subordinate Statement")
    }

    @Test
    fun subordinateStatementWithDuplicateKidsIsNotSigned() = runTest {
        // Section 3.1: each key in jwks MUST have a unique kid.
        assertRefused(publishSs("", jwks = """{"keys":[$key,$key]}"""), "duplicate key IDs")
    }

    @Test
    fun federationEndpointsInSubordinateStatementMetadataAreNotSigned() = runTest {
        // Sections 8.1, 8.2 and 8.4: these endpoints MUST be published in the Entity Configuration, not in Subordinate Statements.
        for (endpoint in listOf("federation_fetch_endpoint", "federation_list_endpoint", "federation_trust_mark_status_endpoint")) {
            io.mockk.clearMocks(jwtService, answers = false, recordedCalls = true)
            assertRefused(publishSs(""","metadata":{"federation_entity":{"$endpoint":"https://subordinate.example/x"}}"""),
                "$endpoint in Subordinate Statement metadata")
        }
    }

    @Test
    fun subordinateStatementWithOrganizationMetadataReachesSigning() = runTest {
        assertSigningReached(publishSs(""","metadata":{"federation_entity":{"organization_name":"Subordinate"}}"""),
            "organization metadata in a Subordinate Statement")
    }

    @Test
    fun federationEndpointsMustBeHttpsWithoutFragment() = runTest {
        // Section 5.1.1: each federation endpoint URL MUST use https and MUST NOT contain a fragment.
        for (url in listOf("http://superior.example/resolve", "https://superior.example/resolve#x", "not a url")) {
            io.mockk.clearMocks(jwtService, answers = false, recordedCalls = true)
            assertRefused(publishEc(""","metadata":{"federation_entity":{"federation_resolve_endpoint":"$url"}}"""),
                "federation_resolve_endpoint $url")
        }
    }

    @Test
    fun federationEndpointWithQueryReachesSigning() = runTest {
        // Section 5.1.1: port, path and query components are allowed.
        assertSigningReached(publishEc(""","metadata":{"federation_entity":{"federation_resolve_endpoint":"https://superior.example:8443/fed?op=resolve"}}"""),
            "an https endpoint with port and query")
    }

    @Test
    fun protocolKeysWithDuplicateKidsAreNotSigned() = runTest {
        // Wallet Architectures section 6 and RFC 7517: each key in an Entity Type's jwks needs a unique kid.
        val other = """{"kty":"EC","crv":"P-256","kid":"k1","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}"""
        assertRefused(publishEc(""","metadata":{"federation_entity":{},"openid_credential_verifier":{"jwks":{"keys":[$key,$other]}}}"""),
            "duplicate kids in openid_credential_verifier jwks")
    }

    @Test
    fun theSameRulesApplyToEveryCallerOfTheSigningValidation() {
        // The administration and prepared-signing paths call StatementSigningValidation too.
        val header = com.sphereon.openid.fed.openapi.models.JwtHeader(typ = "entity-statement+jwt", kid = "k1", alg = "ES256")
        val statement = claims(""","iss":"$issuer","sub":"$issuer","metadata":{"federation_entity":{}},"trust_anchor_hints":["https://anchor.example"]""")
        assertTrue(com.sphereon.openid.fed.services.command.StatementSigningValidation.refusal(statement, header) != null)
    }
}
