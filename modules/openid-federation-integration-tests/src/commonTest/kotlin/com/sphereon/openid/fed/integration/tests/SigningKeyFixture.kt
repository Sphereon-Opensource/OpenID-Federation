package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.openapi.models.CreateKey
import com.sphereon.openid.fed.openapi.models.TenantJwk
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** Uses the real authenticated, revision-checked account signing-selection API. */
suspend fun selectAccountSigningKey(client: HttpClient, baseUrl: String, username: String, key: TenantJwk) {
    val current = client.get("$baseUrl/keys/signing-selection") {
        header("X-Account-Username", username)
    }
    assertEquals(HttpStatusCode.OK, current.status, current.bodyAsText())
    val revision = Json.parseToJsonElement(current.bodyAsText()).jsonObject.getValue("revision").jsonPrimitive.long
    val selected = client.put("$baseUrl/keys/signing-selection") {
        header("X-Account-Username", username)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("expectedRevision", revision)
            put("selectedKeyId", requireNotNull(key.id))
        })
    }
    assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())
}

suspend fun createAndSelectAccountSigningKey(client: HttpClient, baseUrl: String, username: String): TenantJwk {
    val created = client.post("$baseUrl/keys") {
        header("X-Account-Username", username)
        contentType(ContentType.Application.Json)
        setBody(CreateKey())
    }
    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    val key = created.body<TenantJwk>()
    selectAccountSigningKey(client, baseUrl, username, key)
    return key
}
