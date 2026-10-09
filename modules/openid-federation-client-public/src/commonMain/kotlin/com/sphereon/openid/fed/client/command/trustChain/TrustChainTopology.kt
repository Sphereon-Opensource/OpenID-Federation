package com.sphereon.openid.fed.client.command.trustChain

/**
 * Trust chain topology helpers (OIDFed 1.1 §4 / §10.2).
 *
 * Valid shapes:
 * - **Length 1:** subject is the Trust Anchor (self-signed Entity Configuration only).
 * - **Length 2:** subject EC + Trust Anchor Subordinate Statement (TA EC omitted; TA keys OOB).
 * - **Length ≥ 3:** subject EC + one or more Subordinate Statements + Trust Anchor EC
 *   (deeper intermediate paths are valid; there is no maximum other than deployment
 *   `maxDepth` / `max_path_length` constraints).
 */
object TrustChainTopology {

    /**
     * Minimum number of JWTs required for a non-TA subject under a Trust Anchor when the
     * Trust Anchor Entity Configuration is included: leaf EC + TA SS + TA EC.
     */
    const val MIN_FULL_CHAIN_WITH_TA_EC = 3

    /**
     * @return null if the length is acceptable for further validation; otherwise an error reason.
     */
    fun validateMinimumLength(size: Int): String? {
        if (size < 1) {
            return "Trust chain is empty"
        }
        // Length 1 and 2 are handled as special topologies in the verifier.
        // Length ≥ 3 covers the common leaf + SS* + TA EC case, including deep intermediates.
        return null
    }

    /**
     * Candidates in preference order: Trust Anchors earlier in [preferredTrustAnchors] first, then shorter chains.
     * OpenID Federation 1.1 §10.3 chooses among valid chains, so callers verify in this order and take the first valid.
     */
    fun orderPreferredChains(
        candidates: List<List<String>>,
        preferredTrustAnchors: Array<String>,
        trustAnchorOf: (List<String>) -> String?,
    ): List<List<String>> {
        val taOrder = preferredTrustAnchors.mapIndexed { index, ta -> ta to index }.toMap()
        return candidates.sortedWith(
            compareBy<List<String>> { chain -> taOrder[trustAnchorOf(chain)] ?: Int.MAX_VALUE }.thenBy { it.size }
        )
    }

    /** The most preferred candidate by [orderPreferredChains], before any validation. */
    fun selectPreferredChain(
        candidates: List<List<String>>,
        preferredTrustAnchors: Array<String>,
        trustAnchorOf: (List<String>) -> String?,
    ): List<String>? = orderPreferredChains(candidates, preferredTrustAnchors, trustAnchorOf).firstOrNull()
}
