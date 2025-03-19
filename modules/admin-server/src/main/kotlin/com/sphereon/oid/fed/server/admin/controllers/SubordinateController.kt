package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateSubordinate
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.SubordinateService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/subordinates")
class SubordinateController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun getSubordinates(request: HttpServletRequest): Array<Subordinate> {
        val account = getAccountFromRequest(request)
        return subordinateService.findSubordinatesByAccount(account)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubordinate(
        request: HttpServletRequest,
        @RequestBody subordinate: CreateSubordinate
    ): Subordinate {
        val account = getAccountFromRequest(request)
        return subordinateService.createSubordinate(account, subordinate)
    }

    @DeleteMapping("/{id}")
    fun deleteSubordinate(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Subordinate {
        val account = getAccountFromRequest(request)
        return subordinateService.deleteSubordinate(account, id)
    }

    @PostMapping("/{id}/jwks")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody jwk: Jwk
    ): SubordinateJwk {
        val account = getAccountFromRequest(request)
        return subordinateService.createSubordinateJwk(account, id, jwk)
    }

    @GetMapping("/{id}/jwks")
    fun getSubordinateJwks(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Array<SubordinateJwk> {
        val account = getAccountFromRequest(request)
        return subordinateService.getSubordinateJwks(account, id)
    }

    @DeleteMapping("/{id}/jwks/{jwkId}")
    fun deleteSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @PathVariable jwkId: Int
    ) {
        val account = getAccountFromRequest(request)
        subordinateService.deleteSubordinateJwk(account, id, jwkId)
    }

    @GetMapping("/{id}/statement")
    fun getSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): SubordinateStatement {
        val account = getAccountFromRequest(request)
        return subordinateService.getSubordinateStatement(account, id)
    }

    @PostMapping("/{id}/statement")
    suspend fun publishSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody body: PublishStatementRequest?
    ): ResponseEntity<String> {
        val account = getAccountFromRequest(request)
        val result = subordinateService.publishSubordinateStatement(account = account,  id = id, dryRun = body?.dryRun, kmsKeyRef = body?.kmsKeyRef, kid = body?.kid)
        return if (body?.dryRun == true) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }


}
