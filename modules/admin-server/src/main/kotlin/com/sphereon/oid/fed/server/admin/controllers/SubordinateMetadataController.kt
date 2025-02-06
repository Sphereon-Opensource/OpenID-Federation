package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.SubordinateMetadata
import com.sphereon.oid.fed.services.SubordinateService
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
        @PathVariable subordinateId: Int
    ): Array<SubordinateMetadata> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.findSubordinateMetadata(account, subordinateId)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        request: HttpServletRequest,
        @PathVariable subordinateId: Int,
        @RequestBody body: CreateMetadata
    ): SubordinateMetadata {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
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
        @PathVariable subordinateId: Int,
        @PathVariable id: Int
    ): SubordinateMetadata {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.deleteSubordinateMetadata(
            account,
            subordinateId,
            id
        )
    }
}
