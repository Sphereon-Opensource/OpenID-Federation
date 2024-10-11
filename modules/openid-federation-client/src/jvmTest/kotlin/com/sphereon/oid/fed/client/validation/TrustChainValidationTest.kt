package com.sphereon.oid.fed.client.validation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.client.OidFederationClientService.TRUST_CHAIN_VALIDATION
import com.sphereon.oid.fed.common.jwt.IJwtService
import com.sphereon.oid.fed.common.jwt.JwtSignInput
import com.sphereon.oid.fed.common.jwt.JwtVerifyInput
import com.sphereon.oid.fed.openapi.models.*
import io.ktor.client.engine.mock.*
import io.ktor.client.engine.mock.MockEngine.Companion.invoke
import io.ktor.http.*
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.BeforeClass
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class JwtServiceImpl : IJwtService {
    override suspend fun sign(input: JwtSignInput): String {
        val jwkJsonString = Json.encodeToString(input.key)
        val ecJWK = ECKey.parse(jwkJsonString)
        val signer: JWSSigner = ECDSASigner(ecJWK)
        val jwsHeader = input.header.toJWSHeader()

        val signedJWT = SignedJWT(
            jwsHeader, JWTClaimsSet.parse(JsonObject(input.payload).toString())
        )

        signedJWT.sign(signer)
        return signedJWT.serialize()
    }

    override suspend fun verify(input: JwtVerifyInput): Boolean {
        try {
            val jwkJsonString = Json.encodeToString(input.key)
            val ecKey = ECKey.parse(jwkJsonString)
            val verifier: JWSVerifier = ECDSAVerifier(ecKey)
            val signedJWT = SignedJWT.parse(input.jwt)
            val verified = signedJWT.verify(verifier)
            return verified
        } catch (e: Exception) {
            throw Exception("Couldn't verify the JWT Signature: ${e.message}", e)
        }
    }

    private fun JWTHeader.toJWSHeader(): JWSHeader {
        val type = typ
        return JWSHeader.Builder(JWSAlgorithm.parse(alg)).apply {
            type(JOSEObjectType(type))
            keyID(kid)
        }.build()
    }
}

class TrustChainValidationTest {

