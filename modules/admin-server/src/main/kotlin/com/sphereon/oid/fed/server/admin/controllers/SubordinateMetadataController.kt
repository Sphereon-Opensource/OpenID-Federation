package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.SubordinateMetadata
import com.sphereon.oid.fed.openapi.models.SubordinateMetadataResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.mappers.toSubordinateMetadataResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/subordinates/{subordinateId}/metadata")
class SubordinateMetadataController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun get(
        request: HttpServletRequest,
        @PathVariable subordinateId: String
    ): SubordinateMetadataResponse {
        val account = getAccountFromRequest(request)
        return subordinateService.findSubordinateMetadata(account, subordinateId).toSubordinateMetadataResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @RequestBody body: CreateMetadata
    ): SubordinateMetadata {
        val account = getAccountFromRequest(request)
        return subordinateService.createMetadata(
            account,
            subordinateId,
            body.key,
            body.metadata
        )
    }

    @DeleteMapping("/{id}")
    fun delete(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @PathVariable id: String
    ): SubordinateMetadata {
        val account = getAccountFromRequest(request)
        return subordinateService.deleteSubordinateMetadata(
            account,
            subordinateId,
            id
        )
    }
}
