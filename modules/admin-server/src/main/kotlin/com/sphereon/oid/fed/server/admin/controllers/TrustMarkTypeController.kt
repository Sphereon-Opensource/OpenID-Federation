package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeDTO
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
@RequestMapping("/trust-mark-types")
class TrustMarkTypeController(
    private val trustMarkService: TrustMarkService,
    private val accountService: AccountService
) {
    @GetMapping
    fun getTrustMarkTypes(request: HttpServletRequest): List<TrustMarkTypeDTO> {
        return trustMarkService.findAllByAccount(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id
        )
    }

    @PostMapping
    fun createTrustMarkType(
        request: HttpServletRequest,
        @RequestBody createDto: CreateTrustMarkTypeDTO
    ): TrustMarkTypeDTO {
        return trustMarkService.createTrustMarkType(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            createDto,
            accountService
        )
    }

    @GetMapping("/{id}")
    fun getTrustMarkTypeById(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): TrustMarkTypeDTO {
        return trustMarkService.findById(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id,
            id
        )
    }

    @DeleteMapping("/{id}")
    fun deleteTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): TrustMarkTypeDTO {
        return trustMarkService.deleteTrustMarkType(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id,
            id
        )
    }
}
