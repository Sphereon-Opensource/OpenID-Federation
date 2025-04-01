package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.AddTrustMarkIssuerResponse
import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeIssuer
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeIssuersResponse
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.mappers.toAddTrustMarkIssuerResponse
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
        @PathVariable id: String
    ): TrustMarkTypeIssuersResponse {
        val account = getAccountFromRequest(request)
        // We do not create a mapper for this response like we do for others, as the extension function would apply to String lists, which is very common
        return TrustMarkTypeIssuersResponse(trustMarkService.getIssuersForTrustMarkType(account, id).toTypedArray())
    }

    @PostMapping
    fun addIssuerToTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestBody body: CreateTrustMarkTypeIssuer
    ): AddTrustMarkIssuerResponse {
        val account = getAccountFromRequest(request)
        return trustMarkService.addIssuerToTrustMarkType(
            account,
            id,
            body.identifier
        ).toAddTrustMarkIssuerResponse()
    }

    @DeleteMapping("/{issuerIdentifier}")
    fun removeIssuerFromTrustMarkType(
        request: HttpServletRequest,
        @PathVariable id: String,
        @PathVariable issuerIdentifier: String
    ): TrustMarkIssuer {
        val account = getAccountFromRequest(request)
        return trustMarkService.removeIssuerFromTrustMarkType(
            account,
            id,
            issuerIdentifier
        )
    }
}
