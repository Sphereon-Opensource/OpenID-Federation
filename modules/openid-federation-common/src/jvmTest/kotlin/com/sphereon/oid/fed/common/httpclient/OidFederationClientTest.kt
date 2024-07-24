package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.openapi.models.EntityStatement
import com.sphereon.oid.fed.openapi.models.FederationEntityMetadata
import com.sphereon.oid.fed.openapi.models.Metadata
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

class OidFederationClientTest {

    private val entityStatement = EntityStatement(
        iss = "test_iss",
        sub = "test_sub",
        metadata = Metadata(
            federationEntity = FederationEntityMetadata(
                federationListEndpoint = "http://www.example.com/list",
                federationResolveEndpoint = "http://www.example.com/resolve",
                organizationName = "test organization",
                homepageUri = "http://www.example.com",
                federationFetchEndpoint = "http://www.example.com/fetch",
            )
        )
    )

    private val mockEngine = MockEngine {
        respond(
            content = ByteReadChannel(Json.encodeToString(entityStatement)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )
    }

    @Test
    fun testGetEntityStatement() {
        runBlocking {
            val client = OidFederationClient(mockEngine)
            val response = client.fetchEntityStatement("test_iss", HttpMethod.Get)
            assert(response == entityStatement)
        }
    }
}