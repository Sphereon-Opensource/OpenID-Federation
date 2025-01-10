package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/received-trust-marks")
class ReceivedTrustMarkController(
    private val receivedTrustMarkService: ReceivedTrustMarkService,
    private val accountService: AccountService
) {
    @PostMapping
    fun create(
        request: HttpServletRequest,
        @RequestBody dto: CreateReceivedTrustMarkDTO
    ): ReceivedTrustMarkDTO {
        return receivedTrustMarkService.create(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            dto,
            accountService
        )
    }

    @GetMapping
    fun list(request: HttpServletRequest): Array<ReceivedTrustMarkDTO> {
        return receivedTrustMarkService.list(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            accountService
        ).toTypedArray()
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun delete(
        request: HttpServletRequest,
        @PathVariable receivedTrustMarkId: Int
    ): ReceivedTrustMarkDTO {
        return receivedTrustMarkService.delete(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            receivedTrustMarkId,
            accountService
        )
    }
}
