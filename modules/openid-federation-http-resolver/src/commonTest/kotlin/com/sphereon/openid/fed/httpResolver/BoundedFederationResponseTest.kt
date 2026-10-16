package com.sphereon.openid.fed.httpResolver

import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.openid.fed.core.cache.OidfCache
import com.sphereon.openid.fed.httpResolver.config.DefaultHttpResolverConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BoundedFederationResponseTest {
    @Test
    fun productionMapperStreamsBoundsChecksMediaAndDoesNotCacheRefusals() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.OK, "application/entity-statement+jwt", ByteArray(MAX_FEDERATION_RESPONSE_BYTES + 1) { 120 }),
            Triple(HttpStatusCode.OK, "text/plain", "jwt".encodeToByteArray()),
            Triple(HttpStatusCode.Found, "application/entity-statement+jwt", "redirect".encodeToByteArray()),
            Triple(HttpStatusCode.OK, "application/entity-statement+jwt", byteArrayOf(0xc3.toByte(), 0x28)),
        )
        for ((status, type, bytes) in cases) {
            var requests = 0
            val client = HttpClient(MockEngine { request ->
                requests++
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                respond(ByteReadChannel(bytes), status, headersOf(HttpHeaders.ContentType, type)) }) {
                followRedirects = false
            }
            val cache = OidfCache.createStringKeyCache(OidfCache.newManager(), CacheRequirements(namespace = "bounded-${status.value}-$type-${bytes.size}"),
                HttpMetadataCacheSerializers.stringValue)
            val resolver = HttpResolver(DefaultHttpResolverConfig(httpRetries = 3), client, cache, ::readBoundedFederationResponse)
            try {
                assertFailsWith<FederationHttpFetchException> { resolver.get("https://entity.example/document") }
                assertNull(cache.getApp("https://entity.example/document"))
                assertEquals(1, requests, "Protocol refusals must not trigger retry amplification")
            } finally { client.close() }
        }
    }

    @Test
    fun declaredOversizeIsRefusedAndValidJsonAndTypedJwtRemainAccepted() = runTest {
        for (type in listOf("application/json", "application/entity-statement+jwt", "application/jwk-set+jwt")) {
            val client = HttpClient(MockEngine { respond("payload", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, type)) })
            val cache = OidfCache.createStringKeyCache(OidfCache.newManager(), CacheRequirements(namespace = "valid-$type"), HttpMetadataCacheSerializers.stringValue)
            try { assertEquals("payload", HttpResolver(httpClient = client, cache = cache, responseMapper = ::readBoundedFederationResponse).get("https://entity.example/document")) }
            finally { client.close() }
        }
        val client = HttpClient(MockEngine { respond("small", HttpStatusCode.OK, headersOf(
            HttpHeaders.ContentType to listOf("application/entity-statement+jwt"),
            HttpHeaders.ContentLength to listOf((MAX_FEDERATION_RESPONSE_BYTES + 1).toString()))) })
        val cache = OidfCache.createStringKeyCache(OidfCache.newManager(), CacheRequirements(namespace = "declared-limit"), HttpMetadataCacheSerializers.stringValue)
        try { assertFailsWith<FederationHttpFetchException> { HttpResolver(httpClient = client, cache = cache, responseMapper = ::readBoundedFederationResponse).getJwt("https://entity.example/document") } }
        finally { client.close() }
    }
}
