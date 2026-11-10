package com.sphereon.openid.fed.services.command

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Whether a stored statement is still live, judged from its `exp` claim alone.
 *
 * Releases up to 0.20.x signed statements with a JOSE header that has no `alg`. A full decode rejects such a header, so judging
 * liveness through it dropped the migrated statement and the public endpoint answered 404 although the row is intact. The payload
 * is all that is read here: the signature was made when the statement was stored and is not re-verified when it is served. `exp`
 * must be a JSON number, a quoted "2000" is not a NumericDate and counts as not live.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun isStoredStatementLive(compact: String, nowSeconds: Long): Boolean = runCatching {
    val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(compact.split(".")[1]).decodeToString()
    (Json.parseToJsonElement(payload).jsonObject["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.let { it > nowSeconds } == true
}.getOrDefault(false)
