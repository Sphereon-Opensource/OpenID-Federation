package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/entity-statement")
class EntityStatementController(
    private val entityConfigurationStatementService: EntityConfigurationStatementService
) {
    @GetMapping
    fun getEntityStatement(request: HttpServletRequest): EntityConfigurationStatement {
        val account = getAccountFromRequest(request)
        return entityConfigurationStatementService.findByAccount(account)
    }

    @PostMapping
    suspend fun publishEntityStatement(
        request: HttpServletRequest,
        @RequestBody body: PublishStatementRequest?
    ): ResponseEntity<String> {
        val account = getAccountFromRequest(request)
        val result = entityConfigurationStatementService.publishByAccount(account, dryRun = body?.dryRun, kmsKeyRef = body?.kmsKeyRef, kid = body?.kid)
        return if (body?.dryRun == true) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }
}
