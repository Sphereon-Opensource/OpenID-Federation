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


private const val baseUrl = "http://localhost:8081"

private val trustAnchor = Entity(
    username = "root"
)
private val intermediaryEntity = Entity(
    username = "intermediate"
)
private val leafEntity = Entity(
    username = "leaf"
)

data class Entity(
    var id: String? = null,
    var identifier: String? = null,
    var jwk: AccountJwk? = null,
    val username: String,
    var subordinates: MutableList<Entity> = mutableListOf(),
    var authorities: MutableList<Entity> = mutableListOf()
)

private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

class SetupConformanceTest {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            setupConformanceTest()
        }
    }
}

fun setupConformanceTest() {
    println("--------------------------------------------------------------------------------")
    println("Setting up conformance test data structure")
    configClient()

    trustAnchor
        .updateIdentifier()
        .createKey()
    intermediaryEntity
        .createAccount()
        .updateIdentifier()
        .createKey()
    leafEntity
        .createAccount()
        .updateIdentifier()
        .createKey()

    intermediaryEntity.addAuthority(trustAnchor)
    leafEntity.addAuthority(intermediaryEntity)

    trustAnchor.addSubordinate(intermediaryEntity)
    intermediaryEntity.addSubordinate(leafEntity)

    trustAnchor.publishEntityStatement()
    intermediaryEntity.publishEntityStatement()
    leafEntity.publishEntityStatement()
    println("--------------------------------------------------------------------------------")
}

private fun configClient() {
    client.config {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }
}

fun Entity.createAccount(): Entity {
    val username = this.username
    println("Creating account for: $username")

    val account: Account = runBlocking {
        client.post("$baseUrl/accounts") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("username", username)
            })
        }.body<Account>()
    }
    println("Account created: $account")
    this.id = account.id

    return this
}

fun Entity.updateIdentifier(): Entity {
    println("Updating identifier for: ${this.username}")
    val username = this.username

    val entityConfigurationStatement: EntityConfigurationStatement = runBlocking {
        client.get("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", username)
            }
        }.body<EntityConfigurationStatement>()
    }

    println("Identifier updated: ${entityConfigurationStatement.iss}")
    this.identifier = entityConfigurationStatement.iss

    return this
}

fun Entity.createKey(): Entity {
    println("Creating key for: ${this.username}")
    val username = this.username

    val key = runBlocking {
        client.post("$baseUrl/keys") {
            contentType(ContentType.Application.Json)

            setBody(buildJsonObject {
                put("use", "sig")
                put("alg", "ECDSA_SHA256")
            })
            headers {
                append("X-Account-Username", username)
            }
        }.body<AccountJwk>()
    }
    println("Key created: $key")
    this.jwk = key

    return this
}

fun Entity.addSubordinate(subordinate: Entity): Entity {
    println("Adding subordinate: ${subordinate.username} to: ${this.username}")

    val username = this.username

    val subordinateRelationship = runBlocking {
        client.post("$baseUrl/subordinates") {
            contentType(ContentType.Application.Json)

            setBody(buildJsonObject {
                put("identifier", subordinate.identifier)
            })
            headers {
                append("X-Account-Username", username)
            }
        }.body<Subordinate>()
    }
    println("Subordinate relationship created: $subordinateRelationship")

    runBlocking {
        client.post("$baseUrl/subordinates/${subordinateRelationship.id}/keys") {
            contentType(ContentType.Application.Json)
            setBody(subordinate.jwk)
            headers {
                append("X-Account-Username", username)
            }

        }.also {
            println(it.bodyAsText())
            println("Added key to subordinate: $it")
        }.bodyAsText()
    }

    runBlocking {
        client.post("$baseUrl/subordinates/${subordinateRelationship.id}/statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", username)
            }
        }.also {
            println("Published statement for subordinate: $it")
        }.bodyAsText()
    }

    return this
}

fun Entity.addAuthority(authority: Entity): Entity {
    println("Adding authority: ${authority.username} to: ${this.username}")

    val username = this.username

    runBlocking {
        client.post("$baseUrl/authority-hints") {
            contentType(ContentType.Application.Json)

            setBody(buildJsonObject {
                put("identifier", authority.identifier)
            })
            headers {
                append("X-Account-Username", username)
            }
        }.bodyAsText()
        println("Authority added: ${authority.username} to: ${username}")
    }

    return this
}

fun Entity.publishEntityStatement(): Entity {
    println("Publishing entity statement for: ${this.username}")
    val username = this.username

    runBlocking {
        client.post("$baseUrl/entity-statement") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Account-Username", username)
            }
        }.bodyAsText()
        println("Entity statement published for: ${username}")
    }

    return this
}
