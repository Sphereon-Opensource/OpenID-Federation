package com.sphereon.openid.fed.interop.harness

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor

/** Verifies the implicit Testcontainers support images are immutable without starting Docker. */
class TestcontainersImplicitImagePinTest {
    @Test
    fun `Testcontainers support image names resolve to the audited digest pins`() {
        val substitutor = ImageNameSubstitutor.instance()
        val ryukOriginal = DockerImageName.parse("testcontainers/ryuk:0.12.0")
        val alpineOriginal = DockerImageName.parse("alpine:3.17")
        val ryukPinned = substitutor.apply(ryukOriginal)
        val alpinePinned = substitutor.apply(alpineOriginal)
        val existingPinnedImage = PeerLock.loadHarnessImage("curl")
        val explicitPinned = DockerImageName.parse(existingPinnedImage)

        assertAll(
            "Testcontainers 1.21.3 implicit support images",
            {
                assertEquals(
                    PeerLock.loadHarnessImage("ryuk"),
                    ryukPinned.asCanonicalNameString(),
                )
            },
            {
                assertEquals(
                    PeerLock.loadHarnessImage("startupAlpine"),
                    alpinePinned.asCanonicalNameString(),
                )
            },
            { assertTrue(ryukPinned.isCompatibleWith(ryukOriginal)) },
            { assertTrue(alpinePinned.isCompatibleWith(alpineOriginal)) },
            { assertEquals(explicitPinned.asCanonicalNameString(), substitutor.apply(explicitPinned).asCanonicalNameString()) },
        )
    }
}
