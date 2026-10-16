package com.sphereon.openid.fed.interop.oidc

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** Replaces exactly one unsigned outer redirect_uri while retaining the captured signed JAR bytes. */
internal object OuterRedirectParameterControl {
    fun replace(requestUrl: String, expectedOriginal: String, replacement: String): String {
        val uri = URI(requestUrl)
        require(uri.scheme == "https" && uri.host == "sphereon.test" && uri.path == "/authorize")
        require(uri.rawFragment == null)
        val rawQuery = requireNotNull(uri.rawQuery)
        val parameters = rawQuery.split('&', ignoreCase = false, limit = Int.MAX_VALUE)
        fun parameterName(parameter: String): String = URLDecoder.decode(parameter.substringBefore('='), UTF_8)

        val redirects = parameters.indices.filter { parameterName(parameters[it]) == "redirect_uri" }
        val signedRequests = parameters.indices.filter { parameterName(parameters[it]) == "request" }
        require(redirects.size == 1) { "authorization URL must carry exactly one outer redirect_uri" }
        require(signedRequests.size == 1) { "authorization URL must carry exactly one signed Request Object" }
        val redirectIndex = redirects.single()
        val requestIndex = signedRequests.single()
        val redirectSegment = parameters[redirectIndex]
        require(redirectSegment.contains('='))
        require(URLDecoder.decode(redirectSegment.substringAfter('='), UTF_8) == expectedOriginal) {
            "outer redirect_uri differs from the registered callback"
        }

        val signedRequestSegment = parameters[requestIndex]
        val signedRequest = URLDecoder.decode(signedRequestSegment.substringAfter('='), UTF_8)
        require(signedRequest.split('.').size == 3) { "request parameter is not a compact signed JAR" }

        val encodedReplacement = URLEncoder.encode(replacement, UTF_8)
        val changed = parameters.toMutableList()
        changed[redirectIndex] = "${redirectSegment.substringBefore('=')}=$encodedReplacement"
        require(changed[requestIndex] == signedRequestSegment)
        require(changed.indices.all { index -> index == redirectIndex || changed[index] == parameters[index] })

        val changedUrl = requestUrl.substringBefore('?') + "?" + changed.joinToString("&")
        val changedUri = URI(changedUrl)
        val changedParameters = requireNotNull(changedUri.rawQuery).split('&', ignoreCase = false, limit = Int.MAX_VALUE)
        require(changedParameters.size == parameters.size)
        require(changedParameters[requestIndex] == signedRequestSegment)
        require(URLDecoder.decode(changedParameters[redirectIndex].substringAfter('='), UTF_8) == replacement)
        return changedUrl
    }
}
