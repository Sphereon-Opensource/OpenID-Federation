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
                authorityHints = arrayOf("https://edugain.org/federation"),
            )
            partyBSubordinateStatement = intermediateEntity(
                publicKey = partyBKeyPair.toPublicJWK()
            )

            intermediateEntityKeyPair = ECKeyGenerator(Curve.P_256).generate()
            intermediateEntityConfiguration = entityConfiguration(
                publicKey = intermediateEntityKeyPair.toPublicJWK(),
                authorityHints = arrayOf(
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
                authorityHints = arrayOf("https://edugain.org/federation_three"),
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
                authorityHints = arrayOf(),
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
                authorityHints = arrayOf(),
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
                authorityHints = arrayOf(),
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
        when (request.url) {
            Url("https://edugain.org/federation") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiIgaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJzdWIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImV4cCI6NDgsImlhdCI6NDgsImp3a3MiOnsia2V5cyI6W3sia2lkIjpudWxsLCJrdHkiOiJFQyIsImNydiI6IlAyNTYiLCJ4IjoiRmtWYzdmMTZPX0E4UEpVandOVEVFME10eF9FSTM4OXRPdlo1YzhtaVJwWSIsInkiOiJkX0ktTUdwWHh5SzVfR3Y1RHFwZnRJblVyLUo2X3RjQU1TMzdkMXdlY1VRIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsiZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCI6Imh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbi9mZWRlcmF0aW9uX2ZldGNoX2VuZHBvaW50IiwiaG9tZXBhZ2VfdXJpIjoiaHR0cHM6Ly93d3cuc3VuZXQuc2UiLCJvcmdhbml6YXRpb25fbmFtZSI6IlNVTkVUIn0sIm9wZW5pZF9wcm92aWRlciI6eyJpc3N1ZXIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImF1dGhvcml6YXRpb25fZW5kcG9pbnQiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZS9hdXRob3JpemF0aW9uIiwiZ3JhbnRfdHlwZXNfc3VwcG9ydGVkIjpbImF1dGhvcml6YXRpb25fY29kZSJdLCJpZF90b2tlbl9zaWduaW5nX2FsZ192YWx1ZXNfc3VwcG9ydGVkIjpbIlJTMjU2IiwiRVMyNTYiXSwibG9nb191cmkiOiJodHRwczovL3d3dy51bXUuc2UvaW1nL3VtdS1sb2dvLWxlZnQtbmVnLlNFLnN2ZyIsIm9wX3BvbGljeV91cmkiOiJvcF9wb2xpY3lfdXJpIiwicmVzcG9uc2VfdHlwZXNfc3VwcG9ydGVkIjpbImNvZGUiXSwic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiLCJwdWJsaWMiXSwidG9rZW5fZW5kcG9pbnQiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZS90b2tlbiIsInRva2VuX2VuZHBvaW50X2F1dGhfbWV0aG9kc19zdXBwb3J0ZWQiOlsicHJpdmF0ZV9rZXlfand0Il0sImp3a3NfdXJpIjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvandrcyJ9fSwiY3JpdCI6bnVsbCwiYXV0aG9yaXR5X2hpbnRzIjpbImh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbl9vbmUiXSwidHJ1c3RfbWFya3MiOm51bGwsInRydXN0X21hcmtfaXNzdWVycyI6bnVsbCwidHJ1c3RfbWFya19vd25lcnMiOm51bGx9.tP7ObwU01pj-05f56XDQ5jN6EDY217_L_nvhhKPsmaCPP1IW6w9n_o-AlXcU8lFDwdYmrO-4H_i-TEOsoJYhxcTCsnL4DVNNF7aLNFDlYjnmteQM06uCZbq9XVfCsIpDtcnFTnH7DQAG5Bs0vu-Imnc9LREFr1nSeL2dNTEtEcqH_wjno5d_u3DulrZeIyDwN7W_Q2xFMr245PcI9CMtn6jzPJw3l9qV7EK9A5HqmaUHCi5od8XYcJUq2EnPQBMJ21tOLei_1vTxt_KG5ANvAf8CKr6Ehp5IntA5R-df4xqL6lEhEjhrC6EBpQcaF5gehRbweZNKQlb8Ex39VaU95w",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation/federation_fetch_endpoint") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb24vZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCJ9.psisFesGRDx_-iBEfb_sb6ydpKh9ih5vWTTmmYk4i1ZYZOgLxodrSkRWcDq4agRadcTj8XyZiDd3CYQtQIH8N1fPgrvMJTAGOQxpYG31GN0gDaWmFvpTMEynPY4NxFh5oP9oR7VjYRe_hxLcI_fFhO0GHyPDaHmPXi2jvPQ9Wg-bYOMkHmf9YbbQ30GSHZWhd-Kg3xsbmEqGg91Jj70UYwPQT3h9tI7-OELExU6WSQLT6HSVQTuMdhIoLp_f7ELkeUdO3YLxQyZ8h5QU1lTHrycAIQ1g-9NkyCOJQWrzWojxwP30yLWvaPPfwjz01jHfymIjtE0fOlRqZ-xMrJFzpQ",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_one") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiIgaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJzdWIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImV4cCI6NDgsImlhdCI6NDgsImp3a3MiOnsia2V5cyI6W3sia2lkIjpudWxsLCJrdHkiOiJFQyIsImNydiI6IlAyNTYiLCJ4IjoiRmtWYzdmMTZPX0E4UEpVandOVEVFME10eF9FSTM4OXRPdlo1YzhtaVJwWSIsInkiOiJkX0ktTUdwWHh5SzVfR3Y1RHFwZnRJblVyLUo2X3RjQU1TMzdkMXdlY1VRIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsiZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCI6Imh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbl9vbmUvZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCIsImhvbWVwYWdlX3VyaSI6Imh0dHBzOi8vd3d3LnN1bmV0LnNlIiwib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsiaXNzdWVyIjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJhdXRob3JpemF0aW9uX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvYXV0aG9yaXphdGlvbiIsImdyYW50X3R5cGVzX3N1cHBvcnRlZCI6WyJhdXRob3JpemF0aW9uX2NvZGUiXSwiaWRfdG9rZW5fc2lnbmluZ19hbGdfdmFsdWVzX3N1cHBvcnRlZCI6WyJSUzI1NiIsIkVTMjU2Il0sImxvZ29fdXJpIjoiaHR0cHM6Ly93d3cudW11LnNlL2ltZy91bXUtbG9nby1sZWZ0LW5lZy5TRS5zdmciLCJvcF9wb2xpY3lfdXJpIjoib3BfcG9saWN5X3VyaSIsInJlc3BvbnNlX3R5cGVzX3N1cHBvcnRlZCI6WyJjb2RlIl0sInN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjpbInBhaXJ3aXNlIiwicHVibGljIl0sInRva2VuX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvdG9rZW4iLCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjpbInByaXZhdGVfa2V5X2p3dCJdLCJqd2tzX3VyaSI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2p3a3MifX0sImNyaXQiOm51bGwsImF1dGhvcml0eV9oaW50cyI6WyJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdHdvIl0sInRydXN0X21hcmtzIjpudWxsLCJ0cnVzdF9tYXJrX2lzc3VlcnMiOm51bGwsInRydXN0X21hcmtfb3duZXJzIjpudWxsfQ.A6TKVthUEUgjJL-XNa_mPEx_n-gpeFcPvla4HzMepTBv6VjIiG1YSVvgBBA3SiHSPt9Eqx5NKWKN0YpXSNcootff7JZTlQSLRk-K2vwPmNOE2NLg6B40c2cYi36E6rVIon-4Q0zJC0WdvN1lDrS1_gwL_pReToCwFKQG42MyXjJzKlWNIqYLyMcBr5qoHBW3hhCXPdWxw3FKc8_kVqPAjXNkkB4VH44R1XOYvK3OYo27f26GEstChA8nHZwZak0ky2powMwWNh36_YyjxaJvyPiIvNUqcsYgRG3IVrRw7Lhd0qT_Um3z8ZGuSsESSThZQOfw_J2cq7Esem8VKwmo3w",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_one/federation_fetch_endpoint") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fb25lL2ZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQifQ.elvdpFKwSmXO878CeE3iK2LAmlPV9_iBcuDM3JL7_zJ9UWay0K-QCrlwbqWNxkH-d0DrnKh1KHZGarQ1u9OB0Q2CFCplkbdNiQc7qUpSk1aHoaz30O9p7o4qKtNjz8AV3gE0xKFLnkj6M7rJZ3POC8AONelC1eaGe4AfgNzd7JgWd_lQGV2esNUoq5x9udjXrX1HW--PAC6vue-F-Tcx_TU2Dff3qOkW-SJvbvGnxVdvirutsJoXdae9yGMQLoi3Araefr1Tfjvd1MSxhlUL80eASrT3XgHZEeajN8ijeYUuaY36K_gCxzxkrgypXZyfZptSIGDctOuj8GucZNz9FA",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_two") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiIgaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJzdWIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImV4cCI6NDgsImlhdCI6NDgsImp3a3MiOnsia2V5cyI6W3sia2lkIjpudWxsLCJrdHkiOiJFQyIsImNydiI6IlAyNTYiLCJ4IjoiRmtWYzdmMTZPX0E4UEpVandOVEVFME10eF9FSTM4OXRPdlo1YzhtaVJwWSIsInkiOiJkX0ktTUdwWHh5SzVfR3Y1RHFwZnRJblVyLUo2X3RjQU1TMzdkMXdlY1VRIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsiZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCI6Imh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbl90d28vZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCIsImhvbWVwYWdlX3VyaSI6Imh0dHBzOi8vd3d3LnN1bmV0LnNlIiwib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsiaXNzdWVyIjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJhdXRob3JpemF0aW9uX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvYXV0aG9yaXphdGlvbiIsImdyYW50X3R5cGVzX3N1cHBvcnRlZCI6WyJhdXRob3JpemF0aW9uX2NvZGUiXSwiaWRfdG9rZW5fc2lnbmluZ19hbGdfdmFsdWVzX3N1cHBvcnRlZCI6WyJSUzI1NiIsIkVTMjU2Il0sImxvZ29fdXJpIjoiaHR0cHM6Ly93d3cudW11LnNlL2ltZy91bXUtbG9nby1sZWZ0LW5lZy5TRS5zdmciLCJvcF9wb2xpY3lfdXJpIjoib3BfcG9saWN5X3VyaSIsInJlc3BvbnNlX3R5cGVzX3N1cHBvcnRlZCI6WyJjb2RlIl0sInN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjpbInBhaXJ3aXNlIiwicHVibGljIl0sInRva2VuX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvdG9rZW4iLCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjpbInByaXZhdGVfa2V5X2p3dCJdLCJqd2tzX3VyaSI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2p3a3MifX0sImNyaXQiOm51bGwsImF1dGhvcml0eV9oaW50cyI6WyJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdGhyZWUiXSwidHJ1c3RfbWFya3MiOm51bGwsInRydXN0X21hcmtfaXNzdWVycyI6bnVsbCwidHJ1c3RfbWFya19vd25lcnMiOm51bGx9.bfINUjOaCxvVVtnTBCOyGKgHgvym_FD-oFRa33xAWYgs2zBGbj2PZfFma8W4eH_I6GDmHcM0S65C-rT96oqBbUBM0LmnJ5ztJs5DZSc69QN0Q_TgEtq4r6mZQQIbBPxTeWqbiAwMaF0QxBA38Y312aeQU9mCaxOI7CX8PUzSQtps6m81vGfLBscBHfi3Qnh-aC2Ob7NnR_9kACn-9Ddj_AoZgjRGDCqpQd9sBpkcwno7uFqy02-vjuzX-wGio1q7NTXqzuV1eI7OSXj-1pxR09I1k8G3aBud2NMGTohBlA9VB6-oUYZFfIV1b_WXoSxz1oS-67g8FADiYStsiaDVjA",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_two/federation_fetch_endpoint") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdHdvL2ZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQifQ.eJCajk2u8NMpq2Qi8tUUW4yT3t8zN5efdMz95Qg2Edc9-xXp32V6POp2Zmj3M99TyUrp9YwK9TEesH_7oz5QXYFR4J5cNkvd4mJvpDudvgayvlW_3z4nLUWru8nCfSyH_PiSwbPdhpiUZGvf5-KZHMdQ0SAbp5GSMSf4CD8fIYEV8u_KyS38g9Zgsv6BoQzCZtEPtY_cLCG9YV1S7V3tLsW5-bhf8da8mEny1cdnSLI6YJanQxmpW9Aq0ooxntIo1cokeL2fFoUvzNw-4JhYLOgqVDSNg3lhNhsbiPxHI3yN04Qcn90h0s1--QFHaf8rgMWyLyKZPId4kxPR-4PZoQ",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )

            Url("https://edugain.org/federation_three") -> respond(
                content = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiIgaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJzdWIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImV4cCI6NDgsImlhdCI6NDgsImp3a3MiOnsia2V5cyI6W3sia2lkIjpudWxsLCJrdHkiOiJFQyIsImNydiI6IlAyNTYiLCJ4IjoiRmtWYzdmMTZPX0E4UEpVandOVEVFME10eF9FSTM4OXRPdlo1YzhtaVJwWSIsInkiOiJkX0ktTUdwWHh5SzVfR3Y1RHFwZnRJblVyLUo2X3RjQU1TMzdkMXdlY1VRIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsiZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCI6Imh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbl90aHJlZS9mZWRlcmF0aW9uX2ZldGNoX2VuZHBvaW50IiwiaG9tZXBhZ2VfdXJpIjoiaHR0cHM6Ly93d3cuc3VuZXQuc2UiLCJvcmdhbml6YXRpb25fbmFtZSI6IlNVTkVUIn0sIm9wZW5pZF9wcm92aWRlciI6eyJpc3N1ZXIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImF1dGhvcml6YXRpb25fZW5kcG9pbnQiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZS9hdXRob3JpemF0aW9uIiwiZ3JhbnRfdHlwZXNfc3VwcG9ydGVkIjpbImF1dGhvcml6YXRpb25fY29kZSJdLCJpZF90b2tlbl9zaWduaW5nX2FsZ192YWx1ZXNfc3VwcG9ydGVkIjpbIlJTMjU2IiwiRVMyNTYiXSwibG9nb191cmkiOiJodHRwczovL3d3dy51bXUuc2UvaW1nL3VtdS1sb2dvLWxlZnQtbmVnLlNFLnN2ZyIsIm9wX3BvbGljeV91cmkiOiJvcF9wb2xpY3lfdXJpIiwicmVzcG9uc2VfdHlwZXNfc3VwcG9ydGVkIjpbImNvZGUiXSwic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiLCJwdWJsaWMiXSwidG9rZW5fZW5kcG9pbnQiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZS90b2tlbiIsInRva2VuX2VuZHBvaW50X2F1dGhfbWV0aG9kc19zdXBwb3J0ZWQiOlsicHJpdmF0ZV9rZXlfand0Il0sImp3a3NfdXJpIjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvandrcyJ9fSwiY3JpdCI6bnVsbCwidHJ1c3RfbWFya3MiOm51bGwsInRydXN0X21hcmtfaXNzdWVycyI6bnVsbCwidHJ1c3RfbWFya19vd25lcnMiOm51bGx9.K_pBKXuAhkQnLMnZoaKWBHNLvrbRJi9SLWTCgRWBQK0UvkXUzN7AXbitZUNby3WpjzSweIdZKsGbvgEzGQG3IpELEpBC8pEgVn-nZGcFjkxvLfA5wd-FuAt_mNK_NU_qlNx2xRTNXCM2hRxNvbOK2Si9IPaY5Mb8kdQ2CGObUjsyA8ZKHTYT9Uiv_EWOmgKYofQvk2iEUqLMlMiLah4pNe_S1jesB9brksCbtj_rLfketXcPs3mcMun7aP73guL7-BIwAB1TzFsQjYmYZHimPCmgG6nL2zOx2xYJWP6v1xUdzbrB-4vHuay102z6sf46MkSGv_CIzKLPIK5XKbkEKA",
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

        val listOfEntityConfigurationStatementList = listOf(
            listOf(
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb24vZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCJ9.psisFesGRDx_-iBEfb_sb6ydpKh9ih5vWTTmmYk4i1ZYZOgLxodrSkRWcDq4agRadcTj8XyZiDd3CYQtQIH8N1fPgrvMJTAGOQxpYG31GN0gDaWmFvpTMEynPY4NxFh5oP9oR7VjYRe_hxLcI_fFhO0GHyPDaHmPXi2jvPQ9Wg-bYOMkHmf9YbbQ30GSHZWhd-Kg3xsbmEqGg91Jj70UYwPQT3h9tI7-OELExU6WSQLT6HSVQTuMdhIoLp_f7ELkeUdO3YLxQyZ8h5QU1lTHrycAIQ1g-9NkyCOJQWrzWojxwP30yLWvaPPfwjz01jHfymIjtE0fOlRqZ-xMrJFzpQ",
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fb25lL2ZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQifQ.elvdpFKwSmXO878CeE3iK2LAmlPV9_iBcuDM3JL7_zJ9UWay0K-QCrlwbqWNxkH-d0DrnKh1KHZGarQ1u9OB0Q2CFCplkbdNiQc7qUpSk1aHoaz30O9p7o4qKtNjz8AV3gE0xKFLnkj6M7rJZ3POC8AONelC1eaGe4AfgNzd7JgWd_lQGV2esNUoq5x9udjXrX1HW--PAC6vue-F-Tcx_TU2Dff3qOkW-SJvbvGnxVdvirutsJoXdae9yGMQLoi3Araefr1Tfjvd1MSxhlUL80eASrT3XgHZEeajN8ijeYUuaY36K_gCxzxkrgypXZyfZptSIGDctOuj8GucZNz9FA",
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdHdvL2ZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQifQ.eJCajk2u8NMpq2Qi8tUUW4yT3t8zN5efdMz95Qg2Edc9-xXp32V6POp2Zmj3M99TyUrp9YwK9TEesH_7oz5QXYFR4J5cNkvd4mJvpDudvgayvlW_3z4nLUWru8nCfSyH_PiSwbPdhpiUZGvf5-KZHMdQ0SAbp5GSMSf4CD8fIYEV8u_KyS38g9Zgsv6BoQzCZtEPtY_cLCG9YV1S7V3tLsW5-bhf8da8mEny1cdnSLI6YJanQxmpW9Aq0ooxntIo1cokeL2fFoUvzNw-4JhYLOgqVDSNg3lhNhsbiPxHI3yN04Qcn90h0s1--QFHaf8rgMWyLyKZPId4kxPR-4PZoQ",
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldG9uZS5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiZXhwIjoyMSwiaWF0IjoyMSwiandrcyI6eyJrZXlzIjpbeyJraWQiOm51bGwsImt0eSI6IkVDIiwiY3J2IjoiUC0yNTYiLCJ4IjoiQWdKQzZLb3R2X1FubEI2UENoZEdpeXRydkg2dnVabkFrdzFCN0ZYVlBvZyIsInkiOiJ1M19qOWVETW90RjVDV0R4M2c2V3EybjVWUE1ZZ2plX01Sb1BWME5QX1YwIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsib3JnYW5pemF0aW9uX25hbWUiOiJTVU5FVCJ9LCJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiXSwidG9rZW5fZW5kcG9pbnRfYXV0aF9tZXRob2RzX3N1cHBvcnRlZCI6WyJwcml2YXRlX2tleV9qd3QiXX19LCJjcml0IjpudWxsLCJtZXRhZGF0YV9wb2xpY3kiOnsib3BlbmlkX3Byb3ZpZGVyIjp7InN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjp7InZhbHVlIjpbInBhaXJ3aXNlIl19LCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjp7ImRlZmF1bHQiOlsicHJpdmF0ZV9rZXlfand0Il0sInN1YnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiLCJjbGllbnRfc2VjcmV0X2p3dCJdLCJzdXBlcnNldF9vZiI6WyJwcml2YXRlX2tleV9qd3QiXX19fSwiY29uc3RyYWludHMiOm51bGwsIm1ldGFkYXRhX3BvbGljeV9jcml0IjpudWxsLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdGhyZWUvZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCJ9.ZnPDr-p8_RF9vI_DUqEuld-iJSPTMC5IL_B6rp_7mK1L4F_TXg0WJBP7uyn1dpkuS-Cd0V5oqHjtsxJgCoToqXGc2qvewktHOPEVKmIEVm25ALMVfiU0HZE1fU78crpF7xEo4UVBMHq78_Kk1QUg-SPmjGtS3IJ1e-EW8kxdnUVXvSsqP1pPh1iXVIjUHlQsh0SbfIbmLDmu5xYjlXXzad56zJv1G0Jov8gWw8wPYOwWY2j06MwQghu_N-ViyeFDa1UYjo4XChtU_tirFF5NzcxYfUnJUnATRgC_GuuQz5zmBEbrry252EED86lPV8UVTd9RhS1Ks9k8yJeAN8-LvA"
            ),
        )
        assertEquals(
            listOfEntityConfigurationStatementList,
            readAuthorityHints(jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiIgaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UiLCJzdWIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImV4cCI6NDgsImlhdCI6NDgsImp3a3MiOnsia2V5cyI6W3sia2lkIjpudWxsLCJrdHkiOiJFQyIsImNydiI6IlAyNTYiLCJ4IjoiRmtWYzdmMTZPX0E4UEpVandOVEVFME10eF9FSTM4OXRPdlo1YzhtaVJwWSIsInkiOiJkX0ktTUdwWHh5SzVfR3Y1RHFwZnRJblVyLUo2X3RjQU1TMzdkMXdlY1VRIn1dfSwibWV0YWRhdGEiOnsiZmVkZXJhdGlvbl9lbnRpdHkiOnsiZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCI6Imh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbi9mZWRlcmF0aW9uX2ZldGNoX2VuZHBvaW50IiwiaG9tZXBhZ2VfdXJpIjoiaHR0cHM6Ly93d3cuc3VuZXQuc2UiLCJvcmdhbml6YXRpb25fbmFtZSI6IlNVTkVUIn0sIm9wZW5pZF9wcm92aWRlciI6eyJpc3N1ZXIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImF1dGhvcml6YXRpb25fZW5kcG9pbnQiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZS9hdXRob3JpemF0aW9uIiwiZ3JhbnRfdHlwZXNfc3VwcG9ydGVkIjpbImF1dGhvcml6YXRpb25fY29kZSJdLCJpZF90b2tlbl9zaWduaW5nX2FsZ192YWx1ZXNfc3VwcG9ydGVkIjpbIlJTMjU2IiwiRVMyNTYiXSwibG9nb191cmkiOiJodHRwczovL3d3dy51bXUuc2UvaW1nL3VtdS1sb2dvLWxlZnQtbmVnLlNFLnN2ZyIsIm9wX3BvbGljeV91cmkiOiJvcF9wb2xpY3lfdXJpIiwicmVzcG9uc2VfdHlwZXNfc3VwcG9ydGVkIjpbImNvZGUiXSwic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOlsicGFpcndpc2UiLCJwdWJsaWMiXSwidG9rZW5fZW5kcG9pbnQiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZS90b2tlbiIsInRva2VuX2VuZHBvaW50X2F1dGhfbWV0aG9kc19zdXBwb3J0ZWQiOlsicHJpdmF0ZV9rZXlfand0Il0sImp3a3NfdXJpIjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvandrcyJ9fSwiY3JpdCI6bnVsbCwiYXV0aG9yaXR5X2hpbnRzIjpbImh0dHBzOi8vZWR1Z2Fpbi5vcmcvZmVkZXJhdGlvbiJdLCJ0cnVzdF9tYXJrcyI6bnVsbCwidHJ1c3RfbWFya19pc3N1ZXJzIjpudWxsLCJ0cnVzdF9tYXJrX293bmVycyI6bnVsbH0.imtQl0xm22aNQnw-ZyWPx-h_9sr_LMDP9nlJMsLZcfjABu6zki_LjJdoxeck9H4_H6_Zf1HQw5ZORj6p6LKKQ76OJkp88VqnoVt6Ko6j6CxohtGNo9KmzoW0a0c6Hi2L1eWlMZJsI2WhZ6DZhh3HngyzHX0jk7f51EpDmmnZkzICjA3XtoCBDx6T40h4ncowHfMj4lwfKOwZJAPEow5ISoqc4e3n800Hr6XEDJ9C1dMrzbr5M5bGYHxtQrOkp_kJWndLQPyvasVr9LQ-MDUWvCRfPHDZsCzGsvGkfnIQ617CXdT6dbyzqy7bJT8-sLROuZ6kT9v_ZcRnqnzNe6fd0Q", engine = mockEngine)
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
