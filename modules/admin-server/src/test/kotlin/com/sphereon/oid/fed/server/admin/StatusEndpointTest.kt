package com.sphereon.oid.fed.server.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class StatusEndpointTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun testStatusEndpoint() {
        mockMvc.perform(get("/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
    }
}