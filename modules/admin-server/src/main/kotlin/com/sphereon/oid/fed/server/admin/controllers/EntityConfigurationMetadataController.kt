package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateMetadataDTO
import com.sphereon.oid.fed.openapi.models.EntityConfigurationMetadataDTO
import com.sphereon.oid.fed.services.EntityConfigurationMetadataService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{accountUsername}/metadata")
class EntityConfigurationMetadataController {
    private val entityConfigurationMetadataService = EntityConfigurationMetadataService()

    @GetMapping
    fun get(
        @PathVariable accountUsername: String
    ): Array<EntityConfigurationMetadataDTO> {
        return entityConfigurationMetadataService.findByAccountUsername(accountUsername)
    }

    @PostMapping
    fun create(
        @PathVariable accountUsername: String,
        @RequestBody body: CreateMetadataDTO
    ): EntityConfigurationMetadataDTO {
        return entityConfigurationMetadataService.createEntityConfigurationMetadata(
            accountUsername,
            body.key,
            body.metadata
        )
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable accountUsername: String,
        @PathVariable id: Int
    ): EntityConfigurationMetadataDTO {
        return entityConfigurationMetadataService.deleteEntityConfigurationMetadata(accountUsername, id)
    }
}
