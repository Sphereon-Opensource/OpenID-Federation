package com.sphereon.oid.fed.client.validation

import com.sphereon.oid.fed.common.jwt.JwtService
import com.sphereon.oid.fed.common.jwt.JwtSignInput
import com.sphereon.oid.fed.common.jwt.JwtVerifyInput
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
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.js.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@JsModule("jose")
@JsNonModule
external object Jose {
    class SignJWT {
        constructor(payload: dynamic) {
            definedExternally
        }

        fun setProtectedHeader(protectedHeader: dynamic): SignJWT {
            definedExternally
        }

        fun sign(key: Any?, signOptions: Any?): String {
            definedExternally
        }
    }

    fun generateKeyPair(alg: String, options: dynamic = definedExternally): dynamic
    fun jwtVerify(jwt: String, key: Any, options: dynamic = definedExternally): dynamic
    fun exportJWK(key: dynamic): dynamic
    fun importJWK(jwk: dynamic, alg: String, options: dynamic = definedExternally): dynamic
}

fun convertToJwk(keyPair: dynamic): Jwk {
    val privateJWK = Jose.exportJWK(keyPair.privateKey)
    val publicJWK = Jose.exportJWK(keyPair.publicKey)
    return Jwk(
        crv = privateJWK.crv,
        d = privateJWK.d,
        kty = privateJWK.kty,
        x = privateJWK.x,
        y = privateJWK.y,
        alg = publicJWK.alg,
        kid = publicJWK.kid,
        use = publicJWK.use,
        x5c = publicJWK.x5c,
        x5t = publicJWK.x5t,
        x5tS256 = privateJWK.x5tS256,
        x5u = publicJWK.x5u,
        dp = privateJWK.dp,
        dq = privateJWK.dq,
        e = privateJWK.e,
        n = privateJWK.n,
        p = privateJWK.p,
        q = privateJWK.q,
        qi = privateJWK.qi
    )
}

class JwtServiceImpl: JwtService {
    override fun sign(input: JwtSignInput): String {
        return Jose.SignJWT(JSON.parse<Any>(Json.encodeToString(input.payload)))
            .setProtectedHeader(JSON.parse<Any>(Json.encodeToString(input.header)))
            .sign(key = input.key, null)
    }

    override fun verify(input: JwtVerifyInput): Boolean {
        val publicKey = Jose.importJWK(input.key, alg = input.key.alg ?: "RS256")
        return Jose.jwtVerify(input.jwt, publicKey)
    }

}


class TrustChainValidationTest {

    val jwtServiceImpl = JwtServiceImpl()

