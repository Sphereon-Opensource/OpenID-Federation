package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateCrit
import com.sphereon.oid.fed.persistence.models.Crit
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.CriticalClaimService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/crits")
class CriticalClaimController(
    private val criticalClaimService: CriticalClaimService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createCriticalClaim(
        request: HttpServletRequest,
        @RequestBody body: CreateCrit
    ): Crit {
        val account = getAccountFromRequest(request)
        return criticalClaimService.create(account, body.claim)
    }

    @GetMapping
    fun getCriticalClaims(request: HttpServletRequest): Array<Crit> {
        val account = getAccountFromRequest(request)
        return criticalClaimService.findByAccount(account)
    }

    @DeleteMapping("/{id}")
    fun deleteCriticalClaim(
        request: HttpServletRequest,
        @PathVariable id: String
    ): Crit {
        val account = getAccountFromRequest(request)
        return criticalClaimService.delete(account, id)
    }
}
