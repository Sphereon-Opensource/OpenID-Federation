package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeIssuerDTO
import com.sphereon.oid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.TrustMarkService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{username}/trust-mark-types/{id}/issuers")
class TrustMarkIssuerController {
    private val accountService = AccountService()
    private val trustMarkService = TrustMarkService()

    @GetMapping
    fun getIssuersForTrustMarkType(
        @PathVariable username: String,
        @PathVariable id: Int
    ): List<String> {
        return trustMarkService.getIssuersForTrustMarkType(
            accountId = accountService.usernameToAccountId(username),
            trustMarkTypeId = id
        )
    }

    @PostMapping
    fun addIssuerToTrustMarkType(
        @PathVariable username: String,
        @PathVariable id: Int,
        @RequestBody body: CreateTrustMarkTypeIssuerDTO
    ): TrustMarkIssuer {
        return trustMarkService.addIssuerToTrustMarkType(
            accountId = accountService.usernameToAccountId(username),
            trustMarkTypeId = id,
            issuerIdentifier = body.identifier
        )
    }

    @DeleteMapping("/{issuerIdentifier}")
    fun removeIssuerFromTrustMarkType(
        @PathVariable username: String,
        @PathVariable id: Int,
        @PathVariable issuerIdentifier: String
    ): TrustMarkIssuer {
        return trustMarkService.removeIssuerFromTrustMarkType(
            accountId = accountService.usernameToAccountId(username),
            trustMarkTypeId = id,
            issuerIdentifier = issuerIdentifier
        )
    }
}
