package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{accountUsername}/entity-statement")
class EntityStatementController {
    private val entityConfigurationStatementService = EntityConfigurationStatementService()

    @GetMapping
    fun getEntityStatement(@PathVariable accountUsername: String): EntityConfigurationStatement {
        return entityConfigurationStatementService.findByUsername(accountUsername)
    }

    @PostMapping
    fun publishEntityStatement(@PathVariable accountUsername: String): EntityConfigurationStatement {
        return entityConfigurationStatementService.publishByUsername(accountUsername)
    }
}
