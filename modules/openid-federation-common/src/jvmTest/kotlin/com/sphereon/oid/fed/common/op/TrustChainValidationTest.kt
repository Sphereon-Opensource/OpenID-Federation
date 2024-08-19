package com.sphereon.oid.fed.common.op

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sphereon.oid.fed.openapi.models.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustChainValidationTest {

        companion object {

            // key pairs
            lateinit var partyBKeyPair: ECKey
            lateinit var intermediateEntityKeyPair: ECKey
            lateinit var intermediateEntity1KeyPair: ECKey
            lateinit var validTrustAnchorKeyPair: ECKey
            lateinit var unknownTrustAnchorKeyPair: ECKey
            lateinit var invalidTrustAnchorKeyPair: ECKey

            // configurations
            lateinit var partyBConfiguration: EntityConfigurationStatement
            lateinit var intermediateEntityConfiguration: EntityConfigurationStatement
            lateinit var intermediateEntity1Configuration: EntityConfigurationStatement
            lateinit var validTrustAnchorConfiguration: EntityConfigurationStatement
            lateinit var unknownTrustAnchorConfiguration: EntityConfigurationStatement
            lateinit var invalidTrustAnchorConfiguration: EntityConfigurationStatement

            // subordinate statements
            lateinit var partyBSubordinateStatement: SubordinateStatement
            lateinit var intermediateEntitySubordinateStatement: SubordinateStatement
            lateinit var intermediateEntity1SubordinateStatement: SubordinateStatement
            lateinit var validTrustAnchorSubordinateStatement: SubordinateStatement
            lateinit var unknownTrustAnchorSubordinateStatement: SubordinateStatement
            lateinit var invalidTrustAnchorSubordinateStatement: SubordinateStatement

            @JvmStatic
            @BeforeClass
            fun setup(): Unit {
                partyBKeyPair = ECKeyGenerator(Curve.P_256).generate()
                partyBConfiguration = entityConfiguration(
                    publicKey = partyBKeyPair.toPublicJWK(),
                    authorityHints = listOf("https://edugain.org/federation"),
                )
                partyBSubordinateStatement = intermediateEntity(
                    publicKey = partyBKeyPair.toPublicJWK()
                )

                intermediateEntityKeyPair = ECKeyGenerator(Curve.P_256).generate()
                intermediateEntityConfiguration = entityConfiguration(
                    publicKey = intermediateEntityKeyPair.toPublicJWK(),
                    authorityHints = listOf(
                        "https://edugain.org/federation_one",
                        "https://edugain.org/federation_two"
                    ),
                    iss = "https://openid.sunet.se",
                    sub = "https://openid.sunet.se"
                )

                //signed with intermediateEntity1 Private Key
                intermediateEntitySubordinateStatement = intermediateEntity(
                    publicKey = intermediateEntityKeyPair.toPublicJWK(),
                    iss = "https://openid.sunetone.se",
                    sub = "https://openid.sunet.se"
                )

                intermediateEntity1KeyPair = ECKeyGenerator(Curve.P_256).generate()
                intermediateEntity1Configuration = entityConfiguration(
                    publicKey = intermediateEntity1KeyPair.toPublicJWK(),
                    authorityHints = listOf("https://edugain.org/federation_three"),
                    iss = "https://openid.sunetone.se",
                    sub = "https://openid.sunetone.se"
                )
                intermediateEntity1SubordinateStatement = intermediateEntity(
                    publicKey = intermediateEntity1KeyPair.toPublicJWK(),
                    iss = "https://openid.sunettwo.se",
                    sub = "https://openid.sunetone.se"
                )

                validTrustAnchorKeyPair = ECKeyGenerator(Curve.P_256).generate()
                validTrustAnchorConfiguration = entityConfiguration(
                    publicKey = validTrustAnchorKeyPair.toPublicJWK(),
                    authorityHints = listOf(),
                    iss = "https://openid.sunetthree.se",
                    sub = "https://openid.sunettwo.se"
                )
                validTrustAnchorSubordinateStatement = intermediateEntity(
                    publicKey = validTrustAnchorKeyPair.toPublicJWK(),
                    iss = "https://openid.sunetthree.se",
                    sub = "https://openid.sunetthree.se"
                )

                unknownTrustAnchorKeyPair = ECKeyGenerator(Curve.P_256).generate()
                unknownTrustAnchorConfiguration = entityConfiguration(
                    publicKey = unknownTrustAnchorKeyPair.toPublicJWK(),
                    authorityHints = listOf(),
                    iss = "https://openid.sunetfour.se", // Should match the id of the trust anchor
                    sub = "https://openid.sunetone.se"
                )
                unknownTrustAnchorSubordinateStatement = intermediateEntity(
                    publicKey = unknownTrustAnchorKeyPair.toPublicJWK(),
                    iss = "https://openid.sunetfour.se",
                    sub = "https://openid.sunetfour.se"
                )

                invalidTrustAnchorKeyPair = ECKeyGenerator(Curve.P_256).generate()
                invalidTrustAnchorConfiguration = entityConfiguration(
                    publicKey = invalidTrustAnchorKeyPair.toPublicJWK(),
                    authorityHints = listOf(),
                    iss = "https://openid.sunetfive.se", // Should match the id of the trust anchor
                    sub = "https://openid.sunetfour.se"
                )
                invalidTrustAnchorSubordinateStatement = intermediateEntity(
                    publicKey = invalidTrustAnchorKeyPair.toPublicJWK(),
                    iss = "https://openid.sunetfive.se",
                    sub = "https://openid.sunetfive.se"
                )
            }
        }

    private val mockEngine = MockEngine { request ->
        when(request.url) {
            Url("https://edugain.org/federation") -> respond(
                content = "<partyB entity configuration jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation/federation_fetch_endpoint") -> respond(
                content = "<Entity Configuration of trust chain subject jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation_one") -> respond(
                content = "<Intermediate Entity Configuration Statement jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation_one/federation_fetch_endpoint") -> respond(
                content = "<Intermediate Entity Statement jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation_two") -> respond(
                content = "<Intermediate Entity Configuration Statement 1 jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation_two/federation_fetch_endpoint") -> respond(
                content = "<Intermediate Entity Statement 1 jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation_three") -> respond(
                content = "<Intermediate Entity Configuration Statement 2 jwt>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            Url("https://edugain.org/federation_three/federation_fetch_endpoint") -> respond(
                content = "Intermediate Entity Configuration 2 jwt",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
            else -> error("Unhandled ${request.url}")
        }
    }

    @Test
    fun readAuthorityHintsTest() {

        //three trust anchors (invalid, unknown and valid)
        // configuration |- invalid trust anchor
        //               |- intermediate |- unknown trust anchor
        //                               | - intermediate1 |- valid trust anchor

        val listOfEntityConfigurationStatementList = listOf(
            listOf(partyBConfiguration, intermediateEntitySubordinateStatement, intermediateEntity1SubordinateStatement, validTrustAnchorSubordinateStatement),
        )
        assertEquals(listOfEntityConfigurationStatementList, readAuthorityHints(jwt = "<partyB entity configuration jwt>", engine = mockEngine))
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
        iat = LocalDateTime.now().second,
        exp = LocalDateTime.now().plusHours(1).second,
       sourceEndpoint = "https://edugain.org/federation/federation_fetch_endpoint",
        jwks = JWKS(
            propertyKeys = listOf(
                JwkDTO(
                    kid = publicKey.keyID,
                    kty = publicKey.keyType.value,
                    crv = publicKey.curve.name,
                    x = publicKey.x.toString(),
                    y = publicKey.y.toString(),
                )
            )
        ),
        metadata = Metadata(
            federationEntity = FederationEntityMetadata(
                organizationName = "SUNET"
            )
        ),
        metadataPolicy = MetadataPolicy(
            openidProvider = MetadataParameterPolicy(
                additionalProperties = JsonObject(mapOf(
                    "subject_types_supported" to JsonObject(
                        mapOf(
                            "value" to JsonArray(listOf(JsonPrimitive("pairwise")))
                        )
                    ),
                    "token_endpoint_auth_methods_supported" to JsonObject(
                        mapOf(
                            "default" to JsonArray(listOf(JsonPrimitive("private_key_jwt"))),
                            "subset_of" to JsonArray(listOf(
                                JsonPrimitive("private_key_jwt"),
                                JsonPrimitive("client_secret_jwt")
                            )),
                            "superset_of" to JsonArray(listOf(JsonPrimitive("private_key_jwt")))
                        )
                    )
                ))
            )
        )
    )
}

fun entityConfiguration(
    publicKey: ECKey,
    authorityHints: List<String> = listOf(),
    iss: String = "https://openid.sunet.se",
    sub: String = "https://openid.sunet.se"): EntityConfigurationStatement {

   return EntityConfigurationStatement(
       iss = iss,
       sub = sub,
       iat = LocalDateTime.now().second,
       exp = LocalDateTime.now().plusHours(1).second,
       metadata = Metadata(
           federationEntity = FederationEntityMetadata(
               federationFetchEndpoint = "https://sunet.se/openid/fedapi",
               homepageUri = "https://www.sunet.se",
               organizationName = "SUNET"
           ),
           openidProvider = OpenIDProviderMetadata(
               issuer = "https://openid.sunet.se",
               authorizationEndpoint = "https://openid.sunet.se/authorization",
               grantTypesSupported = listOf("authorization_code"),
               idTokenSigningAlgValuesSupported = listOf("RS256",  "ES256"),
               logoUri = "https://www.umu.se/img/umu-logo-left-neg.SE.svg",
               opPolicyUri = "op_policy_uri",
               responseTypesSupported = listOf("code"),
               subjectTypesSupported = listOf("pairwise", "public"),
               tokenEndpoint = "https://openid.sunet.se/token",
               tokenEndpointAuthMethodsSupported = listOf("private_key_jwt"),
               jwksUri = "https://openid.sunet.se/jwks"
           ),
       ),
       jwks = JWKS(
           propertyKeys = listOf(
               JwkDTO(
                   kid = publicKey.keyID,
                   kty = publicKey.keyType.value,
                   crv = publicKey.curve.name,
                   x = publicKey.x.toString(),
                   y = publicKey.y.toString(),
               )
           )
       ),
       authorityHints = authorityHints,
   )
}
