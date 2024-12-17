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
@RequestMapping("/accounts/{username}/subordinates")
class SubordinateController {
    private val subordinateService = SubordinateService()

    @GetMapping
    fun getSubordinates(@PathVariable username: String): Array<SubordinateAdminDTO> {
        return subordinateService.findSubordinatesByAccount(username).map { it.toSubordinateAdminDTO() }
            .toTypedArray()
    }

    @PostMapping
    fun createSubordinate(
        @PathVariable username: String,
        @RequestBody subordinate: CreateSubordinateDTO
    ): Subordinate {
        return subordinateService.createSubordinate(username, subordinate)
    }

    @DeleteMapping("/{id}")
    fun deleteSubordinate(
        @PathVariable username: String,
        @PathVariable id: Int
    ): Subordinate {
        return subordinateService.deleteSubordinate(username, id)
    }

    @PostMapping("/{id}/jwks")
    fun createSubordinateJwk(
        @PathVariable username: String,
        @PathVariable id: Int,
        @RequestBody jwk: JsonObject
    ): SubordinateJwkDto {
        return subordinateService.createSubordinateJwk(username, id, jwk)
    }

    @GetMapping("/{id}/jwks")
    fun getSubordinateJwks(
        @PathVariable username: String,
        @PathVariable id: Int
    ): Array<SubordinateJwkDto> {
        return subordinateService.getSubordinateJwks(username, id)
    }

    @DeleteMapping("/{id}/jwks/{jwkId}")
    fun deleteSubordinateJwk(
        @PathVariable username: String,
        @PathVariable id: Int,
        @PathVariable jwkId: Int
    ) {
        subordinateService.deleteSubordinateJwk(username, id, jwkId)
    }

    @GetMapping("/{id}/statement")
    fun getSubordinateStatement(
        @PathVariable username: String,
        @PathVariable id: Int
    ): SubordinateStatement {
        return subordinateService.getSubordinateStatement(username, id)
    }

    @PostMapping("/{id}/statement")
    fun publishSubordinateStatement(
        @PathVariable username: String,
        @PathVariable id: Int,
        @RequestBody dryRun: Boolean?
    ): String {
        return subordinateService.publishSubordinateStatement(username, id, dryRun)
    }
}
