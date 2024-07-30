package com.sphereon.oid.fed.common.mapper

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

class JsonMapperTest {

    private val mapper = JsonMapper()

    @Test
    fun testDecodeValidJWT() {
        val jwt =
            "eyJraWQiOiJCNkVCODQ4OENDODRDNDEwMTcxMzRCQzc3RjQxMzJBMDQ2N0NDQzBFIiwidHlwIjoiZW50aXR5LXN0YXRlbWVudFx1MDAyQmp3dCIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImV4cCI6MTUxNjIzOTAyMn0.NHVaYe26MbtOYhSKkoKYdFVomg4i8ZJd8_-RU8VNbftc"
        val (header, payload, signature) = mapper.decodeJWTComponents(jwt)

        assertEquals("RS256", header?.alg)
        assertEquals("B6EB8488CC84C41017134BC77F4132A0467CCC0E", header?.kid)
        assertEquals("entity-statement+jwt", header?.typ)

        payload as JsonObject
        assertEquals("1234567890", payload["sub"]?.jsonPrimitive?.content) // Check payload
        assertEquals("John Doe", payload["name"]?.jsonPrimitive?.content)
        assertEquals(true, payload["admin"]?.jsonPrimitive?.boolean)

        assertEquals("NHVaYe26MbtOYhSKkoKYdFVomg4i8ZJd8_-RU8VNbftc", signature?.value) // Check signature
    }

    @Test
    fun testDecodeJWTWithInvalidStructure() {
        val invalidJWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQSflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"  // Missing dots

        val exception = assertFails {
            mapper.decodeJWTComponents(invalidJWT)
        }

        assertIs<JsonMapper.InvalidJwtException>(exception)
    }

    @Test
    fun testDecodeJWTWithInvalidJSON() {
        val jwtWithInvalidJson =
            "eyJraWQiOiJCNkVCODQ4OENDODRDNDEwMTcxMzRCQzc3RjQxMzJBMDQ2N0NDQzBFIiwidHlwIjoiZW50aXR5LXN0YXRlbWVudFx1MDAyQmp3dCIsImFsZyI6IlJTMjU2In0.eyJzdWI6IjEyMzQ1Njc4OTAiLCJuYW1lIjoiSm9obiBEb2UiLCJhZG1pbiI6dHJ1ZX0.NHVaYe26MbtOYhSKkoKYdFVomg4i8ZJd8_-RU8VNbftc" // Missing quote in payload

        val exception = assertFails {
            mapper.decodeJWTComponents(jwtWithInvalidJson)
        }

        assertIs<JsonMapper.JwtDecodingException>(exception)
    }
}
