package com.sphereon.oid.fed.common.op

import com.sphereon.oid.fed.common.mapper.JsonMapper
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustChainValidationTest {

    private val intermediateEntityStatement = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyOTgwMjIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL3N1bmV0LnNlL29wZW5pZC9mZWRhcGkiLCJob21lcGFnZV91cmkiOiJodHRwczovL3d3dy5zdW5ldC5zZSIsIm9yZ2FuaXphdGlvbl9uYW1lIjoiU1VORVQifSwib3BlbmlkX3Byb3ZpZGVyIjp7Imlzc3VlciI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiYXV0aG9yaXphdGlvbl9lbmRwb2ludCI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2F1dGhvcml6YXRpb24iLCJncmFudF90eXBlc19zdXBwb3J0ZWQiOlsiYXV0aG9yaXphdGlvbl9jb2RlIl0sImlkX3Rva2VuX3NpZ25pbmdfYWxnX3ZhbHVlc19zdXBwb3J0ZWQiOlsiRVMyNTYiLCJSUzI1NiJdLCJsb2dvX3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9pbWcvdW11LWxvZ28tbGVmdC1uZWctU0Uuc3ZnIiwib3BfcG9saWN5X3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9lbi93ZWJzaXRlL2xlZ2FsLWluZm9ybWF0aW9uLyIsInJlc3BvbnNlX3R5cGVzX3N1cHBvcnRlZCI6WyJjb2RlIl0sInN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjpbInBhaXJ3aXNlIiwicHVibGljIl0sInRva2VuX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvdG9rZW4iLCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjpbInByaXZhdGVfa2V5X2p3dCJdLCJqd2tzX3VyaSI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2p3a3MifX0sImp3a3MiOnsia2V5cyI6W3siYWxnIjoiUlMyNTYiLCJlIjoiQVFBQiIsImtpZCI6ImtleTEiLCJrdHkiOiJSU0EiLCJuIjoicG5YQk91c0VBTnV1ZzZld2V6YjlKXy4uLiIsInVzZSI6InNpZyJ9XX0sImF1dGhvcml0eV9oaW50cyI6WyJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fb25lIiwiaHR0cHM6Ly9lZHVnYWluLm9yZy9mZWRlcmF0aW9uX3R3byJdfQ.dRJSJIhDkB6LmqJ7iKsacmWTKYnSJDy9X8KRpz3XZq0"
    private val intermediateEntityStatement1 = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyOTgwMjIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL3N1bmV0LnNlL29wZW5pZC9mZWRhcGkiLCJob21lcGFnZV91cmkiOiJodHRwczovL3d3dy5zdW5ldC5zZSIsIm9yZ2FuaXphdGlvbl9uYW1lIjoiU1VORVQifSwib3BlbmlkX3Byb3ZpZGVyIjp7Imlzc3VlciI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiYXV0aG9yaXphdGlvbl9lbmRwb2ludCI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2F1dGhvcml6YXRpb24iLCJncmFudF90eXBlc19zdXBwb3J0ZWQiOlsiYXV0aG9yaXphdGlvbl9jb2RlIl0sImlkX3Rva2VuX3NpZ25pbmdfYWxnX3ZhbHVlc19zdXBwb3J0ZWQiOlsiRVMyNTYiLCJSUzI1NiJdLCJsb2dvX3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9pbWcvdW11LWxvZ28tbGVmdC1uZWctU0Uuc3ZnIiwib3BfcG9saWN5X3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9lbi93ZWJzaXRlL2xlZ2FsLWluZm9ybWF0aW9uLyIsInJlc3BvbnNlX3R5cGVzX3N1cHBvcnRlZCI6WyJjb2RlIl0sInN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjpbInBhaXJ3aXNlIiwicHVibGljIl0sInRva2VuX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvdG9rZW4iLCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjpbInByaXZhdGVfa2V5X2p3dCJdLCJqd2tzX3VyaSI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2p3a3MifX0sImp3a3MiOnsia2V5cyI6W3siYWxnIjoiUlMyNTYiLCJlIjoiQVFBQiIsImtpZCI6ImtleTEiLCJrdHkiOiJSU0EiLCJuIjoicG5YQk91c0VBTnV1ZzZld2V6YjlKXy4uLiIsInVzZSI6InNpZyJ9XX0sImF1dGhvcml0eV9oaW50cyI6WyJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb25fdGhyZWUiXX0.HUuT8CtSWpax6Dbu8THPei16UDVEylUyESuXE3-PdPo"
    private val trustAnchor = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyOTgwMjIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL3N1bmV0LnNlL29wZW5pZC9mZWRhcGkiLCJob21lcGFnZV91cmkiOiJodHRwczovL3d3dy5zdW5ldC5zZSIsIm9yZ2FuaXphdGlvbl9uYW1lIjoiU1VORVQifSwib3BlbmlkX3Byb3ZpZGVyIjp7Imlzc3VlciI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiYXV0aG9yaXphdGlvbl9lbmRwb2ludCI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2F1dGhvcml6YXRpb24iLCJncmFudF90eXBlc19zdXBwb3J0ZWQiOlsiYXV0aG9yaXphdGlvbl9jb2RlIl0sImlkX3Rva2VuX3NpZ25pbmdfYWxnX3ZhbHVlc19zdXBwb3J0ZWQiOlsiRVMyNTYiLCJSUzI1NiJdLCJsb2dvX3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9pbWcvdW11LWxvZ28tbGVmdC1uZWctU0Uuc3ZnIiwib3BfcG9saWN5X3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9lbi93ZWJzaXRlL2xlZ2FsLWluZm9ybWF0aW9uLyIsInJlc3BvbnNlX3R5cGVzX3N1cHBvcnRlZCI6WyJjb2RlIl0sInN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjpbInBhaXJ3aXNlIiwicHVibGljIl0sInRva2VuX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvdG9rZW4iLCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjpbInByaXZhdGVfa2V5X2p3dCJdLCJqd2tzX3VyaSI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2p3a3MifX0sImp3a3MiOnsia2V5cyI6W3siYWxnIjoiUlMyNTYiLCJlIjoiQVFBQiIsImtpZCI6ImtleTEiLCJrdHkiOiJSU0EiLCJuIjoicG5YQk91c0VBTnV1ZzZld2V6YjlKXy4uLiIsInVzZSI6InNpZyJ9XX19.CxbYkZxQ8Y8U0uLBn5uAl1xJh96C8qv_4RB6cOIDvss"

    private val mockEngine = MockEngine { request ->
        when(request.url) {
            Url("https://edugain.org/federation") -> respond(
                content = intermediateEntityStatement,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
            Url("https://edugain.org/federation_one") -> respond(
                content = intermediateEntityStatement1,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
            Url("https://edugain.org/federation_two") -> respond(
                content = trustAnchor,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
            Url("https://edugain.org/federation_three") -> respond(
                content = trustAnchor,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
            else -> error("Unhandled ${request.url}")
        }
    }

    @Test
    fun readAuthorityHintsTest() {
        val entityConfigurationStatement = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL29wZW5pZC5zdW5ldC5zZSIsInN1YiI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyOTgwMjIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL3N1bmV0LnNlL29wZW5pZC9mZWRhcGkiLCJob21lcGFnZV91cmkiOiJodHRwczovL3d3dy5zdW5ldC5zZSIsIm9yZ2FuaXphdGlvbl9uYW1lIjoiU1VORVQifSwib3BlbmlkX3Byb3ZpZGVyIjp7Imlzc3VlciI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlIiwiYXV0aG9yaXphdGlvbl9lbmRwb2ludCI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2F1dGhvcml6YXRpb24iLCJncmFudF90eXBlc19zdXBwb3J0ZWQiOlsiYXV0aG9yaXphdGlvbl9jb2RlIl0sImlkX3Rva2VuX3NpZ25pbmdfYWxnX3ZhbHVlc19zdXBwb3J0ZWQiOlsiRVMyNTYiLCJSUzI1NiJdLCJsb2dvX3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9pbWcvdW11LWxvZ28tbGVmdC1uZWctU0Uuc3ZnIiwib3BfcG9saWN5X3VyaSI6Imh0dHBzOi8vd3d3LnVtdS5zZS9lbi93ZWJzaXRlL2xlZ2FsLWluZm9ybWF0aW9uLyIsInJlc3BvbnNlX3R5cGVzX3N1cHBvcnRlZCI6WyJjb2RlIl0sInN1YmplY3RfdHlwZXNfc3VwcG9ydGVkIjpbInBhaXJ3aXNlIiwicHVibGljIl0sInRva2VuX2VuZHBvaW50IjoiaHR0cHM6Ly9vcGVuaWQuc3VuZXQuc2UvdG9rZW4iLCJ0b2tlbl9lbmRwb2ludF9hdXRoX21ldGhvZHNfc3VwcG9ydGVkIjpbInByaXZhdGVfa2V5X2p3dCJdLCJqd2tzX3VyaSI6Imh0dHBzOi8vb3BlbmlkLnN1bmV0LnNlL2p3a3MifX0sImp3a3MiOnsia2V5cyI6W3siYWxnIjoiUlMyNTYiLCJlIjoiQVFBQiIsImtpZCI6ImtleTEiLCJrdHkiOiJSU0EiLCJuIjoicG5YQk91c0VBTnV1ZzZld2V6YjlKXy4uLiIsInVzZSI6InNpZyJ9XX0sImF1dGhvcml0eV9oaW50cyI6WyJodHRwczovL2VkdWdhaW4ub3JnL2ZlZGVyYXRpb24iXX0.8O9EVsFWRo65ITGDp2KS5sVs7PNOBEWPm60mcOyC29A"
        // It should be
        // [
        //  [intermediateEntityStatement, intermediateStatement1, trustAnchor],
        //  [intermediateStatement, trustAnchor]
        // ]
        val entityStatements = listOf(
            JsonMapper().mapEntityStatement(intermediateEntityStatement),
            JsonMapper().mapEntityStatement(intermediateEntityStatement1),
            JsonMapper().mapEntityStatement(trustAnchor),
            JsonMapper().mapEntityStatement(trustAnchor)
            )
        assertEquals(entityStatements, readAuthorityHints(entityConfigurationStatement, mockEngine))
    }
}
