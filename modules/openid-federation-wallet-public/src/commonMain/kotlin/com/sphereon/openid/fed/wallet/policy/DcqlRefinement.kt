package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * DCQL query matching for federation-managed `dcql_queries` (wallet architecture §6.3.1 / §7.1.1).
 *
 * A request `dcql_query` is accepted when it is:
 * - **equal** to a registered query (structural, object-key order-insensitive), or
 * - a **constrained refinement** of a registered query — it asks for no more than the
 *   federation authorizes (subset of credentials / claims / claim_sets / meta values).
 *
 * Refinement rules (practical OpenID4VP DCQL profile):
 * - Every request credential maps to a registered credential by `id` (preferred) or `format`
 * - Request claim paths ⊆ registered claim paths for that credential
 * - Request `claim_sets` (if present) ⊆ registered `claim_sets` (each set is a multiset of claim ids)
 * - Request `meta` array/string values ⊆ registered values when both are arrays of primitives
 * - Request must not introduce credentials, claims, or claim_sets outside the registered query
 * - Extra top-level keys on the request (beyond `credentials` / `credential_sets`) must equal the registered value
 */
object DcqlRefinement {

    enum class MatchKind {
        EQUAL,
        REFINEMENT,
        NONE,
    }

    data class MatchResult(
        val kind: MatchKind,
        val registeredIndex: Int? = null,
        val detail: String? = null,
    ) {
        val matches: Boolean get() = kind != MatchKind.NONE
    }

    /**
     * Find the best match of [request] against [registeredQueries].
     * Prefers EQUAL over REFINEMENT; returns first such index.
     */
    fun match(
        request: JsonElement,
        registeredQueries: JsonArray,
    ): MatchResult {
        var refinement: MatchResult? = null
        registeredQueries.forEachIndexed { index, registered ->
            if (jsonElementsEqual(registered, request)) {
                return MatchResult(MatchKind.EQUAL, index)
            }
            if (refinement == null && isRefinementOf(request, registered)) {
                refinement = MatchResult(
                    kind = MatchKind.REFINEMENT,
                    registeredIndex = index,
                    detail = "Request dcql_query is a constrained refinement of registered query at index $index",
                )
            }
        }
        return refinement ?: MatchResult(
            kind = MatchKind.NONE,
            detail = "Request dcql_query is neither equal to nor a constrained refinement of any registered dcql_queries entry",
        )
    }

    /**
     * Whether [request] is a constrained refinement of [registered] (not merely equal).
     * Equal queries also return true (refinement includes equality for boolean checks).
     */
    fun isRefinementOf(request: JsonElement, registered: JsonElement): Boolean {
        if (jsonElementsEqual(request, registered)) return true
        val req = request as? JsonObject ?: return false
        val reg = registered as? JsonObject ?: return false
        return isQueryRefinement(req, reg)
    }

    private fun isQueryRefinement(request: JsonObject, registered: JsonObject): Boolean {
        // credential_sets: if request has them, each set must be authorized by registered
        val reqSets = request["credential_sets"] as? JsonArray
        val regSets = registered["credential_sets"] as? JsonArray
        if (reqSets != null) {
            if (regSets == null) return false
            if (!credentialSetsRefine(reqSets, regSets)) return false
        }

        val reqCreds = request["credentials"] as? JsonArray
        val regCreds = registered["credentials"] as? JsonArray
        if (reqCreds != null) {
            if (regCreds == null) return false
            if (!credentialsRefine(reqCreds, regCreds)) return false
        } else if (regCreds != null && regCreds.isNotEmpty() && reqSets == null) {
            // Registered requires credentials but request has neither credentials nor credential_sets
            // Allow empty request only if registered is also empty (handled by equality path)
            return false
        }

        // Other top-level keys on request must equal registered (no expansion via unknown keys)
        val structuralKeys = setOf("credentials", "credential_sets")
        for (key in request.keys) {
            if (key in structuralKeys) continue
            val regVal = registered[key] ?: return false
            if (!jsonElementsEqual(request.getValue(key), regVal)) return false
        }

        return true
    }

