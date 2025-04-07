package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateCrit
import com.sphereon.oid.fed.persistence.models.Crit
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.CriticalClaimService
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
@RequestMapping("/crits")
class CriticalClaimController(
    private val criticalClaimService: CriticalClaimService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createCriticalClaim(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateCrit,
        bindingResult: BindingResult
    ): ResponseEntity<Crit> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return criticalClaimService.create(getAccountFromRequest(request), body.claim)
            .let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
    }

    @GetMapping
    fun getCriticalClaims(request: HttpServletRequest): ResponseEntity<Array<Crit>> {
        return criticalClaimService.findByAccount(getAccountFromRequest(request)).let { ResponseEntity.ok(it) }
    }

    @DeleteMapping("/{id}")
    fun deleteCriticalClaim(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<Crit> {
        return criticalClaimService.delete(getAccountFromRequest(request), id).let { ResponseEntity.ok(it) }
    }
}
