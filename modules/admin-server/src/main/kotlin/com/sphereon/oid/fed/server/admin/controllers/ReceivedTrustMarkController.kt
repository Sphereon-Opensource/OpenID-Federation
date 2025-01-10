package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/received-trust-marks")
class ReceivedTrustMarkController(
    private val receivedTrustMarkService: ReceivedTrustMarkService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createReceivedTrustMark(
        request: HttpServletRequest,
        @RequestBody dto: CreateReceivedTrustMarkDTO
    ): ReceivedTrustMarkDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return receivedTrustMarkService.createReceivedTrustMark(account, dto)
    }

    @GetMapping
    fun listReceivedTrustMarks(request: HttpServletRequest): Array<ReceivedTrustMarkDTO> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return receivedTrustMarkService.listReceivedTrustMarks(account).toTypedArray()
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun deleteReceivedTrustMark(
        request: HttpServletRequest,
        @PathVariable receivedTrustMarkId: Int
    ): ReceivedTrustMarkDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return receivedTrustMarkService.deleteReceivedTrustMark(account, receivedTrustMarkId)
    }
}