    private fun credentialsRefine(request: JsonArray, registered: JsonArray): Boolean {
        // Each request credential must refine some registered credential; no extras
        val used = BooleanArray(registered.size)
        for (reqEl in request) {
            val reqCred = reqEl as? JsonObject ?: return false
            val idx = findMatchingRegisteredCredential(reqCred, registered, used) ?: return false
            used[idx] = true
            val regCred = registered[idx].jsonObject
            if (!credentialRefine(reqCred, regCred)) return false
        }
        return true
    }

    private fun findMatchingRegisteredCredential(
        request: JsonObject,
        registered: JsonArray,
        used: BooleanArray,
    ): Int? {
        val reqId = request["id"]?.jsonPrimitive?.contentOrNull
        if (reqId != null) {
            registered.forEachIndexed { i, el ->
                if (used[i]) return@forEachIndexed
                val regId = el.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                if (regId == reqId) return i
            }
            return null
        }
        // Fall back to format match (first unused with same format)
        val reqFormat = request["format"]?.jsonPrimitive?.contentOrNull
        registered.forEachIndexed { i, el ->
            if (used[i]) return@forEachIndexed
            val reg = el as? JsonObject ?: return@forEachIndexed
            val regFormat = reg["format"]?.jsonPrimitive?.contentOrNull
            if (reqFormat != null && reqFormat == regFormat) return i
            if (reqFormat == null && regFormat == null) return i
        }
        return null
    }

    private fun credentialRefine(request: JsonObject, registered: JsonObject): Boolean {
        // format must not expand: if both present, equal
        val reqFormat = request["format"]?.jsonPrimitive?.contentOrNull
        val regFormat = registered["format"]?.jsonPrimitive?.contentOrNull
        if (reqFormat != null && regFormat != null && reqFormat != regFormat) return false
        if (reqFormat != null && regFormat == null) return false

        // claims: request ⊆ registered by path
        val reqClaims = request["claims"] as? JsonArray
        val regClaims = registered["claims"] as? JsonArray
        if (reqClaims != null) {
            if (regClaims == null) return false
            if (!claimsRefine(reqClaims, regClaims)) return false
        }

        // claim_sets: each request set must be ⊆ some registered set (or equal membership subset)
        val reqClaimSets = request["claim_sets"] as? JsonArray
        val regClaimSets = registered["claim_sets"] as? JsonArray
        if (reqClaimSets != null) {
            if (regClaimSets == null) return false
            if (!claimSetsRefine(reqClaimSets, regClaimSets)) return false
        }

        // meta: array values refine by subset; other meta keys must equal
        val reqMeta = request["meta"] as? JsonObject
        val regMeta = registered["meta"] as? JsonObject
        if (reqMeta != null) {
            if (regMeta == null) return false
            if (!metaRefine(reqMeta, regMeta)) return false
        }

        // trusted_authorities etc.: request ⊆ registered when arrays; else equal
        for (key in request.keys) {
            if (key in setOf("id", "format", "claims", "claim_sets", "meta")) continue
            val regVal = registered[key] ?: return false
            val reqVal = request.getValue(key)
            if (reqVal is JsonArray && regVal is JsonArray) {
                if (!arraySubsetEqualElements(reqVal, regVal)) return false
            } else if (!jsonElementsEqual(reqVal, regVal)) {
                return false
            }
        }

        return true
    }

    private fun claimsRefine(request: JsonArray, registered: JsonArray): Boolean {
        val regPaths = registered.mapNotNull { claimPathKey(it) }.toSet()
        if (regPaths.isEmpty() && request.isNotEmpty()) {
            // Registered claims without path: fall back to full element membership
            return request.all { req -> registered.any { jsonElementsEqual(it, req) } }
        }
        for (req in request) {
            val path = claimPathKey(req)
            if (path != null) {
                if (path !in regPaths) return false
            } else {
                // No path: must equal some registered claim object
                if (registered.none { jsonElementsEqual(it, req) }) return false
            }
        }
        return true
    }