    // key pairs
    @OptIn(ExperimentalJsExport::class)
    val partyBKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
    @OptIn(ExperimentalJsExport::class)
    val intermediateEntityKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
    @OptIn(ExperimentalJsExport::class)
    val intermediateEntity1KeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
    @OptIn(ExperimentalJsExport::class)
    val validTrustAnchorKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
    @OptIn(ExperimentalJsExport::class)
    val unknownTrustAnchorKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))
    @OptIn(ExperimentalJsExport::class)
    val invalidTrustAnchorKeyPair = Jose.generateKeyPair("PS256", JsonObject(mapOf("extractable" to JsonPrimitive(true))))

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
    val partyBJwk = convertToJwk(partyBKeyPair)

    @OptIn(ExperimentalJsExport::class)
    val intermediateEntityConfigurationJwk = convertToJwk(intermediateEntityKeyPair)

    @OptIn(ExperimentalJsExport::class)
    val intermediateEntityConfiguration1Jwk = convertToJwk(intermediateEntity1KeyPair)

    @OptIn(ExperimentalJsExport::class)
    val validTrustAnchorConfigurationJwk = convertToJwk(validTrustAnchorKeyPair)

    @OptIn(ExperimentalJsExport::class)
    val unknownTrustAnchorConfigurationJwk = convertToJwk(unknownTrustAnchorKeyPair)

    @OptIn(ExperimentalJsExport::class)
    val invalidTrustAnchorConfigurationJwk = convertToJwk(invalidTrustAnchorKeyPair)

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

    @OptIn(ExperimentalJsExport::class)
    @BeforeTest
    fun setup() {

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
        )

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

        intermediateEntityConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
            payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), intermediateEntityConfiguration).jsonObject,
            header = JWTHeader(
                alg = "ES256",
                typ = "entity-statement+jwt",
                kid = intermediateEntityConfigurationJwk.kid
            ),
            key = intermediateEntityConfiguration1Jwk
        )
        )

        //signed with intermediateEntity1 Private Key
        intermediateEntitySubordinateStatement = intermediateEntity(
            publicKey = intermediateEntityConfigurationJwk,
            iss = "https://openid.sunet-one.se",
            sub = "https://openid.sunet.se",
        )

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
        )

        // Federation 4
        intermediateEntityConfiguration1 = entityConfiguration(
            publicKey = intermediateEntityConfiguration1Jwk,
            authorityHints = arrayOf("https://edugain.org/federation_five"),
            iss = "https://openid.sunet-two.se",
            sub = "https://openid.sunet-one.se",
            federationFetchEndpoint = "https://edugain.org/federation_four/federation_fetch_endpoint"
        )

        intermediateEntityConfiguration1Jwt = jwtServiceImpl.sign(
            JwtSignInput(
            payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), intermediateEntityConfiguration1).jsonObject,
            header = JWTHeader(
                alg = "ES256",
                typ = "entity-statement+jwt",
                kid = intermediateEntityConfiguration1Jwk.kid
            ),
            key = validTrustAnchorConfigurationJwk
        )
        )

        intermediateEntity1SubordinateStatement = intermediateEntity(
            publicKey = intermediateEntityConfiguration1Jwk,
            iss = "https://openid.sunet-two.se",
            sub = "https://openid.sunet-one.se"
        )

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
        )

        // Federation 5
        validTrustAnchorConfiguration = entityConfiguration(
            publicKey = validTrustAnchorConfigurationJwk,
            authorityHints = arrayOf(),
            iss = "https://openid.sunet-five.se",
            sub = "https://openid.sunet-five.se",
            federationFetchEndpoint = "https://edugain.org/federation_five/federation_fetch_endpoint"
        )

        validTrustAnchorConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
            payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), validTrustAnchorConfiguration).jsonObject,
            header = JWTHeader(
                alg = "ES256",
                typ = "entity-statement+jwt",
                kid = validTrustAnchorConfigurationJwk.kid
            ),
            key = validTrustAnchorConfigurationJwk
        )
        )

        // Federation 3
        unknownTrustAnchorConfiguration = entityConfiguration(
            publicKey = unknownTrustAnchorConfigurationJwk,
            authorityHints = arrayOf(),
            iss = "https://openid.sunet-three.se",
            sub = "https://openid.sunet-three.se",
            federationFetchEndpoint = "https://edugain.org/federation_three/federation_fetch_endpoint"
        )

        unknownTrustAnchorConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
            payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), unknownTrustAnchorConfiguration).jsonObject,
            header = JWTHeader(
                alg = "ES256",
                typ = "entity-statement+jwt",
                kid = unknownTrustAnchorConfigurationJwk.kid
            ),
            key = unknownTrustAnchorConfigurationJwk
        )
        )

        // Federation 1
        invalidTrustAnchorConfiguration = entityConfiguration(
            publicKey = invalidTrustAnchorConfigurationJwk,
            authorityHints = arrayOf(),
            iss = "https://openid.sunet-invalid.se",
            sub = "https://openid.sunet-invalid.se",
            federationFetchEndpoint = "https://edugain.org/federation_one/federation_fetch_endpoint"
        )

        invalidTrustAnchorConfigurationJwt = jwtServiceImpl.sign(
            JwtSignInput(
            payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), invalidTrustAnchorConfiguration).jsonObject,
            header = JWTHeader(
                alg = "ES256",
                typ = "entity-statement+jwt",
                kid = invalidTrustAnchorConfigurationJwk.kid
            ),
            key = invalidTrustAnchorConfigurationJwk
        )
        )

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

    @Test
    fun readAuthorityHintsTest() = runTest {
        assertEquals(
            listOfEntityConfigurationStatementList,
            TrustChainValidation(jwtServiceImpl).readAuthorityHints(
                partyBId = "https://edugain.org/federation",
                engine = mockEngine,
                trustChains = mutableListOf(mutableListOf<EntityConfigurationStatement>()),
                trustChain = mutableListOf<EntityConfigurationStatement>(),
            ).await()
        )
    }

    @Test
    fun fetchSubordinateStatementsTest() = runTest {
        assertEquals(
            listOfSubordinateStatementList,
            TrustChainValidation(jwtServiceImpl).fetchSubordinateStatements(
                entityConfigurationStatementsList = listOfEntityConfigurationStatementList,
                engine = mockEngine
            ).await()
        )
    }

    @Test
    fun validateTrustChainTest() = runTest {
        assertTrue(
            TrustChainValidation(jwtServiceImpl).validateTrustChains(listOfSubordinateStatementList, listOf("https://openid.sunet-invalid.se", "https://openid.sunet-five.se")).await().size == 1
        )
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