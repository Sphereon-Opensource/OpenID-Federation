package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateMetadataDTO
import com.sphereon.oid.fed.openapi.models.SubordinateMetadataDTO
import com.sphereon.oid.fed.services.SubordinateService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{accountUsername}/subordinates/{subordinateId}/metadata")
class SubordinateMetadataController {
    private val subordinateService = SubordinateService()

    @GetMapping
    fun get(
        @PathVariable accountUsername: String,
        @PathVariable subordinateId: Int
    ): Array<SubordinateMetadataDTO> {
        return subordinateService.findSubordinateMetadata(accountUsername, subordinateId)
    }

    @PostMapping
    fun create(
        @PathVariable accountUsername: String,
        @PathVariable subordinateId: Int,
        @RequestBody body: CreateMetadataDTO
    ): SubordinateMetadataDTO {
        return subordinateService.createMetadata(
            accountUsername,
            subordinateId,
            body.key,
            body.metadata
        )
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable accountUsername: String,
        @PathVariable subordinateId: Int,
        @PathVariable id: Int
    ): SubordinateMetadataDTO {
        return subordinateService.deleteSubordinateMetadata(accountUsername, subordinateId, id)
    }
}
