package com.sphereon.openid.fed.interop.harness

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeerLockTest {
    @Test fun `mutable image tags cannot qualify a counterpart`() {
        assertFailsWith<IllegalArgumentException> {
            PeerLock("lighthouse", "a".repeat(40), "oidfed/lighthouse:latest", "MIT")
        }
    }

    @Test fun `source identity requires a full git commit`() {
        assertFailsWith<IllegalArgumentException> {
            PeerLock("lighthouse", "main", "oidfed/lighthouse@sha256:" + "a".repeat(64), "MIT")
        }
    }

    @Test fun `checked in counterpart identity loads without network access`() {
        val lock = PeerLock.load("lighthouse")
        assertEquals("20c4a4e4a9b91b489014a2f1b009fcaaa186401b", lock.commit)
        assertEquals("oidfed/lighthouse@sha256:20eab44c0360985c5687283644622e50fe27599ccea603392948e61e0bc3f505", lock.image)
    }
}
