package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkTypesResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.mappers.toTrustMarkTypesResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/trust-mark-types")
class TrustMarkTypeController(
    private val trustMarkService: TrustMarkService
) {
    @GetMapping
    fun getTrustMarkTypes(request: HttpServletRequest): TrustMarkTypesResponse {
        val account = getAccountFromRequest(request)
        return trustMarkService.findAllByAccount(account).toTrustMarkTypesResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTrustMarkType(
        request: HttpServletRequest,
        @RequestBody createDto: CreateTrustMarkType
    ): TrustMarkType {
        val account = getAccountFromRequest(request)
        return trustMarkService.createTrustMarkType(account, createDto)
    }

    @GetMapping("/{id}")
    fun getTrustMarkTypeById(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): TrustMarkType {
        val account = getAccountFromRequest(request)
        return trustMarkService.findById(account, id)
    }

    @DeleteMapping("/{id}")
    fun deleteTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): TrustMarkType {
        val account = getAccountFromRequest(request)
        return trustMarkService.deleteTrustMarkType(account, id)
    }
}
