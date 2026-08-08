package com.sphereon.openid.fed.httpResolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Ensures HttpMetadata serializers are portable (byte round-trip without identity maps).
 */
class HttpMetadataCacheSerializersTest {

    @Test
    fun `stringValue serializer round-trips HttpMetadata`() {
        val original = HttpMetadata(
            value = """{"iss":"https://example.com"}""",
            etag = "W/\"abc\"",
            lastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
        )
        val ser = HttpMetadataCacheSerializers.stringValue
        val bytes = ser.serialize(original)
        val restored = ser.deserialize(bytes)
        assertEquals(original, restored)

        val asString = ser.serializeToString(original)
        assertNotNull(asString)
        assertEquals(original, ser.deserializeFromString(asString))
    }
}
