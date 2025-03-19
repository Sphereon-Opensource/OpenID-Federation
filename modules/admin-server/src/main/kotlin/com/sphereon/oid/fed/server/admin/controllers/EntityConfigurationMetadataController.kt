package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.Metadata
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.MetadataService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/metadata")
class EntityConfigurationMetadataController(
    private val metadataService: MetadataService
) {
    @GetMapping
    fun getEntityConfigurationMetadata(request: HttpServletRequest): List<Metadata> {
        val account = getAccountFromRequest(request)
        return metadataService.findByAccount(account).toList()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createEntityConfigurationMetadata(
        request: HttpServletRequest,
        @RequestBody body: CreateMetadata
    ): Metadata {
        val account = getAccountFromRequest(request)
        return metadataService.createEntityConfigurationMetadata(
            account,
            body.key,
            body.metadata
        )
    }

    @DeleteMapping("/{id}")
    fun deleteEntityConfigurationMetadata(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Metadata {
        val account = getAccountFromRequest(request)
        return metadataService.deleteEntityConfigurationMetadata(
            account,
            id
        )
    }
}