    private fun claimPathKey(claim: JsonElement): String? {
        val obj = claim as? JsonObject ?: return null
        val path = obj["path"] as? JsonArray ?: return null
        return path.joinToString("/") { it.jsonPrimitive.contentOrNull ?: it.toString() }
    }

    private fun claimSetsRefine(request: JsonArray, registered: JsonArray): Boolean {
        // Each request claim_set is an array of claim ids; must be subset of some registered claim_set
        for (reqSet in request) {
            val reqIds = claimSetIds(reqSet) ?: return false
            val authorized = registered.any { regSet ->
                val regIds = claimSetIds(regSet) ?: return@any false
                reqIds.all { it in regIds }
            }
            if (!authorized) return false
        }
        return true
    }

    private fun claimSetIds(set: JsonElement): Set<String>? {
        val arr = set as? JsonArray ?: return null
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    }

    private fun credentialSetsRefine(request: JsonArray, registered: JsonArray): Boolean {
        // credential_sets entries have "options": [[cred_id, ...], ...]
        // Request options must be subsets of some registered options list
        for (reqSet in request) {
            val reqObj = reqSet as? JsonObject ?: return false
            val regMatch = registered.any { regEl ->
                val regObj = regEl as? JsonObject ?: return@any false
                credentialSetEntryRefine(reqObj, regObj)
            }
            if (!regMatch) return false
        }
        return true
    }

    private fun credentialSetEntryRefine(request: JsonObject, registered: JsonObject): Boolean {
        val reqOptions = request["options"] as? JsonArray
        val regOptions = registered["options"] as? JsonArray
        if (reqOptions != null) {
            if (regOptions == null) return false
            for (reqOpt in reqOptions) {
                val reqIds = (reqOpt as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                    ?: return false
                val ok = regOptions.any { regOpt ->
                    val regIds = (regOpt as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                        ?: return@any false
                    reqIds.all { it in regIds }
                }
                if (!ok) return false
            }
        }
        // required flag: request may set required=true only if registered also requires it (or omit)
        val reqRequired = request["required"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        val regRequired = registered["required"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        if (reqRequired == true && regRequired != true) {
            // Request requires a set that federation marked optional — still ok (stronger for wallet)
            // No expansion concern
        }
        for (key in request.keys) {
            if (key == "options" || key == "required") continue
            val regVal = registered[key] ?: return false
            if (!jsonElementsEqual(request.getValue(key), regVal)) return false
        }
        return true
    }

    private fun metaRefine(request: JsonObject, registered: JsonObject): Boolean {
        for (key in request.keys) {
            val regVal = registered[key] ?: return false
            val reqVal = request.getValue(key)
            when {
                reqVal is JsonArray && regVal is JsonArray -> {
                    if (!arraySubsetEqualElements(reqVal, regVal)) return false
                }
                else -> if (!jsonElementsEqual(reqVal, regVal)) return false
            }
        }
        return true
    }

    private fun arraySubsetEqualElements(request: JsonArray, registered: JsonArray): Boolean {
        return request.all { req -> registered.any { jsonElementsEqual(it, req) } }
    }

    /**
     * Structural equality for JSON (order-insensitive for objects).
     */
    fun jsonElementsEqual(a: JsonElement, b: JsonElement): Boolean {
        return when {
            a is JsonObject && b is JsonObject -> {
                if (a.keys != b.keys) return false
                a.keys.all { key -> jsonElementsEqual(a.getValue(key), b.getValue(key)) }
            }
            a is JsonArray && b is JsonArray -> {
                if (a.size != b.size) return false
                a.indices.all { i -> jsonElementsEqual(a[i], b[i]) }
            }
            a is JsonPrimitive && b is JsonPrimitive -> a.content == b.content
            else -> a == b
        }
    }
}
