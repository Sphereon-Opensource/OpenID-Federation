package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.openapi.models.SubordinateAdminDTO
import com.sphereon.oid.fed.openapi.models.SubordinateJwkDto
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.mappers.toSubordinateAdminDTO
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.JsonObject
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/subordinates")
class SubordinateController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun getSubordinates(request: HttpServletRequest): Array<SubordinateAdminDTO> {
        return subordinateService.findSubordinatesByAccount(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username
        ).map { it.toSubordinateAdminDTO() }.toTypedArray()
    }

    @PostMapping
    fun createSubordinate(
        request: HttpServletRequest,
        @RequestBody subordinate: CreateSubordinateDTO
    ): Subordinate {
        return subordinateService.createSubordinate(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            subordinate
        )
    }

    @DeleteMapping("/{id}")
    fun deleteSubordinate(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Subordinate {
        return subordinateService.deleteSubordinate(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id
        )
    }

    @PostMapping("/{id}/jwks")
    fun createSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody jwk: JsonObject
    ): SubordinateJwkDto {
        return subordinateService.createSubordinateJwk(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id,
            jwk
        )
    }

    @GetMapping("/{id}/jwks")
    fun getSubordinateJwks(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Array<SubordinateJwkDto> {
        return subordinateService.getSubordinateJwks(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id
        )
    }

    @DeleteMapping("/{id}/jwks/{jwkId}")
    fun deleteSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @PathVariable jwkId: Int
    ) {
        subordinateService.deleteSubordinateJwk(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id,
            jwkId
        )
    }

    @GetMapping("/{id}/statement")
    fun getSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): SubordinateStatement {
        return subordinateService.getSubordinateStatement(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id
        )
    }

    @PostMapping("/{id}/statement")
    fun publishSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable id: Int,
        @RequestBody dryRun: Boolean?
    ): String {
        return subordinateService.publishSubordinateStatement(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id,
            dryRun
        )
    }
}
