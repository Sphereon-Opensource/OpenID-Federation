package com.sphereon.openid.fed.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class OidfHoconFlattenerTest {

    @Test
    fun flattens_nested_oidf_blocks() {
        val conf = """
            oidf {
              federation {
                root.identifier = "http://localhost:8080"
                dev.mode = false
              }
              identity {
                mode = "legacy"
              }
            }
        """.trimIndent()

        val map = OidfHoconFlattener.flatten(conf)
        assertEquals("http://localhost:8080", map["oidf.federation.root.identifier"])
        assertEquals("false", map["oidf.federation.dev.mode"])
        assertEquals("legacy", map["oidf.identity.mode"])
    }

    @Test
    fun ignores_comments() {
        val conf = """
            # comment
            oidf.logger.severity = "INFO" // inline
        """.trimIndent()
        val map = OidfHoconFlattener.flatten(conf)
        assertEquals("INFO", map["oidf.logger.severity"])
    }
}
