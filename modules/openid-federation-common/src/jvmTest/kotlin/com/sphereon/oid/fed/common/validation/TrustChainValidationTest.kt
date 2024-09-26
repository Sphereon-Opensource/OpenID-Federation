package com.sphereon.oid.fed.common.validation

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sphereon.oid.fed.kms.local.jwt.sign
import com.sphereon.oid.fed.openapi.models.*
import io.ktor.client.engine.mock.*
import io.ktor.client.engine.mock.MockEngine.Companion.invoke
import io.ktor.http.*
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.BeforeClass
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustChainValidationTest {

    companion object {

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
        lateinit var validTrustAnchorSubordinateStatement: SubordinateStatement
        lateinit var unknownTrustAnchorSubordinateStatement: SubordinateStatement
        lateinit var invalidTrustAnchorSubordinateStatement: SubordinateStatement

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

        lateinit var listOfEntityConfigurationStatementList: MutableList<MutableList<EntityConfigurationStatement>>

        val listOfSubordinateStatementList: MutableList<MutableList<String>> = mutableListOf(
            mutableListOf(
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb24vZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCJ9.psisFesGRDx_-iBEfb_sb6ydpKh9ih5vWTTmmYk4i1ZYZOgLxodrSkRWcDq4agRadcTj8XyZiDd3CYQtQIH8N1fPgrvMJTAGOQxpYG31GN0gDaWmFvpTMEynPY4NxFh5oP9oR7VjYRe_hxLcI_fFhO0GHyPDaHmPXi2jvPQ9Wg-bYOMkHmf9YbbQ30GSHZWhd-Kg3xsbmEqGg91Jj70UYwPQT3h9tI7-OELExU6WSQLT6HSVQTuMdhIoLp_f7ELkeUdO3YLxQyZ8h5QU1lTHrycAIQ1g-9NkyCOJQWrzWojxwP30yLWvaPPfwjz01jHfymIjtE0fOlRqZ-xMrJFzpQ",
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fb25lL2ZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQifQ.elvdpFKwSmXO878CeE3iK2LAmlPV9_iBcuDM3JL7_zJ9UWay0K-QCrlwbqWNxkH-d0DrnKh1KHZGarQ1u9OB0Q2CFCplkbdNiQc7qUpSk1aHoaz30O9p7o4qKtNjz8AV3gE0xKFLnkj6M7rJZ3POC8AONelC1eaGe4AfgNzd7JgWd_lQGV2esNUoq5x9udjXrX1HW--PAC6vue-F-Tcx_TU2Dff3qOkW-SJvbvGnxVdvirutsJoXdae9yGMQLoi3Araefr1Tfjvd1MSxhlUL80eASrT3XgHZEeajN8ijeYUuaY36K_gCxzxkrgypXZyfZptSIGDctOuj8GucZNz9FA",
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdHdvL2ZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQifQ.eJCajk2u8NMpq2Qi8tUUW4yT3t8zN5efdMz95Qg2Edc9-xXp32V6POp2Zmj3M99TyUrp9YwK9TEesH_7oz5QXYFR4J5cNkvd4mJvpDudvgayvlW_3z4nLUWru8nCfSyH_PiSwbPdhpiUZGvf5-KZHMdQ0SAbp5GSMSf4CD8fIYEV8u_KyS38g9Zgsv6BoQzCZtEPtY_cLCG9YV1S7V3tLsW5-bhf8da8mEny1cdnSLI6YJanQxmpW9Aq0ooxntIo1cokeL2fFoUvzNw-4JhYLOgqVDSNg3lhNhsbiPxHI3yN04Qcn90h0s1--QFHaf8rgMWyLyKZPId4kxPR-4PZoQ",
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdGhyZWUvZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCJ9.ZnPDr-p8_RF9vI_DUqEuld-iJSPTMC5IL_B6rp_7mK1L4F_TXg0WJBP7uyn1dpkuS-Cd0V5oqHjtsxJgCoToqXGc2qvewktHOPEVKmIEVm25ALMVfiU0HZE1fU78crpF7xEo4UVBMHq78_Kk1QUg-SPmjGtS3IJ1e-EW8kxdnUVXvSsqP1pPh1iXVIjUHlQsh0SbfIbmLDmu5xYjlXXzad56zJv1G0Jov8gWw8wPYOwWY2j06MwQghu_N-ViyeFDa1UYjo4XChtU_tirFF5NzcxYfUnJUnATRgC_GuuQz5zmBEbrry252EED86lPV8UVTd9RhS1Ks9k8yJeAN8-LvA",
            )
        )

        @JvmStatic
        @BeforeClass
        fun setup(): Unit {

            // Party B Entity Configuration (federation)

            partyBConfiguration = entityConfiguration(
                publicKey = partyBKeyPair.toPublicJWK(),
                authorityHints = arrayOf(
                    "https://edugain.org/federation_one",
                    "https://edugain.org/federation_two"
                ),
                iss = "https://openid.sunet.se",
                sub = "https://openid.sunet.se"
            )

            partyBJwt = sign(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), partyBConfiguration).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = partyBKeyPair.keyID
                ),
                key = partyBJwk
            )

            // Intermediate 1 ( Federation 2 )

            intermediateEntityConfiguration = entityConfiguration(
                publicKey = intermediateEntityKeyPair.toPublicJWK(),
                authorityHints = arrayOf(
                    "https://edugain.org/federation_three",
                    "https://edugain.org/federation_four"
                ),
                iss = "https://openid.sunet.se",
                sub = "https://openid.sunet.se"
            )

            intermediateEntityConfigurationJwt = sign(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), intermediateEntityConfiguration).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = intermediateEntityKeyPair.keyID
                ),
                key = intermediateEntityConfigurationJwk
            )

            //signed with intermediateEntity1 Private Key
            intermediateEntitySubordinateStatement = intermediateEntity(
                publicKey = intermediateEntityKeyPair.toPublicJWK(),
                iss = "https://openid.sunetone.se",
                sub = "https://openid.sunet.se"
            )

            intermediateEntityConfiguration1 = entityConfiguration(
                publicKey = intermediateEntity1KeyPair.toPublicJWK(),
                authorityHints = arrayOf("https://edugain.org/federation_five"),
                iss = "https://openid.sunetone.se",
                sub = "https://openid.sunetone.se"
            )

            intermediateEntityConfiguration1Jwt = sign(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), intermediateEntityConfiguration1).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = intermediateEntity1KeyPair.keyID
                ),
                key = intermediateEntityConfiguration1Jwk
            )

            intermediateEntity1SubordinateStatement = intermediateEntity(
                publicKey = intermediateEntity1KeyPair.toPublicJWK(),
                iss = "https://openid.sunettwo.se",
                sub = "https://openid.sunetone.se"
            )

            // Federation 4
            validTrustAnchorConfiguration = entityConfiguration(
                publicKey = validTrustAnchorKeyPair.toPublicJWK(),
                authorityHints = arrayOf(),
                iss = "https://openid.sunetthree.se",
                sub = "https://openid.sunettwo.se"
            )

            validTrustAnchorConfigurationJwt = sign(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), validTrustAnchorConfiguration).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = validTrustAnchorKeyPair.keyID
                ),
                key = validTrustAnchorConfigurationJwk
            )

            validTrustAnchorSubordinateStatement = intermediateEntity(
                publicKey = validTrustAnchorKeyPair.toPublicJWK(),
                iss = "https://openid.sunetthree.se",
                sub = "https://openid.sunetthree.se"
            )

            // Federation 3
            unknownTrustAnchorConfiguration = entityConfiguration(
                publicKey = unknownTrustAnchorKeyPair.toPublicJWK(),
                authorityHints = arrayOf(),
                iss = "https://openid.sunetfour.se",
                sub = "https://openid.sunetone.se"
            )

            unknownTrustAnchorConfigurationJwt = sign(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), unknownTrustAnchorConfiguration).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = unknownTrustAnchorKeyPair.keyID
                ),
                key = unknownTrustAnchorConfigurationJwk
            )

            unknownTrustAnchorSubordinateStatement = intermediateEntity(
                publicKey = unknownTrustAnchorKeyPair.toPublicJWK(),
                iss = "https://openid.sunetfour.se",
                sub = "https://openid.sunetfour.se"
            )

            // Federation 1
            invalidTrustAnchorConfiguration = entityConfiguration(
                publicKey = invalidTrustAnchorKeyPair.toPublicJWK(),
                authorityHints = arrayOf(),
                iss = "https://openid.sunetfive.se",
                sub = "https://openid.sunetfour.se"
            )

            invalidTrustAnchorConfigurationJwt = sign(
                payload = Json.encodeToJsonElement(serializer = EntityConfigurationStatement.serializer(), invalidTrustAnchorConfiguration).jsonObject,
                header = JWTHeader(
                    alg = "ES256",
                    typ = "entity-statement+jwt",
                    kid = invalidTrustAnchorKeyPair.keyID
                ),
                key = invalidTrustAnchorConfigurationJwk
            )

            invalidTrustAnchorSubordinateStatement = intermediateEntity(
                publicKey = invalidTrustAnchorKeyPair.toPublicJWK(),
                iss = "https://openid.sunetfive.se",
                sub = "https://openid.sunetfive.se"
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
        }
    }

    private val mockEngine = MockEngine { request ->
        when (request.url) {
            Url("https://edugain.org/federation") -> respond(
                content = partyBJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_one") -> respond(
                content = invalidTrustAnchorConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_two") -> respond(
                content = intermediateEntityConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_three") -> respond(
                content = unknownTrustAnchorConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_four") -> respond(
                content = intermediateEntityConfiguration1Jwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_five") -> respond(
                content = validTrustAnchorConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation/federation_fetch_endpoint") -> respond(
                content = intermediateEntityConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_one/federation_fetch_endpoint") -> respond(
                content = validTrustAnchorConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_two/federation_fetch_endpoint") -> respond(
                content = invalidTrustAnchorConfigurationJwt,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_three/federation_fetch_endpoint") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdGhyZWUvZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCJ9.ZnPDr-p8_RF9vI_DUqEuld-iJSPTMC5IL_B6rp_7mK1L4F_TXg0WJBP7uyn1dpkuS-Cd0V5oqHjtsxJgCoToqXGc2qvewktHOPEVKmIEVm25ALMVfiU0HZE1fU78crpF7xEo4UVBMHq78_Kk1QUg-SPmjGtS3IJ1e-EW8kxdnUVXvSsqP1pPh1iXVIjUHlQsh0SbfIbmLDmu5xYjlXXzad56zJv1G0Jov8gWw8wPYOwWY2j06MwQghu_N-ViyeFDa1UYjo4XChtU_tirFF5NzcxYfUnJUnATRgC_GuuQz5zmBEbrry252EED86lPV8UVTd9RhS1Ks9k8yJeAN8-LvA",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            else -> error("Unhandled ${request.url}")
        }
}

    @Test
    fun readAuthorityHintsTest() {
        assertEquals(
            listOfEntityConfigurationStatementList.toString(),
            TrustChainValidation().readAuthorityHints(
                partyBId = "https://edugain.org/federation",
                engine = mockEngine
            ).toString()
        )
    }

    @Test
    fun fetchSubordinateStatementsTest() {
        assertEquals(
            listOfSubordinateStatementList,
            TrustChainValidation().fetchSubordinateStatements(
            entityConfigurationStatementsList = listOfEntityConfigurationStatementList,
            engine = mockEngine
            )
        )
    }

    @Test
    fun validateTrustChainTest() {
        assertTrue(
            listOfSubordinateStatementList.all {  TrustChainValidation().validateTrustChain(it) }
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
        iat = LocalDateTime.now().second,
        exp = LocalDateTime.now().plusHours(1).second,
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
    sub: String = "https://openid.sunet.se"
): EntityConfigurationStatement {

    return EntityConfigurationStatement(
        iss = iss,
        sub = sub,
        iat = LocalDateTime.now().second,
        exp = LocalDateTime.now().plusHours(1).second,
        metadata = JsonObject(
            mapOf(
                "federation_entity" to JsonObject(
                    mapOf(
                        "federation_fetch_endpoint" to JsonPrimitive("https://sunet.se/openid/fedapi"),
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
