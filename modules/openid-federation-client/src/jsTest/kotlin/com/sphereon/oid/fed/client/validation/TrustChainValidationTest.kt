package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.common.jwt.JwtSignInput
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngine.Companion.invoke
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.Array
import kotlin.js.Date
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrustChainValidationTest {

    val jwtServiceImpl = MockJwtServiceJS()

    // key pairs
    @OptIn(ExperimentalJsExport::class)
    var partyBKeyPair: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var intermediateEntityKeyPair: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var intermediateEntity1KeyPair: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var validTrustAnchorKeyPair: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var unknownTrustAnchorKeyPair: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var invalidTrustAnchorKeyPair: dynamic = null

    // configurations
    lateinit var partyBConfiguration: EntityConfigurationStatement
    lateinit var intermediateEntityConfiguration: EntityConfigurationStatement
    lateinit var intermediateEntityConfiguration1: EntityConfigurationStatement
    lateinit var validTrustAnchorConfiguration: EntityConfigurationStatement
    lateinit var unknownTrustAnchorConfiguration: EntityConfigurationStatement
    lateinit var invalidTrustAnchorConfiguration: EntityConfigurationStatement

    // subordinate statements
    lateinit var intermediateEntitySubordinateStatement: SubordinateStatement
    lateinit var intermediateEntity1SubordinateStatement: SubordinateStatement

    @OptIn(ExperimentalJsExport::class)
    var partyBJwk: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var intermediateEntityConfigurationJwk: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var intermediateEntityConfiguration1Jwk: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var validTrustAnchorConfigurationJwk: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var unknownTrustAnchorConfigurationJwk: dynamic = null
    @OptIn(ExperimentalJsExport::class)
    var invalidTrustAnchorConfigurationJwk: dynamic = null

    lateinit var partyBJwt: String
    lateinit var intermediateEntityConfigurationJwt: String
    lateinit var intermediateEntityConfiguration1Jwt: String
    lateinit var validTrustAnchorConfigurationJwt: String
    lateinit var unknownTrustAnchorConfigurationJwt: String
    lateinit var invalidTrustAnchorConfigurationJwt: String

    lateinit var intermediateEntitySubordinateStatementJwt: String
    lateinit var intermediateEntity1SubordinateStatementJwt: String

    lateinit var listOfEntityConfigurationStatementList: MutableList<MutableList<EntityConfigurationStatement>>
    lateinit var listOfSubordinateStatementList: MutableList<MutableList<String>>

    fun signPartyBJWT() = CoroutineScope(context = CoroutineName("TEST")).promise {
        partyBJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), partyBConfiguration).jsonObject,
                header = JWTHeader(
                    alg = "PS256",
                    typ = "entity-statement+jwt",
                    kid = partyBJwk.kid
                ),
                key = partyBKeyPair.privateKey
            )
        ).await()
    }

    fun signIntermediateEntityConfiguration() = CoroutineScope(context = CoroutineName("TEST")).promise {
        intermediateEntityConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(
                    serializer = EntityConfigurationStatement.serializer(),
                    intermediateEntityConfiguration
                ).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = intermediateEntityConfigurationJwk.kid
                ),
                key = intermediateEntityConfiguration1Jwk
            )
        ).await()
    }

    fun signIntermediateSubordinateStatement() = CoroutineScope(context = CoroutineName("TEST")).promise {
        intermediateEntitySubordinateStatementJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(serializer = SubordinateStatement.serializer(), intermediateEntitySubordinateStatement).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = intermediateEntityConfigurationJwk.kid
                ),
                key = intermediateEntityConfiguration1Jwk
            )
        ).await()
    }

    fun signIntermediateEntityConfiguration1() = CoroutineScope(context = CoroutineName("TEST")).promise {
        intermediateEntityConfiguration1Jwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(
                    serializer = EntityConfigurationStatement.serializer(),
                    intermediateEntityConfiguration1
                ).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = intermediateEntityConfiguration1Jwk.kid
                ),
                key = validTrustAnchorConfigurationJwk
            )
        ).await()
    }

    fun signIntermediateEntity1SubordinateStatement() = CoroutineScope(context = CoroutineName("TEST")).promise {
        intermediateEntity1SubordinateStatementJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(serializer = SubordinateStatement.serializer(), intermediateEntity1SubordinateStatement).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = intermediateEntityConfiguration1Jwk.kid
                ),
                key = validTrustAnchorConfigurationJwk
            )
        ).await()
    }

    fun signValidTrustAnchorConfiguration() = CoroutineScope(context = CoroutineName("TEST")).promise {
        validTrustAnchorConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(
                    serializer = EntityConfigurationStatement.serializer(),
                    validTrustAnchorConfiguration
                ).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = validTrustAnchorConfigurationJwk.kid
                ),
                key = validTrustAnchorConfigurationJwk
            )
        ).await()
    }

    fun signUnknownTrustAnchorConfiguration() = CoroutineScope(context = CoroutineName("TEST")).promise {
        unknownTrustAnchorConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(
                    serializer = EntityConfigurationStatement.serializer(),
                    unknownTrustAnchorConfiguration
                ).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = unknownTrustAnchorConfigurationJwk.kid
                ),
                key = unknownTrustAnchorConfigurationJwk
            )
        ).await()
    }

    fun signInvalidTrustChainConfiguration() = CoroutineScope(context = CoroutineName("TEST")).promise {
        invalidTrustAnchorConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
                payload = Json.encodeToJsonElement(
                    serializer = EntityConfigurationStatement.serializer(),
                    invalidTrustAnchorConfiguration
                ).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = invalidTrustAnchorConfigurationJwk.kid
                ),
                key = invalidTrustAnchorConfigurationJwk
            )
        ).await()
    }

    @OptIn(ExperimentalJsExport::class)
    suspend fun setup() {

        partyBKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
        intermediateEntityKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
        intermediateEntity1KeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
        validTrustAnchorKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
        unknownTrustAnchorKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
        invalidTrustAnchorKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))

        partyBJwk = convertToJwk(partyBKeyPair)
        intermediateEntityConfigurationJwk = convertToJwk(intermediateEntityKeyPair)
        intermediateEntityConfiguration1Jwk = convertToJwk(intermediateEntity1KeyPair)
        validTrustAnchorConfigurationJwk = convertToJwk(validTrustAnchorKeyPair)
        unknownTrustAnchorConfigurationJwk = convertToJwk(unknownTrustAnchorKeyPair)
        invalidTrustAnchorConfigurationJwk = convertToJwk(invalidTrustAnchorKeyPair)

        // Party B Entity Configuration (federation)
        partyBConfiguration = entityConfiguration(
            publicKey = partyBJwk,
            authorityHints = arrayOf(
                "https://edugain.org/federation_one",
                "https://edugain.org/federation_two"
            ),
            iss = "https://openid.sunet.se",
            sub = "https://openid.sunet.se",
            federationFetchEndpoint = "https://edugain.org/federation/federation_fetch_endpoint"
        )

        signPartyBJWT().await()

        // Federation 2
        intermediateEntityConfiguration = entityConfiguration(
            publicKey = intermediateEntityConfigurationJwk,
            authorityHints = arrayOf(
                "https://edugain.org/federation_three",
                "https://edugain.org/federation_four"
            ),
            iss = "https://openid.sunet-one.se",
            sub = "https://openid.sunet.se",
            federationFetchEndpoint = "https://edugain.org/federation_two/federation_fetch_endpoint"
        )

        signIntermediateEntityConfiguration().await()

        //signed with intermediateEntity1 Private Key
        intermediateEntitySubordinateStatement = intermediateEntity(
            publicKey = intermediateEntityConfigurationJwk,
            iss = "https://openid.sunet-one.se",
            sub = "https://openid.sunet.se",
        )

        signIntermediateSubordinateStatement().await()

        // Federation 4
        intermediateEntityConfiguration1 = entityConfiguration(
            publicKey = intermediateEntityConfiguration1Jwk,
            authorityHints = arrayOf("https://edugain.org/federation_five"),
            iss = "https://openid.sunet-two.se",
            sub = "https://openid.sunet-one.se",
            federationFetchEndpoint = "https://edugain.org/federation_four/federation_fetch_endpoint"
        )

        signIntermediateEntityConfiguration1().await()

        intermediateEntity1SubordinateStatement = intermediateEntity(
            publicKey = intermediateEntityConfiguration1Jwk,
            iss = "https://openid.sunet-two.se",
            sub = "https://openid.sunet-one.se"
        )

        signIntermediateEntity1SubordinateStatement().await()

        // Federation 5
        validTrustAnchorConfiguration = entityConfiguration(
            publicKey = validTrustAnchorConfigurationJwk,
            authorityHints = arrayOf(),
            iss = "https://openid.sunet-five.se",
            sub = "https://openid.sunet-five.se",
            federationFetchEndpoint = "https://edugain.org/federation_five/federation_fetch_endpoint"
        )

        signValidTrustAnchorConfiguration().await()

        // Federation 3
        unknownTrustAnchorConfiguration = entityConfiguration(
            publicKey = unknownTrustAnchorConfigurationJwk,
            authorityHints = arrayOf(),
            iss = "https://openid.sunet-three.se",
            sub = "https://openid.sunet-three.se",
            federationFetchEndpoint = "https://edugain.org/federation_three/federation_fetch_endpoint"
        )

        signUnknownTrustAnchorConfiguration().await()

        // Federation 1
        invalidTrustAnchorConfiguration = entityConfiguration(
            publicKey = invalidTrustAnchorConfigurationJwk,
            authorityHints = arrayOf(),
            iss = "https://openid.sunet-invalid.se",
            sub = "https://openid.sunet-invalid.se",
            federationFetchEndpoint = "https://edugain.org/federation_one/federation_fetch_endpoint"
        )

        signInvalidTrustChainConfiguration().await()

        listOfEntityConfigurationStatementList = mutableListOf(
            mutableListOf(
                partyBConfiguration, invalidTrustAnchorConfiguration
            ),
            mutableListOf(
                partyBConfiguration, intermediateEntityConfiguration, unknownTrustAnchorConfiguration
            ),
            mutableListOf(
                partyBConfiguration, intermediateEntityConfiguration, intermediateEntityConfiguration1, validTrustAnchorConfiguration
            )
        )

        listOfSubordinateStatementList = mutableListOf(
            mutableListOf(
                partyBJwt, invalidTrustAnchorConfigurationJwt
            ),
            mutableListOf(
                partyBJwt, intermediateEntitySubordinateStatementJwt, unknownTrustAnchorConfigurationJwt
            ),
            mutableListOf(
                partyBJwt, intermediateEntitySubordinateStatementJwt, intermediateEntity1SubordinateStatementJwt, validTrustAnchorConfigurationJwt
            )
        )
    }

    private val mockEngine = MockEngine { request ->
        when (request.url) {
            Url("https://edugain.org/federation") -> respond(
                content = ByteReadChannel(partyBJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            // Entity Configuration - sub and key binding
            Url("https://edugain.org/federation/federation_fetch_endpoint") -> respond(
                content = ByteReadChannel(partyBJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_one") -> respond(
                content = ByteReadChannel(invalidTrustAnchorConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            // Entity Configuration - Trust Anchor
            Url("https://edugain.org/federation_one/federation_fetch_endpoint") -> respond(
                content = ByteReadChannel(invalidTrustAnchorConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_two") -> respond(
                content = ByteReadChannel(intermediateEntityConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            // Subordinate Statement - sub and key binding
            Url("https://edugain.org/federation_two/federation_fetch_endpoint") -> respond(
                content = ByteReadChannel(intermediateEntitySubordinateStatementJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_three") -> respond(
                content = ByteReadChannel(unknownTrustAnchorConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            // Entity Configuration - Trust Anchor
            Url("https://edugain.org/federation_three/federation_fetch_endpoint") -> respond(
                content = ByteReadChannel(unknownTrustAnchorConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_four") -> respond(
                content = ByteReadChannel(intermediateEntityConfiguration1Jwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            // Subordinate Statement
            Url("https://edugain.org/federation_four/federation_fetch_endpoint") -> respond(
                content = ByteReadChannel(intermediateEntity1SubordinateStatementJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_five") -> respond(
                content = ByteReadChannel(validTrustAnchorConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            // Entity Configuration - Trust Chain
            Url("https://edugain.org/federation_five/federation_fetch_endpoint") -> respond(
                content = ByteReadChannel(validTrustAnchorConfigurationJwt),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            else -> error("Unhandled ${request.url}")
        }
    }

    @Ignore
    @Test
    fun readAuthorityHintsTest() = runTest {
        val trustChainValidationService = TrustChainValidationServiceJS
            .register( MockTrustChainValidationServiceJS(
                httpService = MockHttpClientCallbackServiceJS(
                    engine = mockEngine
                ),
                jwtService = MockJwtServiceJS()
            ))
        assertEquals(
            listOfEntityConfigurationStatementList.map { it.toTypedArray() }.toTypedArray(),
            trustChainValidationService.readAuthorityHints(
                partyBId = "https://edugain.org/federation"
            ).await()
        )
    }

    @Ignore
    @Test
    fun fetchSubordinateStatementsTest() = runTest {
        val trustChainValidationService = TrustChainValidationServiceJS
            .register( MockTrustChainValidationServiceJS(
                httpService = MockHttpClientCallbackServiceJS(
                    engine = mockEngine
                ),
                jwtService = MockJwtServiceJS()
            ))
        assertEquals(
            listOfSubordinateStatementList.asDynamic() ,
            trustChainValidationService.fetchSubordinateStatements(
                entityConfigurationStatementsList = listOfEntityConfigurationStatementList.map { it.toTypedArray() }.toTypedArray()
            )
        )
    }

    @Test
    fun validateTrustChainTest() = runTest {
        setup()
        val trustChainValidationService = TrustChainValidationServiceJS
            .register( MockTrustChainValidationServiceJS(
                httpService = MockHttpClientCallbackServiceJS(
                    engine = mockEngine
                ),
                jwtService = MockJwtServiceJS()
            ))
        assertTrue(
            trustChainValidationService.validateTrustChains(
                listOfSubordinateStatementList.map { it.toTypedArray() }.toTypedArray(),
                listOf("https://openid.sunet-invalid.se", "https://openid.sunet-five.se").toTypedArray())
                .await().size == 1)
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun intermediateEntity(
    publicKey: Jwk,
    iss: String = "https://edugain.org/federation",
    sub: String = "https://edugain.org/federation"
): SubordinateStatement {

    return SubordinateStatement(
        iss = iss,
        sub = sub,
        iat = Date.now().toInt(),
        exp = Date(Date.now() + 3600).getSeconds(),
        sourceEndpoint = "https://edugain.org/federation/federation_fetch_endpoint",
        jwks = JsonObject(
            mapOf(
                "keys" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "kid" to JsonPrimitive(publicKey.kid),
                                "kty" to JsonPrimitive(publicKey.kty),
                                "crv" to JsonPrimitive(publicKey.crv),
                                "x" to JsonPrimitive(publicKey.x),
                                "y" to JsonPrimitive(publicKey.y),
                            )
                        )
                    )
                )
            )
        ),
        metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf(
                        "organization_name" to JsonPrimitive("SUNET")
                    )
                ),
                "openid_provider" to JsonObject(
                    mapOf(
                        "subject_types_supported" to JsonArray(listOf(JsonPrimitive("pairwise"))),
                        "token_endpoint_auth_methods_supported" to JsonArray(listOf(JsonPrimitive("private_key_jwt")))
                    )
                )
            )
        ),
        metadataPolicy = JsonObject(
            mapOf(
                "openid_provider" to JsonObject(
                    mapOf(
                        "subject_types_supported" to JsonObject(
                            mapOf(
                                "value" to JsonArray(listOf(JsonPrimitive("pairwise")))
                            )
                        ),
                        "token_endpoint_auth_methods_supported" to JsonObject(
                            mapOf(
                                "default" to JsonArray(listOf(JsonPrimitive("private_key_jwt"))),
                                "subset_of" to JsonArray(
                                    listOf(
                                        JsonPrimitive("private_key_jwt"),
                                        JsonPrimitive("client_secret_jwt")
                                    )
                                ),
                                "superset_of" to JsonArray(listOf(JsonPrimitive("private_key_jwt")))
                            )
                        )
                    )
                )
            )
        ),
    )
}

@OptIn(ExperimentalSerializationApi::class)
fun entityConfiguration(
    publicKey: Jwk,
    authorityHints: Array<String>? = arrayOf(),
    iss: String = "https://openid.sunet.se",
    sub: String = "https://openid.sunet.se",
    federationFetchEndpoint: String = "https://sunet.se/openid/fedapi",
): EntityConfigurationStatement {

    return EntityConfigurationStatement(
        iss = iss,
        sub = sub,
        iat = Date.now().toInt(),
        exp = Date(Date.now() + 3600).getSeconds(),
        metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf(
                        "federation_fetch_endpoint" to JsonPrimitive(federationFetchEndpoint),
                        "homepage_uri" to JsonPrimitive("https://www.sunet.se"),
                        "organization_name" to JsonPrimitive("SUNET")
                    )
                ),
                "openid_provider" to JsonObject(
                    mapOf(
                        "issuer" to JsonPrimitive("https://openid.sunet.se"),
                        "authorization_endpoint" to JsonPrimitive("https://openid.sunet.se/authorization"),
                        "grant_types_supported" to JsonArray(listOf(JsonPrimitive("authorization_code"))),
                        "id_token_signing_alg_values_supported" to JsonArray(
                            listOf(
                                JsonPrimitive("RS256"),
                                JsonPrimitive("ES256")
                            )
                        ),
                        "logo_uri" to JsonPrimitive("https://www.umu.se/img/umu-logo-left-neg.SE.svg"),
                        "op_policy_uri" to JsonPrimitive("op_policy_uri"),
                        "response_types_supported" to JsonArray(listOf(JsonPrimitive("code"))),
                        "subject_types_supported" to JsonArray(
                            listOf(
                                JsonPrimitive("pairwise"),
                                JsonPrimitive("public")
                            )
                        ),
                        "token_endpoint" to JsonPrimitive("https://openid.sunet.se/token"),
                        "token_endpoint_auth_methods_supported" to JsonArray(listOf(JsonPrimitive("private_key_jwt"))),
                        "jwks_uri" to JsonPrimitive("https://openid.sunet.se/jwks")
                    )
                )
            )
        ),
        jwks = JsonObject(
            mapOf(
                "keys" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "kid" to JsonPrimitive(publicKey.kid),
                                "kty" to JsonPrimitive(publicKey.kty),
                                "crv" to JsonPrimitive(publicKey.crv),
                                "x" to JsonPrimitive(publicKey.x),
                                "y" to JsonPrimitive(publicKey.y),
                            )
                        )
                    )
                )
            )
        ),
        authorityHints = authorityHints,
    )
}
