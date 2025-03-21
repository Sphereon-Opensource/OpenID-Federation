package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarksResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import com.sphereon.oid.fed.services.mappers.toReceivedTrustMarksResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/received-trust-marks")
class ReceivedTrustMarkController(
    private val receivedTrustMarkService: ReceivedTrustMarkService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createReceivedTrustMark(
        request: HttpServletRequest,
        @RequestBody receivedTrustMarkData: CreateReceivedTrustMark
    ): ReceivedTrustMark {
        val account = getAccountFromRequest(request)
        return receivedTrustMarkService.createReceivedTrustMark(account, receivedTrustMarkData)
    }

    @GetMapping
    fun listReceivedTrustMarks(request: HttpServletRequest): ReceivedTrustMarksResponse {
        val account = getAccountFromRequest(request)
        return receivedTrustMarkService.listReceivedTrustMarks(account).toReceivedTrustMarksResponse()
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun deleteReceivedTrustMark(
        request: HttpServletRequest,
        @PathVariable receivedTrustMarkId: String
    ): ReceivedTrustMark {
        val account = getAccountFromRequest(request)
        return receivedTrustMarkService.deleteReceivedTrustMark(account, receivedTrustMarkId)
    }
}

