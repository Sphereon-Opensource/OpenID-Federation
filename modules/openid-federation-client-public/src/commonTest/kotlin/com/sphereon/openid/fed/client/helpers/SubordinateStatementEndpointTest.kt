package com.sphereon.openid.fed.client.helpers

import kotlin.test.Test
import kotlin.test.assertEquals

/** OpenID Federation 1.1 sections 5.1.1 and 8.1.1: the fetch request keeps the endpoint's query and form-encodes sub. */
class SubordinateStatementEndpointTest {

    @Test
    fun subIsFormEncoded() {
        assertEquals(
            "https://ta.example/fetch?sub=https%3A%2F%2Fleaf.example%2Fa%2Bb%3Fx%3D1",
            getSubordinateStatementEndpoint("https://ta.example/fetch", "https://leaf.example/a+b?x=1"),
        )
    }

    @Test
    fun percentEscapesInSubAreEncodedAgain() {
        assertEquals(
            "https://ta.example/fetch?sub=https%3A%2F%2Fleaf.example%2Fr%25C3%25A9",
            getSubordinateStatementEndpoint("https://ta.example/fetch", "https://leaf.example/r%C3%A9"),
        )
    }

    @Test
    fun queryComponentOfTheFetchEndpointIsKept() {
        assertEquals(
            "https://ta.example/federation?op=fetch&tenant=a&sub=https%3A%2F%2Fleaf.example",
            getSubordinateStatementEndpoint("https://ta.example/federation?op=fetch&tenant=a", "https://leaf.example"),
        )
    }

    @Test
    fun anExistingSubParameterIsReplaced() {
        // source_endpoint values are sometimes published as the full fetch URL of that statement.
        assertEquals(
            "https://ta.example/fetch?tenant=a&sub=https%3A%2F%2Fleaf.example",
            getSubordinateStatementEndpoint("https://ta.example/fetch?sub=https%3A%2F%2Fold.example&tenant=a", "https://leaf.example"),
        )
    }
}
