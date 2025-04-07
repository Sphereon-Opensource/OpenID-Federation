package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.LogService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(AuthorityHintController::class)
class AuthorityHintControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var authorityHintService: AuthorityHintService

    @MockitoBean
    private lateinit var logService: LogService

    @Test
    fun `should return bad request when identifier is missing`() {
        val invalidJson = "{}"

        mockMvc.perform(
            post("/authority-hints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(jwt())
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `should return bad request when identifier is empty`() {
        val invalidJson = "{\"identifier\": \"\"}"

        mockMvc.perform(
            post("/authority-hints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(jwt())
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("identifier size must be between 10 and 2048")))
    }
}
