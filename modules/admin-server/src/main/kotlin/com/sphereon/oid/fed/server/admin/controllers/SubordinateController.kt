package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateSubordinateDTO
import com.sphereon.oid.fed.openapi.models.SubordinateAdminDTO
import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.extensions.toSubordinateAdminDTO
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
}
