package com.sphereon.oid.fed.httpResolver

import com.sphereon.oid.fed.cache.Cache
import com.sphereon.oid.fed.cache.CacheOptions
import com.sphereon.oid.fed.cache.CacheStrategy
import com.sphereon.oid.fed.httpResolver.config.DefaultHttpResolverConfig
import com.sphereon.oid.fed.httpResolver.config.HttpResolverConfig
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay

private val logger = HttpResolverConst.LOG

class HttpResolver<V : Any>(
    private val config: HttpResolverConfig = DefaultHttpResolverConfig(),
    private val httpClient: HttpClient,
    private val cache: Cache<String, HttpMetadata<V>>,
    private val responseMapper: suspend (HttpResponse) -> V
) {
    private suspend fun fetchWithRetry(url: String, attempt: Int = 1): HttpResponse {
        try {
            logger.debug("Attempting HTTP request for $url (attempt $attempt/${config.httpRetries})")
            return httpClient.get(url) {
                config.apply {
                    if (enableHttpCaching) {
                        headers {
                            // Add conditional request headers if we have them
                            cache.getIfAvailable(url, CacheOptions())?.let { metadata ->
                                metadata.etag?.let { etag ->
                                    logger.debug("Adding If-None-Match header: $etag")
                                    append(HttpHeaders.IfNoneMatch, etag)
                                }
                                metadata.lastModified?.let { lastModified ->
                                    logger.debug("Adding If-Modified-Since header: $lastModified")
                                    append(HttpHeaders.IfModifiedSince, lastModified)
                                }
                            }
                        }
                    }
                    timeout {
                        requestTimeoutMillis = httpTimeoutMs
                    }
                }
            }
        } catch (e: Exception) {
            if (attempt < config.httpRetries) {
                val delayMs = 1000L * (1 shl (attempt - 1))
                logger.warn("HTTP request failed for $url, will retry after ${delayMs}ms (attempt $attempt): ${e.message ?: "Unknown error"}")
                delay(delayMs)
                return fetchWithRetry(url, attempt + 1)
            }
            logger.error("HTTP request failed for $url after $attempt attempts", e)
            throw e
        }
    }

    private suspend fun fetchFromRemote(url: String): HttpMetadata<V> {
        logger.debug("Fetching from remote: $url")
        val response = fetchWithRetry(url)
        val value = responseMapper(response)

        return HttpMetadata(
            value = value,
            etag = if (config.enableHttpCaching) response.headers[HttpHeaders.ETag] else null,
            lastModified = if (config.enableHttpCaching) response.headers[HttpHeaders.LastModified] else null
        )
    }

    suspend fun get(url: String, options: CacheOptions = CacheOptions()): String {
        logger.debug("Getting resource from $url using strategy ${options.strategy}")

        if (options.strategy == CacheStrategy.CACHE_ONLY) {
            logger.debug("Using cache-only strategy for $url")
            return cache.getIfAvailable(url, options)?.value?.toString()
                ?: throw IllegalStateException("No cached value available")
        }

        if (options.strategy == CacheStrategy.FORCE_REMOTE) {
            logger.debug("Using force-remote strategy for $url")
            val metadata = fetchFromRemote(url)
            cache.put(url) { metadata }
            return metadata.value.toString()
        }

        // CACHE_FIRST strategy
        logger.debug("Using cache-first strategy for $url")
        return cache.getOrPut(url, { fetchFromRemote(url) }, options)?.value?.toString()
            ?: throw IllegalStateException("Failed to get or put value in cache")
    }
}