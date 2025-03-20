package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.mappers.subordinate.toSubordinatesResponse
import com.sphereon.oid.fed.services.mappers.subordinateJwk.toSubordinateJwksResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/subordinates")
class SubordinateController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun getSubordinates(request: HttpServletRequest): SubordinatesResponse {
        val account = getAccountFromRequest(request)
        return subordinateService.findSubordinatesByAccount(account).toSubordinatesResponse()
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
    ): SubordinateJwksResponse {
        val account = getAccountFromRequest(request)
        return subordinateService.getSubordinateJwks(account, id).toSubordinateJwksResponse()
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
        val result = subordinateService.publishSubordinateStatement(account = account, id = id, dryRun = body?.dryRun, kmsKeyRef = body?.kmsKeyRef, kid = body?.kid)
        return if (body?.dryRun == true) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }


}
