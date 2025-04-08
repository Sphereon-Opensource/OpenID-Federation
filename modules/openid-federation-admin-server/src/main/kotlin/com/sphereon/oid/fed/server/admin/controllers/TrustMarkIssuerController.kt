package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.AddTrustMarkIssuerResponse
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeIssuer
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeIssuersResponse
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.mappers.toAddTrustMarkIssuerResponse
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
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trust-mark-types/{id}/issuers")
class TrustMarkIssuerController(
    private val trustMarkService: TrustMarkService
) {
    @GetMapping
    fun getIssuersForTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<TrustMarkTypeIssuersResponse> {
        return TrustMarkTypeIssuersResponse(
            trustMarkService.getIssuersForTrustMarkType(getAccountFromRequest(request), id).toTypedArray()
        ).let {
            ResponseEntity.ok(it)
        }
    }

    @PostMapping
    fun addIssuerToTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: String,
        @Valid @RequestBody body: CreateTrustMarkTypeIssuer,
        bindingResult: BindingResult
    ): ResponseEntity<AddTrustMarkIssuerResponse> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            trustMarkService.addIssuerToTrustMarkType(
                getAccountFromRequest(request),
                id,
                body.identifier
            ).toAddTrustMarkIssuerResponse()
        )
    }

    @DeleteMapping("/{issuerIdentifier}")
    fun removeIssuerFromTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: String,
        @PathVariable issuerIdentifier: String
    ): ResponseEntity<TrustMarkIssuer> {
        return trustMarkService.removeIssuerFromTrustMarkType(
            getAccountFromRequest(request),
            id,
            issuerIdentifier
        ).let {
            ResponseEntity.ok(it)
        }
    }
}
