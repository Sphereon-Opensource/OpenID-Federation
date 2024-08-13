package com.sphereon.oid.fed.common.op

import com.sphereon.oid.fed.common.mapper.JsonMapper
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustChainValidationTest {

    private val entityStatementJwt = """
    eyJhbGciOiJSUzI1NiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0Iiwia2lkIjoiTnpiTHNYaDh1RENjZC02TU53WEY0V183bm9XWEZaQWZIa3ha
    c1JHQzlYcyJ9.eyJpc3MiOiJodHRwczovL2ZlaWRlLm5vIiwic3ViIjoiaHR0cHM6Ly9udG51Lm5vIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTY
    yOTgwMjIsImp3a3MiOnsia2V5cyI6W3sia3R5IjoiUlNBIiwiYWxnIjoiUlMyNTYiLCJ1c2UiOiJzaWciLCJraWQiOiJOemJMc1hoOHVEQ2NkLTZNTnd
    YRjRXXzdub1dYRlpBZkhreFpzUkdDOVhzIiwibiI6InBuWEJPdXNFQU51dWc2ZXdlemI5Sl8uLi4iLCJlIjoiQVFBQiJ9XX0sIm1ldGFkYXRhIjp7Im9
    wZW5pZF9wcm92aWRlciI6eyJpc3N1ZXIiOiJodHRwczovL250bnUubm8iLCJvcmdhbml6YXRpb25fbmFtZSI6Ik5UTlUifSwib2F1dGhfY2xpZW50Ijp
    7Im9yZ2FuaXphdGlvbl9uYW1lIjoiTlROVSJ9fSwibWV0YWRhdGFfcG9saWN5Ijp7Im9wZW5pZF9wcm92aWRlciI6eyJpZF90b2tlbl9zaWduaW5nX2F
    sZ192YWx1ZXNfc3VwcG9ydGVkIjp7InN1YnNldF9vZiI6WyJSUzI1NiIsIlJTMzg0IiwiUlM1MTIiXX0sIm9wX3BvbGljeV91cmkiOnsicmVnZXhwIjo
    iXmh0dHBzOi8vW1xcdy1dK1xcLmV4YW1wbGVcXC5jb20vW1xcdy1dK1xcLmh0bWwifX0sIm9hdXRoX2NsaWVudCI6eyJncmFudF90eXBlcyI6eyJvbmV
    fb2YiOlsiYXV0aG9yaXphdGlvbl9jb2RlIiwiY2xpZW50X2NyZWRlbnRpYWxzIl19fX0sImNvbnN0cmFpbnRzIjp7Im1heF9wYXRoX2xlbmd0aCI6Mn0
    sImNyaXQiOlsianRpIl0sIm1ldGFkYXRhX3BvbGljeV9jcml0IjpbInJlZ2V4cCJdLCJzb3VyY2VfZW5kcG9pbnQiOiJodHRwczovL2ZlaWRlLm5vL2Z
    lZGVyYXRpb25fYXBpL2ZldGNoIiwianRpIjoiN2wybG5jRmRZNlNsaE5pYSJ9.cb0Xxqskr77xvJcF_rOfe1LiDjI-F9W-M7TMqEAJSYVrxEZAcaQfrL
    wIyeyh_gE3_KVt1bBpdod1XPG9Eied5oqwuf6_TPjrtKI6W9pdNwzXExwDSjUCk726UIxhOkakViBFrtptxB0fz_JCwtqOHP7fhdcJY2KhpUNJQjlJkl
    00Bh83MwYuTcYwcbPkr9zl3hf38dDtziZgqOs7Ig9UZUw4FCajC4fcho88NIoBDM3XajfiIblDKd8B-sSJUz8WAJxSzWnD9sLPpbwe5qjOwSms3gSiTg
    Jxl_8N7QV9-bSzEshSU0XvpMPBfP8Hl3RJkgJ7a9Ng6PKDa0eqFLLLQA
    """.replace("[\\n\\s]+".toRegex(), "")

    private val mockEngine = MockEngine { request ->
        when(request.url) {
            Url("https://www.example.com/.well-known/openid-federation") -> respond(entityStatementJwt)
            Url("https://www.example.com/entity-statement") -> respond(entityStatementJwt)
            else -> error("Unhandled ${request.url}")
        }
    }

    @Test
    fun readAuthorityHintsTest() = runTest {
        val entityStatements = listOf(JsonMapper().mapEntityStatement(entityStatementJwt))
        assertEquals(entityStatements, readAuthorityHints(entityStatementJwt, mockEngine))
    }
}