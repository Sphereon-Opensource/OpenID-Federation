package com.sphereon.oid.fed.server.admin.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.LogService
import com.sphereon.oid.fed.services.TrustMarkService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(TrustMarkController::class)
class TrustMarkControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var accountService: AccountService

    @MockitoBean
    private lateinit var trustMarkService: TrustMarkService

    @MockitoBean
    private lateinit var logService: LogService

    private val mockJwtResponse =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    @Test
    fun `should return bad request when sub is missing`() {
        val invalidJson = """
            {
              "trust_mark_id": "http://localhost:8080/trust-mark-types/exampleType"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/trust-marks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(jwt())
//                .withAccount()
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `should return bad request when trust_mark_id is missing`() {
        val invalidJson = """
            {
              "sub": "http://localhost:8080/trust-mark-holder"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/trust-marks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(jwt())
//                .withAccount()
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `should return bad request when trust_mark_id is empty`() {
        val invalidJson = """
            {
              "sub": "http://localhost:8080/trust-mark-holder",
              "trust_mark_id": ""
            }
        """.trimIndent()

        mockMvc.perform(
            post("/trust-marks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(jwt())
//                .withAccount()
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `should return bad request when sub is empty`() {
        val invalidJson = """
            {
              "sub": "",
              "trust_mark_id": "http://localhost:8080/trust-mark-types/exampleType"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/trust-marks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(jwt())
//                .withAccount()
        )
            .andExpect(status().isBadRequest)
    }
}
