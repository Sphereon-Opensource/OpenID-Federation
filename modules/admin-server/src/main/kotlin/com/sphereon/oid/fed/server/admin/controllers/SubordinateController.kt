package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.services.SubordinateService
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.JsonObject
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/subordinates")
class SubordinateController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun getSubordinates(request: HttpServletRequest): Array<Subordinate> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.findSubordinatesByAccount(account)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubordinate(
        request: HttpServletRequest,
        @RequestBody subordinate: CreateSubordinate
    ): Subordinate {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.createSubordinate(account, subordinate)
    }

    @DeleteMapping("/{id}")
    fun deleteSubordinate(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Subordinate {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.deleteSubordinate(account, id)
    }

    @PostMapping("/{id}/jwks")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody jwk: JsonObject
    ): SubordinateJwk {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.createSubordinateJwk(account, id, jwk)
    }

    @GetMapping("/{id}/jwks")
    fun getSubordinateJwks(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Array<SubordinateJwk> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.getSubordinateJwks(account, id)
    }

    @DeleteMapping("/{id}/jwks/{jwkId}")
    fun deleteSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @PathVariable jwkId: Int
    ) {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        subordinateService.deleteSubordinateJwk(account, id, jwkId)
    }

    @GetMapping("/{id}/statement")
    fun getSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): SubordinateStatement {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.getSubordinateStatement(account, id)
    }

    @PostMapping("/{id}/statement")
    fun publishSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody body: PublishStatementRequest?
    ): ResponseEntity<String> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        val result = subordinateService.publishSubordinateStatement(account, id, body?.dryRun)
        return if (body?.dryRun == true) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }
}
