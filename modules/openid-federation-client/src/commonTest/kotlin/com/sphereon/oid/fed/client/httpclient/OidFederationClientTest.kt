package com.sphereon.oid.fed.client.httpclient

import com.sphereon.oid.fed.client.OidFederationClientService.HTTP
import io.ktor.client.engine.mock.*
import io.ktor.client.engine.mock.MockEngine.Companion.invoke
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
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

    private lateinit var client: HttpClientCallbackService

    @BeforeTest
    fun setup() {
       HTTP.register(MockHttpClientCallbackService(mockEngine))
       client = HTTP
    }

    @Test
    fun testGetEntityStatement() = runTest {
            val response = client.fetchEntityStatement(
                "https://www.example.com?iss=https://edugain.org/federation&sub=https://openid.sunet.se",
                HttpMethod.Get
            )
            assertEquals(jwt, response)
        }

    @Test
    fun testPostEntityStatement() = runTest {
        val response = client.fetchEntityStatement("https://www.example.com",
            HttpMethod.Post,
            Parameters.build {
                append("iss", "https://edugain.org/federation")
                append("sub", "https://openid.sunet.se")
            })
        assertEquals(jwt, response)
    }
}
