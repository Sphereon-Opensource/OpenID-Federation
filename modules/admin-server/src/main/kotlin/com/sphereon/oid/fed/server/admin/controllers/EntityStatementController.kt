package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/entity-statement")
class EntityStatementController(
    private val entityConfigurationStatementService: EntityConfigurationStatementService
) {
    @GetMapping
    fun getEntityStatement(request: HttpServletRequest): EntityConfigurationStatement {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return entityConfigurationStatementService.findByAccount(account)
    }

    @PostMapping
    fun publishEntityStatement(
        request: HttpServletRequest,
        @RequestBody body: PublishStatementRequest?
    ): ResponseEntity<String> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        val result = entityConfigurationStatementService.publishByAccount(account, body?.dryRun)
        return if (body?.dryRun == true) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }
}
