package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.PublishEntityStatementDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.EntityConfigurationStatementService
import jakarta.servlet.http.HttpServletRequest
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
        return entityConfigurationStatementService.findByUsername(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username
        )
    }

    @PostMapping
    fun publishEntityStatement(
        request: HttpServletRequest,
        @RequestBody body: PublishEntityStatementDTO?
    ): String {
        return entityConfigurationStatementService.publishByUsername(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            body?.dryRun ?: false
        )
    }
}
