package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.persistence.models.Subordinate
import com.sphereon.oid.fed.services.SubordinateService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{accountUsername}/subordinates")
class SubordinateController {
    private val subordinateService = SubordinateService()

    @GetMapping
    fun getSubordinates(@PathVariable accountUsername: String): List<Subordinate> {
        return subordinateService.findSubordinatesByAccount(accountUsername)
    }
}