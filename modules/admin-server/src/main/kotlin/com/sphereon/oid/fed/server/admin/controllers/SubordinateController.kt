package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.openapi.models.SubordinateAdminDTO
import com.sphereon.oid.fed.openapi.models.SubordinateJwkDto
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.extensions.toSubordinateAdminDTO
import kotlinx.serialization.json.JsonObject
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{accountUsername}/subordinates")
class SubordinateController {
    private val subordinateService = SubordinateService()

    @GetMapping
    fun getSubordinates(@PathVariable accountUsername: String): Array<SubordinateAdminDTO> {
        return subordinateService.findSubordinatesByAccount(accountUsername).map { it.toSubordinateAdminDTO() }
            .toTypedArray()
    }

    @PostMapping
    fun createSubordinate(
        @PathVariable accountUsername: String,
        @RequestBody subordinate: CreateSubordinateDTO
    ): Subordinate {
        return subordinateService.createSubordinate(accountUsername, subordinate)
    }

    @PostMapping("/{id}/jwks")
    fun createSubordinateJwk(
        @PathVariable accountUsername: String,
        @PathVariable id: Int,
        @RequestBody jwk: JsonObject
    ): SubordinateJwkDto {
        return subordinateService.createSubordinateJwk(accountUsername, id, jwk)
    }

    @GetMapping("/{id}/jwks")
    fun getSubordinateJwks(
        @PathVariable accountUsername: String,
        @PathVariable id: Int
    ): Array<SubordinateJwkDto> {
        return subordinateService.getSubordinateJwks(accountUsername, id)
    }

    @DeleteMapping("/{id}/jwks/{jwkId}")
    fun deleteSubordinateJwk(
        @PathVariable accountUsername: String,
        @PathVariable id: Int,
        @PathVariable jwkId: Int
    ) {
        subordinateService.deleteSubordinateJwk(accountUsername, id, jwkId)
    }

    @GetMapping("/{id}/statement")
    fun getSubordinateStatement(
        @PathVariable accountUsername: String,
        @PathVariable id: Int
    ): SubordinateStatement {
        return subordinateService.getSubordinateStatement(accountUsername, id)
    }

    @PostMapping("/{id}/statement")
    fun publishSubordinateStatement(
        @PathVariable accountUsername: String,
        @PathVariable id: Int,
        @RequestBody dryRun: Boolean?
    ): String {
        return subordinateService.publishSubordinateStatement(accountUsername, id, dryRun)
    }
}
