package com.sphereon.oid.fed.common.httpclient

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sphereon.oid.fed.openapi.models.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class OidFederationClientTest {

    private val jwt = """
        eyJhbGciOiJSUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0In0.eyJpc3MiOiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb24i
        LCJzdWIiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsImV4cCI6MTU2ODM5NzI0NywiaWF0IjoxNTY4MzEwODQ3LCJzb3VyY2VfZW5kcG9pbnQi
        OiJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb24vZmVkZXJhdGlvbl9mZXRjaF9lbmRwb2ludCIsImp3a3MiOnsia2V5cyI6W3siZSI6IkFR
        QUIiLCJraWQiOiJkRUV0UmpselkzZGpjRU51VDAxd09HeHJabGt4YjNSSVFWSmxNVFkwLi4uIiwia3R5IjoiUlNBIiwibiI6Ing5N1lLcWM5Q3Mt
        RE50RnJRN192aFhvSDlid2tEV1c2RW4yakowNDR5SC4uLiJ9XX0sIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7Im9yZ2FuaXphdGlv
        bl9uYW1lIjoiU1VORVQifX0sIm1ldGFkYXRhX3BvbGljeSI6eyJvcGVuaWRfcHJvdmlkZXIiOnsic3ViamVjdF90eXBlc19zdXBwb3J0ZWQiOnsi
        dmFsdWUiOlsicGFpcndpc2UiXX0sInRva2VuX2VuZHBvaW50X2F1dGhfbWV0aG9kc19zdXBwb3J0ZWQiOnsiZGVmYXVsdCI6WyJwcml2YXRlX2tl
        eV9qd3QiXSwic3Vic2V0X29mIjpbInByaXZhdGVfa2V5X2p3dCIsImNsaWVudF9zZWNyZXRfand0Il0sInN1cGVyc2V0X29mIjpbInByaXZhdGVf
        a2V5X2p3dCJdfX19fQ.Jdd45c8LKvdzUy3FXl66Dp_1MXCkcbkL_uO17kWP7bIeYHe-fKqPlV2stta3oUitxy3NB8U3abgmNWnSf60qEaF7YmiDr
        j0u3WZE87QXYv6fAMW00TGvcPIC8qtoFcamK7OTrsi06eqKUJslCPSEXYl6couNkW70YSiJGUI0PUQ-TmD-vFFpQCFwtIfQeUUm47GxcCP0jBjjz
        gg1D3rMCX49RhRdJWnH8yl6r1lZazcREVqNuuN6LBHhKA7asNNwtLkcJP1rCRioxIFQPn7g0POM6t50l4wNhDewXZ-NVENex4N7WeVTA1Jh9EcD_
        swTuR9X1AbD7vW80OXe_RrGmw
        """

    private val mockEngine = MockEngine {
        respond(
            content = jwt,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
        )
    }

    @Test
    fun testGetEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val response = client.fetchEntityStatement("https://www.example.com?iss=https://edugain.org/federation&sub=https://openid.sunet.se", HttpMethod.Get)
            assertEquals(jwt, response)
        }
    }

    @Test
    fun testPostEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val key = RSAKeyGenerator(2048).keyID("key1").generate()
            val entityStatement = EntityStatement(iss = "https://edugain.org/federation", sub = "https://openid.sunet.se")
            val payload: JsonObject = Json.encodeToJsonElement(entityStatement) as JsonObject
            val response = client.fetchEntityStatement("https://www.example.com", HttpMethod.Post,
                OidFederationClient.PostEntityParameters(
                    payload = payload,
                    header = JWTHeader(typ = "JWT", alg = "RS256", kid = key.keyID)
                )
            )
            assertEquals(jwt, response)
        }
    }
}
