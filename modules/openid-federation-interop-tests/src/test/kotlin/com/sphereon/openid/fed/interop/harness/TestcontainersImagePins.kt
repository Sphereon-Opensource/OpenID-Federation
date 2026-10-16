package com.sphereon.openid.fed.interop.harness

import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor

/** Pins Testcontainers 1.21.3's tagged Ryuk and startup-check images to the lock file. */
class TestcontainersImagePins : ImageNameSubstitutor() {
    private val pinsByOriginalName = mapOf(
        RYUK_TAG to PeerLock.loadHarnessImage("ryuk"),
        STARTUP_ALPINE_TAG to PeerLock.loadHarnessImage("startupAlpine"),
    )

    override fun apply(original: DockerImageName): DockerImageName {
        val pinnedImage = pinsByOriginalName[original.asCanonicalNameString()] ?: return original
        // If a host-level default substitutor changes either source name before this
        // exact map runs, it will remain unmatched and the pinning test fails closed.
        return DockerImageName.parse(pinnedImage).asCompatibleSubstituteFor(original)
    }

    override fun getDescription(): String = "OpenID Federation harness digest pins for Testcontainers support images"

    private companion object {
        const val RYUK_TAG = "testcontainers/ryuk:0.12.0"
        const val STARTUP_ALPINE_TAG = "alpine:3.17"
    }
}
