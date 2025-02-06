package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateTrustMark
import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.TrustMarkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/trust-marks")
class TrustMarkController(
    private val trustMarkService: TrustMarkService
) {

    @GetMapping
    fun getTrustMarks(request: HttpServletRequest): List<TrustMark> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.getTrustMarksForAccount(account)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTrustMark(
        request: HttpServletRequest,
        @RequestBody body: CreateTrustMark
    ): TrustMark {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.createTrustMark(account, body)
    }

    @DeleteMapping("/{trustMarkId}")
    fun deleteTrustMark(
        request: HttpServletRequest,
        @PathVariable trustMarkId: Int
    ): TrustMark {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.deleteTrustMark(account, trustMarkId)
    }
}