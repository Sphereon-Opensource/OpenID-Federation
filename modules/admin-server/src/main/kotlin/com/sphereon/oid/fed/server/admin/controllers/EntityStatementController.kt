package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.services.EntityStatementService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts/{accountUsername}/statement")
class EntityStatementController {
    private val entityStatementService = EntityStatementService()

    @GetMapping
    fun getEntityStatement(@PathVariable accountUsername: String): EntityConfigurationStatement {
        return entityStatementService.findByUsername(accountUsername)
    }

    @PostMapping
    fun publishEntityStatement(@PathVariable accountUsername: String): EntityConfigurationStatement {
        return entityStatementService.publishByUsername(accountUsername)
    }
}
