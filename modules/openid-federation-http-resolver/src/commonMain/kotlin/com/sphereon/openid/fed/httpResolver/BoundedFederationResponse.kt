package com.sphereon.openid.fed.httpResolver

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable

private val FEDERATION_MEDIA_TYPES = setOf(
    "application/entity-statement+jwt", "application/jwk-set+jwt", "application/jwk-set+json",
    "application/trust-mark+jwt", "application/trust-mark-status-response+jwt", "application/resolve-response+jwt",
    "application/json", "application/trust-chain+json",
)
const val MAX_FEDERATION_RESPONSE_BYTES = 1_048_576

/** Called inside HttpStatement.execute so an untrusted body is never buffered before its bound is enforced. */
suspend fun readBoundedFederationResponse(response: HttpResponse): String {
    val channel = response.bodyAsChannel()
    fun refuse(reason: String): Nothing {
        channel.cancel(null)
        throw FederationHttpFetchException(reason)
    }
    if (response.status.value != 200) refuse("Federation document requires HTTP 200")
    val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
    if (type !in FEDERATION_MEDIA_TYPES) refuse("Unsupported federation document content type")
    if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > MAX_FEDERATION_RESPONSE_BYTES)
        refuse("Federation response exceeds $MAX_FEDERATION_RESPONSE_BYTES bytes")
    val buffer = ByteArray(8192)
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (count == 0) continue
        size += count
        if (size > MAX_FEDERATION_RESPONSE_BYTES) refuse("Federation response exceeds $MAX_FEDERATION_RESPONSE_BYTES bytes")
        chunks += buffer.copyOf(count)
    }
    val bytes = ByteArray(size)
    var offset = 0
    for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
    return try { bytes.decodeToString(throwOnInvalidSequence = true) }
    catch (_: Exception) { refuse("Federation response is not valid UTF-8") }
}
