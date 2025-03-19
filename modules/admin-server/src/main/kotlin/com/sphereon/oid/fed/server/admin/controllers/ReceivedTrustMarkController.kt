package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
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
        @RequestBody dto: CreateReceivedTrustMark
    ): ReceivedTrustMark {
        val account = getAccountFromRequest(request)
        return receivedTrustMarkService.createReceivedTrustMark(account, dto)
    }

    @GetMapping
    fun listReceivedTrustMarks(request: HttpServletRequest): Array<ReceivedTrustMark> {
        val account = getAccountFromRequest(request)
        return receivedTrustMarkService.listReceivedTrustMarks(account)
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun deleteReceivedTrustMark(
        request: HttpServletRequest,
        @PathVariable receivedTrustMarkId: Int
    ): ReceivedTrustMark {
        val account = getAccountFromRequest(request)
        return receivedTrustMarkService.deleteReceivedTrustMark(account, receivedTrustMarkId)
    }
}

