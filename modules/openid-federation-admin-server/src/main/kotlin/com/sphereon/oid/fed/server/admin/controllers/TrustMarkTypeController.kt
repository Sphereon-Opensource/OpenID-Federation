package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkType
import com.sphereon.oid.fed.openapi.models.TrustMarkTypesResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.mappers.toTrustMarkTypesResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trust-mark-types")
class TrustMarkTypeController(
    private val trustMarkService: TrustMarkService
) {
    @GetMapping
    fun getTrustMarkTypes(request: HttpServletRequest): ResponseEntity<TrustMarkTypesResponse> {
        val account = getAccountFromRequest(request)
        return trustMarkService.findAllByAccount(account).toTrustMarkTypesResponse().let {
            ResponseEntity.ok(it)
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTrustMarkType(
        request: HttpServletRequest,
        @Valid @RequestBody createDto: CreateTrustMarkType,
        bindingResult: BindingResult
    ): ResponseEntity<TrustMarkType> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }
        val account = getAccountFromRequest(request)
        return trustMarkService.createTrustMarkType(account, createDto).let {
            ResponseEntity.status(HttpStatus.CREATED).body(it)
        }
    }

    @GetMapping("/{id}")
    fun getTrustMarkTypeById(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<TrustMarkType> {
        val account = getAccountFromRequest(request)
        return trustMarkService.findById(account, id).let {
            ResponseEntity.ok(it)
        }
    }

    @DeleteMapping("/{id}")
    fun deleteTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<TrustMarkType> {
        val account = getAccountFromRequest(request)
        return trustMarkService.deleteTrustMarkType(account, id).let {
            ResponseEntity.ok(it)
        }
    }
}
