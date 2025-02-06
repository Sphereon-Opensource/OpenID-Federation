package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import com.sphereon.oid.fed.services.mappers.toDTO
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
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return receivedTrustMarkService.createReceivedTrustMark(account, dto).toDTO()
    }

    @GetMapping
    fun listReceivedTrustMarks(request: HttpServletRequest): Array<ReceivedTrustMark> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return receivedTrustMarkService.listReceivedTrustMarks(account).map { it.toDTO() }.toTypedArray()
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun deleteReceivedTrustMark(
        request: HttpServletRequest,
        @PathVariable receivedTrustMarkId: Int
    ): ReceivedTrustMark {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return receivedTrustMarkService.deleteReceivedTrustMark(account, receivedTrustMarkId).toDTO()
    }
}

