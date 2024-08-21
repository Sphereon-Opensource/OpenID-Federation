package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateMetadataDTO
import com.sphereon.oid.fed.persistence.models.EntityConfigurationMetadata
import com.sphereon.oid.fed.services.EntityConfigurationMetadataService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts/{accountUsername}/metadata")
class EntityConfigurationMetadataController {
    private val entityConfigurationMetadataService = EntityConfigurationMetadataService()

    @GetMapping
    fun get(
        @PathVariable accountUsername: String
    ): Array<EntityConfigurationMetadata> {
        return entityConfigurationMetadataService.findByAccountUsername(accountUsername)
    }

    @PostMapping
    fun create(
        @PathVariable accountUsername: String,
        @RequestBody metadata: CreateMetadataDTO
    ): EntityConfigurationMetadata {
        return entityConfigurationMetadataService.createEntityConfigurationMetadata(
            accountUsername,
            metadata.key,
            metadata.value
        )
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable accountUsername: String,
        @PathVariable id: Int
    ): EntityConfigurationMetadata {
        return entityConfigurationMetadataService.deleteEntityConfigurationMetadata(accountUsername, id)
    }
}
