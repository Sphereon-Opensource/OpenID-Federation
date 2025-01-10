package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateMetadataDTO
import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.EntityConfigurationMetadataService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/metadata")
class EntityConfigurationMetadataController(
    private val entityConfigurationMetadataService: EntityConfigurationMetadataService
) {
    @GetMapping
    fun getEntityConfigurationMetadata(request: HttpServletRequest): List<EntityConfigurationMetadataDTO> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return entityConfigurationMetadataService.findByAccount(account).toList()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createEntityConfigurationMetadata(
        request: HttpServletRequest,
        @RequestBody body: CreateMetadataDTO
    ): EntityConfigurationMetadataDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return entityConfigurationMetadataService.createEntityConfigurationMetadata(
            account,
            body.key,
            body.metadata
        )
    }

    @DeleteMapping("/{id}")
    fun deleteEntityConfigurationMetadata(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): EntityConfigurationMetadataDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return entityConfigurationMetadataService.deleteEntityConfigurationMetadata(
            account,
            id
        )
    }
}
