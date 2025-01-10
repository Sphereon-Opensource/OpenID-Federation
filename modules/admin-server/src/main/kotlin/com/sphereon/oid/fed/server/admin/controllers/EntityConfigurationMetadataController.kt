package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateMetadataDTO
import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.EntityConfigurationMetadataService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/metadata")
class EntityConfigurationMetadataController(
    private val entityConfigurationMetadataService: EntityConfigurationMetadataService
) {
    @GetMapping
    fun get(request: HttpServletRequest): Array<EntityConfigurationMetadataDTO> {
        return entityConfigurationMetadataService.findByAccountUsername(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username
        )
    }

    @PostMapping
    fun create(
        request: HttpServletRequest,
        @RequestBody body: CreateMetadataDTO
    ): EntityConfigurationMetadataDTO {
        return entityConfigurationMetadataService.createEntityConfigurationMetadata(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            body.key,
            body.metadata
        )
    }

    @DeleteMapping("/{id}")
    fun delete(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): EntityConfigurationMetadataDTO {
        return entityConfigurationMetadataService.deleteEntityConfigurationMetadata(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id
        )
    }
}
