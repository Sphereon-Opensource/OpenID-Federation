package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.TrustMarkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/trust-mark-types")
class TrustMarkTypeController(
    private val trustMarkService: TrustMarkService
) {
    @GetMapping
    fun getTrustMarkTypes(request: HttpServletRequest): List<TrustMarkType> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.findAllByAccount(account)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTrustMarkType(
        request: HttpServletRequest,
        @RequestBody createDto: CreateTrustMarkType
    ): TrustMarkType {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.createTrustMarkType(account, createDto)
    }

    @GetMapping("/{id}")
    fun getTrustMarkTypeById(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): TrustMarkType {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.findById(account, id)
    }

    @DeleteMapping("/{id}")
    fun deleteTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): TrustMarkType {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return trustMarkService.deleteTrustMarkType(account, id)
    }
}
