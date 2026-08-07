package com.sphereon.openid.fed.integration.tests

import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.openapi.models.CreateAccount
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration tests for L2 session alignment + legacy multi-entity isolation.
 *
 * Requires a running admin server (default http://localhost:8081) with
 * `oidf.identity.mode=legacy` (default) and preferably
 * `oidf.identity.session.alignment=account` (L2 default).
 *
 * Validates that:
 * - Distinct accounts can manage independent key sets via X-Account-Username
 * - Root account remains usable without header (defaults to root)
 * - Account CRUD still works after L2 session wiring
 */
class SessionAlignmentApiTest {

    private lateinit var client: HttpClient
    private lateinit var baseUrl: String
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val accountA = "l2-session-a-${System.currentTimeMillis()}"
    private val accountB = "l2-session-b-${System.currentTimeMillis()}"

    @BeforeTest
    fun setup() {
        baseUrl = System.getenv("ADMIN_SERVER_BASE_URL") ?: "http://localhost:8081"
        client = HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    @AfterTest
    fun tearDown() = runTest {
        try {
            deleteAccount(accountA)
            deleteAccount(accountB)
        } finally {
            client.close()
        }
    }

    @Test
    fun `health endpoint is reachable`() = runTest {
        try {
            val response = client.get("$baseUrl/health")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        } catch (e: Exception) {
            fail("Admin server not reachable at $baseUrl: ${e.message}")
        }
    }

    @Test
    fun `two accounts have isolated key stores under L2 session alignment`() = runTest {
        try {
            createAccount(accountA)
            createAccount(accountB)

            // Create a key in account A only
            val createA = client.post("$baseUrl/keys") {
                contentType(ContentType.Application.Json)
                header("X-Account-Username", accountA)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Created, createA.status, createA.bodyAsText())
            val keyA = json.decodeFromString<TenantJwk>(createA.bodyAsText())

            // Account A lists the key
            val listA = client.get("$baseUrl/keys") {
                header("X-Account-Username", accountA)
            }
            assertEquals(HttpStatusCode.OK, listA.status, listA.bodyAsText())
            assertTrue(
                listA.bodyAsText().contains(keyA.id),
                "Account A should list its own key. Body: ${listA.bodyAsText()}",
            )

            // Account B must not see account A's key (tenant isolation)
            val listB = client.get("$baseUrl/keys") {
                header("X-Account-Username", accountB)
            }
            assertEquals(HttpStatusCode.OK, listB.status, listB.bodyAsText())
            assertFalse(
                listB.bodyAsText().contains(keyA.id),
                "Account B must not see Account A's key (L2/entity isolation). Body: ${listB.bodyAsText()}",
            )

            // Create key in B; A still should not list B's key
            val createB = client.post("$baseUrl/keys") {
                contentType(ContentType.Application.Json)
                header("X-Account-Username", accountB)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Created, createB.status, createB.bodyAsText())
            val keyB = json.decodeFromString<TenantJwk>(createB.bodyAsText())

            val listA2 = client.get("$baseUrl/keys") {
                header("X-Account-Username", accountA)
            }
            assertEquals(HttpStatusCode.OK, listA2.status)
            assertFalse(
                listA2.bodyAsText().contains(keyB.id),
                "Account A must not see Account B's key. Body: ${listA2.bodyAsText()}",
            )
            assertTrue(listA2.bodyAsText().contains(keyA.id))
        } catch (e: Exception) {
            fail("L2 isolation test failed: ${e.message}")
        }
    }

    @Test
    fun `root account keys work without X-Account-Username header`() = runTest {
        try {
            val create = client.post("$baseUrl/keys") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            // Default header → root; L2 session uses root Account.id
            assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
            val key = json.decodeFromString<TenantJwk>(create.bodyAsText())

            val listRoot = client.get("$baseUrl/keys") {
                header("X-Account-Username", "root")
            }
            assertEquals(HttpStatusCode.OK, listRoot.status, listRoot.bodyAsText())
            assertTrue(
                listRoot.bodyAsText().contains(key.id),
                "Root header should see key created without header. Body: ${listRoot.bodyAsText()}",
            )

            // Cleanup root key (best-effort)
            client.delete("$baseUrl/keys/${key.id}") {
                header("X-Account-Username", "root")
            }
        } catch (e: Exception) {
            fail("Root default-header test failed: ${e.message}")
        }
    }

    @Test
    fun `accounts list includes newly created L2 accounts`() = runTest {
        try {
            createAccount(accountA)
            val list = client.get("$baseUrl/accounts")
            assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
            assertTrue(
                list.bodyAsText().contains(accountA),
                "Accounts list should include $accountA. Body: ${list.bodyAsText()}",
            )
        } catch (e: Exception) {
            fail("Accounts list test failed: ${e.message}")
        }
    }

    private suspend fun createAccount(username: String) {
        val response = client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateAccount(
                    username = username,
                    identifier = "https://l2-test.example/$username",
                ),
            )
        }
        if (response.status != HttpStatusCode.Created && response.status != HttpStatusCode.Conflict) {
            fail("Failed to create account $username: ${response.status} ${response.bodyAsText()}")
        }
    }

    private suspend fun deleteAccount(username: String) {
        try {
            client.delete("$baseUrl/accounts") {
                header("X-Account-Username", username)
            }
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }
}
