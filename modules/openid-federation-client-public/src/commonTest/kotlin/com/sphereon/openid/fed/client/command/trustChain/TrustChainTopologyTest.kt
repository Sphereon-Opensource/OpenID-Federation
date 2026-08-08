package com.sphereon.openid.fed.client.command.trustChain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustChainTopologyTest {

    @Test
    fun validateMinimumLength_emptyFails() {
        assertEquals("Trust chain is empty", TrustChainTopology.validateMinimumLength(0))
        assertNull(TrustChainTopology.validateMinimumLength(1))
        assertNull(TrustChainTopology.validateMinimumLength(2))
        assertNull(TrustChainTopology.validateMinimumLength(5))
    }

    @Test
    fun selectPreferredChain_prefersEarlierTrustAnchorThenShorter() {
        val taA = "https://ta-a.example"
        val taB = "https://ta-b.example"
        // Fake chains as opaque JWT placeholders; selection only uses length + TA extractor
        val chainLongA = listOf("leaf", "ss1", "ss2", "taA-ec")
        val chainShortA = listOf("leaf", "ss", "taA-ec")
        val chainShortB = listOf("leaf", "ss", "taB-ec")

        val selected = TrustChainTopology.selectPreferredChain(
            candidates = listOf(chainLongA, chainShortB, chainShortA),
            preferredTrustAnchors = arrayOf(taA, taB),
            trustAnchorOf = { chain ->
                when (chain.last()) {
                    "taA-ec" -> taA
                    "taB-ec" -> taB
                    else -> null
                }
            },
        )
        assertEquals(chainShortA, selected)
    }

    @Test
    fun selectPreferredChain_fallsBackToOtherTaWhenPreferredMissing() {
        val taA = "https://ta-a.example"
        val taB = "https://ta-b.example"
        val onlyB = listOf("leaf", "ss", "taB")
        val selected = TrustChainTopology.selectPreferredChain(
            candidates = listOf(onlyB),
            preferredTrustAnchors = arrayOf(taA, taB),
            trustAnchorOf = { taB },
        )
        assertEquals(onlyB, selected)
    }

    @Test
    fun selectPreferredChain_emptyReturnsNull() {
        assertNull(
            TrustChainTopology.selectPreferredChain(
                candidates = emptyList(),
                preferredTrustAnchors = arrayOf("https://ta.example"),
                trustAnchorOf = { null },
            )
        )
    }
}
