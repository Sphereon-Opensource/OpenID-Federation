package com.sphereon.openid.fed.integration.tests.platform

import com.sphereon.openid.fed.integration.tests.adminTestBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.TestInstance
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ACCOUNT mode without any OIDC provider beyond the fixture's token issuer: a legacy client reaches an account whose
 * records were written by an earlier release (a key row with a lower case provider id and no signing key selection),
 * and an unauthenticated or non-allow-listed client does not.
 *
 * The rows are provisioned through JDBC in the shape migration 29 leaves them in. The suites in the persistence module
 * prove the migration itself, this one proves the running admin server serves what it produced.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigratedAccountContractTest {
    private class MigratedAccount(val username: String, val accountId: String, val identifier: String, val oldKid: String, val newKid: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl: String get() = adminTestBaseUrl()
    private lateinit var client: HttpClient

    private val migrated: MigratedAccount by lazy { provision() }

    @BeforeTest
    fun setup() {
        // The external fixture overwrites the process wide identity configuration (mode, allow-list) when another class
        // booted it first, so this class asserts the account mode configuration it depends on before every test.
        val shared = AccountInProcessFixture.shared
        AccountInProcessFixture.seedAccountConfig(issuerUri = shared.oauthAs.issuer, audience = IdkOauth2AsFixture.DEFAULT_AUDIENCE)
        client = HttpClient()
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private fun publicJwk(kid: String) =
        """{"kty":"EC","crv":"P-256","kid":"$kid","alg":"ES256","use":"sig",""" +
            """"x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}"""

    /** An account with an older and a newer active key, as an official release left it: no signing key selection row. */
    private fun provision(): MigratedAccount {
        val stamp = System.currentTimeMillis()
        val username = "migrated-$stamp"
        val identifier = "https://legacy.example/$username"
        AccountInProcessFixture.openDatabaseConnection().use { connection ->
            val accountId = connection.prepareStatement("INSERT INTO Account (username, identifier) VALUES (?, ?) RETURNING id::text").use { statement ->
                statement.setString(1, username)
                statement.setString(2, identifier)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
            }
            fun key(kid: String, created: String) = connection.prepareStatement(
                "INSERT INTO Jwk (account_id, alg, kid, kms, kms_key_ref, key, created_at) VALUES (?::uuid, 'ES256', ?, 'memory', ?, ?, ?::timestamp)",
            ).use { statement ->
                statement.setString(1, accountId)
                statement.setString(2, kid)
                statement.setString(3, "ref-$kid")
                statement.setString(4, publicJwk(kid))
                statement.setString(5, created)
                statement.executeUpdate()
            }
            key("old-$stamp", "2025-01-01 00:00:00")
            key("new-$stamp", "2025-03-01 00:00:00")
            return MigratedAccount(username, accountId, identifier, "old-$stamp", "new-$stamp")
        }
    }

    private fun selectionRows(): Int = AccountInProcessFixture.openDatabaseConnection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM AccountSigningKey WHERE account_id = ?::uuid").use { statement ->
            statement.setString(1, migrated.accountId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
    }

    private suspend fun get(path: String, token: String? = AccountInProcessFixture.shared.defaultAccessToken(), username: String? = null): HttpResponse =
        client.get("$baseUrl$path") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            username?.let { header("X-Account-Username", it) }
        }

    private fun JsonElement.objectsWith(member: String): List<JsonObject> = when (this) {
        is JsonObject -> listOfNotNull(takeIf { member in it }) + values.flatMap { it.objectsWith(member) }
        is JsonArray -> flatMap { it.objectsWith(member) }
        else -> emptyList()
    }

    @Test
    fun shouldListTheMigratedAccountWithItsStoredIdentifierForAnAllowListedLegacyClient() = runTest {
        val response = get("/accounts")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val account = json.parseToJsonElement(response.bodyAsText()).objectsWith("username")
            .single { it.getValue("username").jsonPrimitive.content == migrated.username }
        assertEquals(migrated.identifier, account.getValue("identifier").jsonPrimitive.content)
        assertEquals(migrated.accountId, account.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun shouldSignAMigratedAccountWithItsNewestActiveKeyAlthoughNoSelectionWasStored() = runTest {
        val node = get("/admin/v1", username = migrated.username)
        assertEquals(HttpStatusCode.OK, node.status, node.bodyAsText())
        val body = json.parseToJsonElement(node.bodyAsText()).jsonObject
        assertEquals(migrated.identifier, body.getValue("entity_id").jsonPrimitive.content)
        assertEquals(migrated.newKid, body.getValue("signing_kid").jsonPrimitive.content)
        assertEquals(0, selectionRows(), "reading the node must not store a signing key selection")

        val keys = get("/admin/v1/keys", username = migrated.username)
        assertEquals(HttpStatusCode.OK, keys.status, keys.bodyAsText())
        val byKid = json.parseToJsonElement(keys.bodyAsText()).objectsWith("signing").associateBy { it.getValue("kid").jsonPrimitive.content }
        assertEquals(setOf(migrated.oldKid, migrated.newKid), byKid.keys, "the header selects this account, so only its two keys are listed")
        assertEquals(false, byKid.getValue(migrated.oldKid).getValue("signing").jsonPrimitive.boolean)
        assertEquals(true, byKid.getValue(migrated.newKid).getValue("signing").jsonPrimitive.boolean)
    }

    @Test
    fun shouldDenyALegacyClientThatPresentsNoToken() = runTest {
        assertEquals(HttpStatusCode.Unauthorized, get("/admin/v1/keys", token = null, username = migrated.username).status)
        assertEquals(HttpStatusCode.Unauthorized, get("/accounts", token = null).status)
    }

    @Test
    fun shouldDenyAnEntityHeaderFromAPrincipalOffTheAllowList() = runTest {
        val outsider = AccountInProcessFixture.shared.oauthAs.mintAccessToken(tenantId = null, subject = "not-on-header-allow-list")
        val response = get("/admin/v1/keys", token = outsider, username = migrated.username)
        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    @Test
    fun shouldAnswerNotFoundForAnEntityHeaderThatNamesNoAccount() = runTest {
        val response = get("/admin/v1/keys", username = "no-such-account-${System.currentTimeMillis()}")
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().trimStart().startsWith("{"), "the unknown account answer is JSON: ${response.bodyAsText()}")
    }
}
