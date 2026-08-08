package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DcqlRefinementTest {

    @Test
    fun equalIsEqualKind() {
        val q = JsonObject(mapOf("id" to JsonPrimitive("q1")))
        val match = DcqlRefinement.match(q, JsonArray(listOf(q)))
        assertEquals(DcqlRefinement.MatchKind.EQUAL, match.kind)
        assertEquals(0, match.registeredIndex)
    }

    @Test
    fun metaVctSubsetIsRefinement() {
        val registered = cred(
            id = "mdl",
            format = "mso_mdoc",
            meta = JsonObject(
                mapOf(
                    "doctype_value" to JsonPrimitive("org.iso.18013.5.1.mDL"),
                    "vct_values" to JsonArray(
                        listOf(JsonPrimitive("A"), JsonPrimitive("B"), JsonPrimitive("C"))
                    ),
                )
            ),
            claims = listOf(pathClaim("family_name"), pathClaim("given_name")),
        )
        val request = cred(
            id = "mdl",
            format = "mso_mdoc",
            meta = JsonObject(
                mapOf(
                    "doctype_value" to JsonPrimitive("org.iso.18013.5.1.mDL"),
                    "vct_values" to JsonArray(listOf(JsonPrimitive("A"))),
                )
            ),
            claims = listOf(pathClaim("family_name")),
        )
        val regQuery = JsonObject(mapOf("credentials" to JsonArray(listOf(registered))))
        val reqQuery = JsonObject(mapOf("credentials" to JsonArray(listOf(request))))
        val match = DcqlRefinement.match(reqQuery, JsonArray(listOf(regQuery)))
        assertEquals(DcqlRefinement.MatchKind.REFINEMENT, match.kind)
    }

    @Test
    fun metaVctExpansionRejected() {
        val registered = cred(
            id = "mdl",
            format = "mso_mdoc",
            meta = JsonObject(
                mapOf("vct_values" to JsonArray(listOf(JsonPrimitive("A"))))
            ),
        )
        val request = cred(
            id = "mdl",
            format = "mso_mdoc",
            meta = JsonObject(
                mapOf("vct_values" to JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("Z"))))
            ),
        )
        assertFalse(
            DcqlRefinement.isRefinementOf(
                JsonObject(mapOf("credentials" to JsonArray(listOf(request)))),
                JsonObject(mapOf("credentials" to JsonArray(listOf(registered)))),
            )
        )
    }

    @Test
    fun claimSetsSubsetIsRefinement() {
        val registered = cred(
            id = "pid",
            format = "dc+sd-jwt",
            claims = listOf(
                pathClaim("given_name", claimId = "gn"),
                pathClaim("family_name", claimId = "fn"),
                pathClaim("birthdate", claimId = "bd"),
            ),
            claimSets = listOf(
                JsonArray(listOf(JsonPrimitive("gn"), JsonPrimitive("fn"), JsonPrimitive("bd"))),
                JsonArray(listOf(JsonPrimitive("gn"), JsonPrimitive("fn"))),
            ),
        )
        val request = cred(
            id = "pid",
            format = "dc+sd-jwt",
            claims = listOf(
                pathClaim("given_name", claimId = "gn"),
                pathClaim("family_name", claimId = "fn"),
            ),
            claimSets = listOf(
                JsonArray(listOf(JsonPrimitive("gn"), JsonPrimitive("fn"))),
            ),
        )
        assertTrue(
            DcqlRefinement.isRefinementOf(
                JsonObject(mapOf("credentials" to JsonArray(listOf(request)))),
                JsonObject(mapOf("credentials" to JsonArray(listOf(registered)))),
            )
        )
    }

    @Test
    fun droppingCredentialIsRefinement() {
        val reg = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(
                        cred(id = "a", format = "dc+sd-jwt"),
                        cred(id = "b", format = "dc+sd-jwt"),
                    )
                )
            )
        )
        val req = JsonObject(
            mapOf(
                "credentials" to JsonArray(
                    listOf(cred(id = "a", format = "dc+sd-jwt"))
                )
            )
        )
        val match = DcqlRefinement.match(req, JsonArray(listOf(reg)))
        assertEquals(DcqlRefinement.MatchKind.REFINEMENT, match.kind)
    }

    private fun pathClaim(vararg path: String, claimId: String? = null): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "path" to JsonArray(path.map { JsonPrimitive(it) })
        )
        if (claimId != null) map["id"] = JsonPrimitive(claimId)
        return JsonObject(map)
    }

    private fun cred(
        id: String,
        format: String,
        meta: JsonObject? = null,
        claims: List<JsonObject> = emptyList(),
        claimSets: List<JsonArray> = emptyList(),
    ): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "format" to JsonPrimitive(format),
        )
        if (meta != null) map["meta"] = meta
        if (claims.isNotEmpty()) map["claims"] = JsonArray(claims)
        if (claimSets.isNotEmpty()) map["claim_sets"] = JsonArray(claimSets)
        return JsonObject(map)
    }
}