    companion object {

        val jwtService = JwtServiceImpl()

        // key pairs
        val partyBKeyPair = ECKeyGenerator(Curve.P_256).generate()
        val intermediateEntityKeyPair = ECKeyGenerator(Curve.P_256).generate()
        val intermediateEntity1KeyPair = ECKeyGenerator(Curve.P_256).generate()
        val validTrustAnchorKeyPair = ECKeyGenerator(Curve.P_256).generate()
        val unknownTrustAnchorKeyPair = ECKeyGenerator(Curve.P_256).generate()
        val invalidTrustAnchorKeyPair = ECKeyGenerator(Curve.P_256).generate()

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

        val partyBJwk = Jwk(
            kty = partyBKeyPair.keyType.value,
            crv = partyBKeyPair.curve.name,
            kid = partyBKeyPair.keyID,
            x = partyBKeyPair.x.toString(),
            y = partyBKeyPair.y.toString(),
            alg = partyBKeyPair.algorithm?.name ?: "ES256",
            use = partyBKeyPair.keyUse?.value ?: "sign",
            d = partyBKeyPair.d.toString(),
            dp = partyBKeyPair.requiredParams.toString()
        )

        val intermediateEntityConfigurationJwk = Jwk(
            kty = intermediateEntityKeyPair.keyType.value,
            crv = intermediateEntityKeyPair.curve.name,
            kid = intermediateEntityKeyPair.keyID,
            x = intermediateEntityKeyPair.x.toString(),
            y = intermediateEntityKeyPair.y.toString(),
            alg = intermediateEntityKeyPair.algorithm?.name ?: "ES256",
            use = intermediateEntityKeyPair.keyUse?.value ?: "sign",
            d = intermediateEntityKeyPair.d.toString(),
            dp = intermediateEntityKeyPair.requiredParams.toString()
        )

        val intermediateEntityConfiguration1Jwk = Jwk(
            kty = intermediateEntity1KeyPair.keyType.value,
            crv = intermediateEntity1KeyPair.curve.name,
            kid = intermediateEntity1KeyPair.keyID,
            x = intermediateEntity1KeyPair.x.toString(),
            y = intermediateEntity1KeyPair.y.toString(),
            alg = intermediateEntity1KeyPair.algorithm?.name ?: "ES256",
            use = intermediateEntity1KeyPair.keyUse?.value ?: "sign",
            d = intermediateEntity1KeyPair.d.toString(),
            dp = intermediateEntity1KeyPair.requiredParams.toString()
        )

        val validTrustAnchorConfigurationJwk = Jwk(
            kty = validTrustAnchorKeyPair.keyType.value,
            crv = validTrustAnchorKeyPair.curve.name,
            kid = validTrustAnchorKeyPair.keyID,
            x = validTrustAnchorKeyPair.x.toString(),
            y = validTrustAnchorKeyPair.y.toString(),
            alg = validTrustAnchorKeyPair.algorithm?.name ?: "ES256",
            use = validTrustAnchorKeyPair.keyUse?.value ?: "sign",
            d = validTrustAnchorKeyPair.d.toString(),
            dp = validTrustAnchorKeyPair.requiredParams.toString()
        )

        val unknownTrustAnchorConfigurationJwk = Jwk(
            kty = unknownTrustAnchorKeyPair.keyType.value,
            crv = unknownTrustAnchorKeyPair.curve.name,
            kid = unknownTrustAnchorKeyPair.keyID,
            x = unknownTrustAnchorKeyPair.x.toString(),
            y = unknownTrustAnchorKeyPair.y.toString(),
            alg = unknownTrustAnchorKeyPair.algorithm?.name ?: "ES256",
            use = unknownTrustAnchorKeyPair.keyUse?.value ?: "sign",
            d = unknownTrustAnchorKeyPair.d.toString(),
            dp = unknownTrustAnchorKeyPair.requiredParams.toString()
        )

        val invalidTrustAnchorConfigurationJwk = Jwk(
            kty = invalidTrustAnchorKeyPair.keyType.value,
            crv = invalidTrustAnchorKeyPair.curve.name,
            kid = invalidTrustAnchorKeyPair.keyID,
            x = invalidTrustAnchorKeyPair.x.toString(),
            y = invalidTrustAnchorKeyPair.y.toString(),
            alg = invalidTrustAnchorKeyPair.algorithm?.name ?: "ES256",
            use = invalidTrustAnchorKeyPair.keyUse?.value ?: "sign",
            d = invalidTrustAnchorKeyPair.d.toString(),
            dp = invalidTrustAnchorKeyPair.requiredParams.toString()
        )

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

        @JvmStatic
        @BeforeClass
        fun setup() {

            // Party B Entity Configuration (federation)
            partyBConfiguration = entityConfiguration(
                publicKey = partyBKeyPair.toPublicJWK(),
                authorityHints = arrayOf(
                    "https://edugain.org/federation_one",
                    "https://edugain.org/federation_two"
                ),
                iss = "https://openid.sunet.se",
                sub = "https://openid.sunet.se",
                federationFetchEndpoint = "https://edugain.org/federation/federation_fetch_endpoint"
            )

            partyBJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = EntityConfigurationStatement.serializer(),
                        partyBConfiguration
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = partyBKeyPair.keyID
                    ),
                    key = partyBJwk
                )
            ) }

            // Federation 2
            intermediateEntityConfiguration = entityConfiguration(
                publicKey = intermediateEntityKeyPair.toPublicJWK(),
                authorityHints = arrayOf(
                    "https://edugain.org/federation_three",
                    "https://edugain.org/federation_four"
                ),
                iss = "https://openid.sunet-one.se",
                sub = "https://openid.sunet.se",
                federationFetchEndpoint = "https://edugain.org/federation_two/federation_fetch_endpoint"
            )

            intermediateEntityConfigurationJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = EntityConfigurationStatement.serializer(),
                        intermediateEntityConfiguration
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = intermediateEntityKeyPair.keyID
                    ),
                    key = intermediateEntityConfiguration1Jwk
                )
            ) }

            //signed with intermediateEntity1 Private Key
            intermediateEntitySubordinateStatement = intermediateEntity(
                publicKey = intermediateEntityKeyPair.toPublicJWK(),
                iss = "https://openid.sunet-one.se",
                sub = "https://openid.sunet.se",
            )

            intermediateEntitySubordinateStatementJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = SubordinateStatement.serializer(),
                        intermediateEntitySubordinateStatement
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = intermediateEntityKeyPair.keyID
                    ),
                    key = intermediateEntityConfiguration1Jwk
                )
            ) }

            // Federation 4
            intermediateEntityConfiguration1 = entityConfiguration(
                publicKey = intermediateEntity1KeyPair.toPublicJWK(),
                authorityHints = arrayOf("https://edugain.org/federation_five"),
                iss = "https://openid.sunet-two.se",
                sub = "https://openid.sunet-one.se",
                federationFetchEndpoint = "https://edugain.org/federation_four/federation_fetch_endpoint"
            )

            intermediateEntityConfiguration1Jwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = EntityConfigurationStatement.serializer(),
                        intermediateEntityConfiguration1
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = intermediateEntity1KeyPair.keyID
                    ),
                    key = validTrustAnchorConfigurationJwk
                )
            ) }

            intermediateEntity1SubordinateStatement = intermediateEntity(
                publicKey = intermediateEntity1KeyPair.toPublicJWK(),
                iss = "https://openid.sunet-two.se",
                sub = "https://openid.sunet-one.se"
            )

            intermediateEntity1SubordinateStatementJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = SubordinateStatement.serializer(),
                        intermediateEntity1SubordinateStatement
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = intermediateEntity1KeyPair.keyID
                    ),
                    key = validTrustAnchorConfigurationJwk
                )
            ) }

            // Federation 5
            validTrustAnchorConfiguration = entityConfiguration(
                publicKey = validTrustAnchorKeyPair.toPublicJWK(),
                authorityHints = arrayOf(),
                iss = "https://openid.sunet-five.se",
                sub = "https://openid.sunet-five.se",
                federationFetchEndpoint = "https://edugain.org/federation_five/federation_fetch_endpoint"
            )

            validTrustAnchorConfigurationJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = EntityConfigurationStatement.serializer(),
                        validTrustAnchorConfiguration
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = validTrustAnchorKeyPair.keyID
                    ),
                    key = validTrustAnchorConfigurationJwk
                )
            ) }

            // Federation 3
            unknownTrustAnchorConfiguration = entityConfiguration(
                publicKey = unknownTrustAnchorKeyPair.toPublicJWK(),
                authorityHints = arrayOf(),
                iss = "https://openid.sunet-three.se",
                sub = "https://openid.sunet-three.se",
                federationFetchEndpoint = "https://edugain.org/federation_three/federation_fetch_endpoint"
            )

            unknownTrustAnchorConfigurationJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = EntityConfigurationStatement.serializer(),
                        unknownTrustAnchorConfiguration
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = unknownTrustAnchorKeyPair.keyID
                    ),
                    key = unknownTrustAnchorConfigurationJwk
                )
            ) }

            // Federation 1
            invalidTrustAnchorConfiguration = entityConfiguration(
                publicKey = invalidTrustAnchorKeyPair.toPublicJWK(),
                authorityHints = arrayOf(),
                iss = "https://openid.sunet-invalid.se",
                sub = "https://openid.sunet-invalid.se",
                federationFetchEndpoint = "https://edugain.org/federation_one/federation_fetch_endpoint"
            )

            invalidTrustAnchorConfigurationJwt = runBlocking { jwtService.sign(
                JwtSignInput(
                    payload = Json.encodeToJsonElement(
                        serializer = EntityConfigurationStatement.serializer(),
                        invalidTrustAnchorConfiguration
                    ).jsonObject,
                    header = JWTHeader(
                        alg = "ES256",
                        typ = "entity-statement+jwt",
                        kid = invalidTrustAnchorKeyPair.keyID
                    ),
                    key = invalidTrustAnchorConfigurationJwk
                )
            ) }

            listOfEntityConfigurationStatementList = mutableListOf(
                mutableListOf(
                    partyBConfiguration, invalidTrustAnchorConfiguration
                ),
                mutableListOf(
                    partyBConfiguration, intermediateEntityConfiguration, unknownTrustAnchorConfiguration
                ),
                mutableListOf(
                    partyBConfiguration,
                    intermediateEntityConfiguration,
                    intermediateEntityConfiguration1,
                    validTrustAnchorConfiguration
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
                    partyBJwt,
                    intermediateEntitySubordinateStatementJwt,
                    intermediateEntity1SubordinateStatementJwt,
                    validTrustAnchorConfigurationJwt
                )
            )
        }

        val mockEngine = MockEngine { request ->
            when (request.url) {
                Url("https://edugain.org/federation") -> respond(
                    content = partyBJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                // Entity Configuration - sub and key binding
                Url("https://edugain.org/federation/federation_fetch_endpoint") -> respond(
                    content = partyBJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                Url("https://edugain.org/federation_one") -> respond(
                    content = invalidTrustAnchorConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                // Entity Configuration - Trust Anchor
                Url("https://edugain.org/federation_one/federation_fetch_endpoint") -> respond(
                    content = invalidTrustAnchorConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                Url("https://edugain.org/federation_two") -> respond(
                    content = intermediateEntityConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                // Subordinate Statement - sub and key binding
                Url("https://edugain.org/federation_two/federation_fetch_endpoint") -> respond(
                    content = intermediateEntitySubordinateStatementJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                Url("https://edugain.org/federation_three") -> respond(
                    content = unknownTrustAnchorConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                // Entity Configuration - Trust Anchor
                Url("https://edugain.org/federation_three/federation_fetch_endpoint") -> respond(
                    content = unknownTrustAnchorConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                Url("https://edugain.org/federation_four") -> respond(
                    content = intermediateEntityConfiguration1Jwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                // Subordinate Statement
                Url("https://edugain.org/federation_four/federation_fetch_endpoint") -> respond(
                    content = intermediateEntity1SubordinateStatementJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                Url("https://edugain.org/federation_five") -> respond(
                    content = validTrustAnchorConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                // Entity Configuration - Trust Chain
                Url("https://edugain.org/federation_five/federation_fetch_endpoint") -> respond(
                    content = validTrustAnchorConfigurationJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )

                else -> error("Unhandled ${request.url}")
            }
        }
    }


    @Test
    fun readAuthorityHintsTest() = runTest {
        val trustChainValidationService = trustChainValidationService().register(
            MockTrustChainValidationCallback(
                jwtService = jwtService,
                httpService = MockHttpClientCallbackService(
                    engine = mockEngine
                )
            ))
        assertEquals(
            listOfEntityConfigurationStatementList.toString(),
            trustChainValidationService.readAuthorityHints(
                partyBId = "https://edugain.org/federation"
            ).toString()
        )
    }

    @Test
    fun fetchSubordinateStatementsTest() = runTest {
        val trustChainValidationService = trustChainValidationService().register(
            MockTrustChainValidationCallback(
                jwtService = jwtService,
                httpService = MockHttpClientCallbackService(
                    engine = mockEngine
                )
            ))
        assertEquals(
            listOfSubordinateStatementList,
            trustChainValidationService.fetchSubordinateStatements(
                entityConfigurationStatementsList = listOfEntityConfigurationStatementList
            )
        )
    }

    @Test
    fun validateTrustChainTest() = runTest {
        val trustChainValidationService = trustChainValidationService().register(
            MockTrustChainValidationCallback(
                jwtService = jwtService,
                httpService = MockHttpClientCallbackService(
                    engine = mockEngine
                )
            ))
        assertTrue(
            trustChainValidationService.validateTrustChains(
                listOfSubordinateStatementList,
                listOf("https://openid.sunet-invalid.se", "https://openid.sunet-five.se")
            ).size == 1
        )
    }
}

fun intermediateEntity(
    publicKey: ECKey,
    iss: String = "https://edugain.org/federation",
    sub: String = "https://edugain.org/federation"
): SubordinateStatement {

    return SubordinateStatement(
        iss = iss,
        sub = sub,
        iat = OffsetDateTime.now().toEpochSecond().toInt(),
        exp = OffsetDateTime.now().plusHours(1).toEpochSecond().toInt(),
        sourceEndpoint = "https://edugain.org/federation/federation_fetch_endpoint",
        jwks = JsonObject(
            mapOf(
                "keys" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "kid" to JsonPrimitive(publicKey.keyID),
                                "kty" to JsonPrimitive(publicKey.keyType.value),
                                "crv" to JsonPrimitive(publicKey.curve.name),
                                "x" to JsonPrimitive(publicKey.x.toString()),
                                "y" to JsonPrimitive(publicKey.y.toString()),
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

fun entityConfiguration(
    publicKey: ECKey,
    authorityHints: Array<String>? = arrayOf(),
    iss: String = "https://openid.sunet.se",
    sub: String = "https://openid.sunet.se",
    federationFetchEndpoint: String = "https://sunet.se/openid/fedapi",
): EntityConfigurationStatement {

    return EntityConfigurationStatement(
        iss = iss,
        sub = sub,
        iat = OffsetDateTime.now().toEpochSecond().toInt(),
        exp = OffsetDateTime.now().plusHours(1).toEpochSecond().toInt(),
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
                                "kid" to JsonPrimitive(publicKey.keyID),
                                "kty" to JsonPrimitive(publicKey.keyType.value),
                                "crv" to JsonPrimitive(publicKey.curve.name),
                                "x" to JsonPrimitive(publicKey.x.toString()),
                                "y" to JsonPrimitive(publicKey.y.toString()),
                            )
                        )
                    )
                )
            )
        ),
        authorityHints = authorityHints,
    )
}
