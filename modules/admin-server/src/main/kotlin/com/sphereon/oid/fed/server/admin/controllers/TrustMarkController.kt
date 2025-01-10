package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.TrustMarkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trust-marks")
class TrustMarkController(
    private val trustMarkService: TrustMarkService,
    private val accountService: AccountService
) {
    @GetMapping
    fun getTrustMarks(request: HttpServletRequest): List<TrustMarkDTO> {
        return trustMarkService.getTrustMarksForAccount(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id
        )
    }

    @PostMapping
    fun createTrustMark(
        request: HttpServletRequest,
        @RequestBody body: CreateTrustMarkDTO
    ): TrustMarkDTO {
        return trustMarkService.createTrustMark(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id,
            body,
            accountService
        )
    }

    @DeleteMapping("/{trustMarkId}")
    fun deleteTrustMark(
        request: HttpServletRequest,
        @PathVariable trustMarkId: Int
    ): TrustMarkDTO {
        return trustMarkService.deleteTrustMark(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id,
            trustMarkId
        )
    }
}
