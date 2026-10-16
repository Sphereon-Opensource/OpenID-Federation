package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.util.JSONObjectUtils
import java.nio.charset.StandardCharsets

/** Immutable identity for an independently maintained peer used by qualification tests. */
data class PeerLock(
    val name: String,
    val commit: String,
    val image: String,
    val license: String,
) {
    init {
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "peer source must be a full lowercase git SHA" }
        require(image.matches(Regex("[^@]+@sha256:[0-9a-f]{64}"))) { "peer image must be pinned by sha256 digest" }
        require(license.isNotBlank()) { "peer license must be recorded" }
    }

    companion object {
        fun load(name: String): PeerLock {
            val document = readDocument()
            val peers = JSONObjectUtils.getJSONObject(document, "peers")
            val peer = JSONObjectUtils.getJSONObject(peers, name)
            return PeerLock(
                name = name,
                commit = JSONObjectUtils.getString(peer, "commit"),
                image = JSONObjectUtils.getString(peer, "image"),
                license = JSONObjectUtils.getString(peer, "license"),
            )
        }

        fun loadHarnessImage(name: String): String {
            val document = readDocument()
            val images = JSONObjectUtils.getJSONObject(document, "harnessImages")
            return JSONObjectUtils.getString(images, name).also {
                require(it.matches(Regex("[^@]+@sha256:[0-9a-f]{64}"))) { "harness image must be pinned by sha256 digest" }
            }
        }

        private fun readDocument(): Map<String, Any> {
            val resource = "/interop/implementations.lock.json"
            val input = PeerLock::class.java.getResourceAsStream(resource)
                ?: error("Missing peer lock resource $resource")
            input.use {
                val bytes = it.readNBytes(MAX_LOCK_BYTES + 1)
                require(bytes.size <= MAX_LOCK_BYTES) { "peer lock resource exceeds the 1 MiB limit" }
                return JSONObjectUtils.parse(String(bytes, StandardCharsets.UTF_8))
            }
        }

        private const val MAX_LOCK_BYTES = 1_048_576
    }
}
