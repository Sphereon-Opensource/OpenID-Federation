package com.sphereon.oid.fed.conformance

import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.Subordinate
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Configure the HTTP client for making requests.
private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

// Data class to hold subordinate account attributes.
data class SubordinateAttr(
    var id: String? = null,
    var subordinateId: String? = null,
    var identifier: String? = null,
    var jwk: AccountJwk? = null,
    val username: String,
)

private const val baseUrl = "http://localhost:8081" // Base URL for the admin server.
private val subordinate1 = SubordinateAttr(
    username = "subordinate1"
)
private val subordinate2 = SubordinateAttr(
    username = "subordinate2"
)

private var trustAnchorIdentifier: String? = null

class SetupConformanceTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            setupConformanceTest()
        }
    }
}

fun setupConformanceTest() {
    println("Setting up conformance test data structure")
    client.config {
        install(ContentNegotiation) {
            json(Json { // Reconfigure the client with JSON settings.
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    // Get the Trust Anchor identifier
    val entityStatement = runBlocking {
        client.get("$baseUrl/entity-statement").body<EntityConfigurationStatement>()
    }

    trustAnchorIdentifier = entityStatement.iss


    // Create a key for the Trust Anchor (root) Account.
    val key = runBlocking {
        client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("use", "sig")
                put("alg", "ECDSA_SHA256")
            })
        }.body<String>()
    }
    println("Created key for root account: $key")

    // Create Subordinate Accounts.
    val subordinate1Account: Account = runBlocking {
        client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("username", subordinate1.username)
            })
        }.body()
    }
    println("Created subordinate 1 account: ${subordinate1Account}")

    subordinate1.id = subordinate1Account.id

    val subordinate2Account: Account = runBlocking {
        client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("username", subordinate2.username)
            })
        }.body()
    }
    println("Created subordinate 2 account: ${subordinate2Account}")
    subordinate2.id = subordinate2Account.id

    // Get the subordinate1 identifier from the Entity Statement.
    runBlocking {
        client.get("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", subordinate1.username)
            }
        }.body<EntityConfigurationStatement>().let {
            subordinate1.identifier = it.iss
        }
    }

    // Get the subordinate2 identifier from the Entity Statement.
    runBlocking {
        client.get("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", subordinate2.username)
            }
        }.body<EntityConfigurationStatement>().let {
            subordinate2.identifier = it.iss
        }
    }


    // Add the subordinates identifiers as subordinates to the trust anchor.
    val subordinate1Relationship: Subordinate = runBlocking {
        client.post("$baseUrl/subordinates") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("identifier", subordinate1.identifier)
            })
        }.body<Subordinate>()
            .also {
                subordinate1.subordinateId = it.id
            }
    }

    subordinate1.subordinateId = subordinate1Relationship.id
    println("Created subordinate 1 relationship: $subordinate1Relationship")

    val subordinate2Relationship: Subordinate = runBlocking {
        client.post("$baseUrl/subordinates") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("identifier", subordinate2.identifier)
            })
        }.body<Subordinate>()
            .also {
                subordinate2.subordinateId = it.id
            }
    }

    subordinate2.subordinateId = subordinate2Relationship.id
    println("Created subordinate 2 relationship: $subordinate2Relationship")

    // Create Keys for Subordinates.
    runBlocking {
        client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)

            setBody(buildJsonObject {
                put("use", "sig")
                put("alg", "ECDSA_SHA256")
            })
            headers {
                append("X-Account-Username", subordinate1.username)
            }
        }.body<AccountJwk>()
            .also {
                println("Created key for subordinate 1: $it")
                subordinate1.jwk = it
            }
    }

    runBlocking {
        client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)

            setBody(buildJsonObject {
                put("use", "sig")
                put("alg", "ECDSA_SHA256")
            })
            headers {
                append("X-Account-Username", subordinate2.username)
            }
        }.body<AccountJwk>()
            .also {
                println("Created key for subordinate 2: $it")
                subordinate2.jwk = it
            }
    }

    // Add Trust Anchor Identifier as Authority Hint to Subordinates.
    runBlocking {
        client.post("$baseUrl/authority-hints") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("identifier", trustAnchorIdentifier)
            })
            headers {
                append("X-Account-Username", subordinate1.username)
            }
        }.body<String>()
            .also {
                println("Added authority hint to subordinate 1: $it")
            }
    }

    runBlocking {
        client.post("$baseUrl/authority-hints") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("identifier", trustAnchorIdentifier)
            })
            headers {
                append("X-Account-Username", subordinate2.username)
            }
        }.body<String>()
            .also {
                println("Added authority hint to subordinate 2: $it")
            }
    }

    // Publish Entity Statements for Subordinates.
    runBlocking {
        client.post("$baseUrl/subordinates/${subordinate1.subordinateId}/statement") {
            contentType(ContentType.Application.Json)
        }.body<String>()
            .also {
                println("Published entity statement for subordinate 1: $it")
            }
    }

    runBlocking {
        client.post("$baseUrl/subordinates/${subordinate2.subordinateId}/statement") {
            contentType(ContentType.Application.Json)
        }.body<String>()
            .also {
                println("Published entity statement for subordinate 2: $it")
            }
    }

    // Add subordinate keys to subordinates.
    runBlocking {
        client.post("$baseUrl/subordinates/${subordinate1.subordinateId}/keys") {
            contentType(ContentType.Application.Json)
            setBody(subordinate1.jwk)
        }.also {
            println(it.bodyAsText())
            println("Added key to subordinate 1: $it")
        }
    }

    runBlocking {
        client.post("$baseUrl/subordinates/${subordinate2.subordinateId}/keys") {
            contentType(ContentType.Application.Json)
            setBody(subordinate2.jwk)
        }.also {
            println("Added key to subordinate 2: $it")
        }
    }

    // Publish subordinate statements.
    runBlocking {
        client.post("$baseUrl/subordinates/${subordinate1.subordinateId}/statement") {
            contentType(ContentType.Application.Json)
        }.also {
            println("Published statement for subordinate 1: $it")
        }
    }

    runBlocking {
        client.post("$baseUrl/subordinates/${subordinate2.subordinateId}/statement") {
            contentType(ContentType.Application.Json)
        }.also {
            println("Published statement for subordinate 2: $it")
        }
    }

    // Publish the Trust Anchor entity statement.
    runBlocking {
        client.post("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
        }
    }

    // Publish the subordinate1 entity statement
    runBlocking {
        client.post("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", subordinate1.username)
            }
        }
    }

    // Publish the subordinate2 entity statement
    runBlocking {
        client.post("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", subordinate2.username)
            }
        }
    }
}
