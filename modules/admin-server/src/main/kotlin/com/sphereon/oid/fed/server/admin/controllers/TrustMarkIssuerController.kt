package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeIssuer
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.services.TrustMarkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/trust-mark-types/{id}/issuers")
class TrustMarkIssuerController(
    private val trustMarkService: TrustMarkService
) {
    @GetMapping
    fun getIssuersForTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): List<String> {
        return trustMarkService.getIssuersForTrustMarkType(
            request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account,
            id
        )
    }

    @PostMapping
    fun addIssuerToTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody body: CreateTrustMarkTypeIssuer
    ): TrustMarkIssuer {
        return trustMarkService.addIssuerToTrustMarkType(
            request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account,
            id,
            body.identifier
        )
    }

    @DeleteMapping("/{issuerIdentifier}")
    fun removeIssuerFromTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @PathVariable issuerIdentifier: String
    ): TrustMarkIssuer {
        return trustMarkService.removeIssuerFromTrustMarkType(
            request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account,
            id,
            issuerIdentifier
        )
    }
}
