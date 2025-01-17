package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateMetadataDTO
import com.sphereon.oid.fed.openapi.models.SubordinateMetadataDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.SubordinateService
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
@RequestMapping("/subordinates/{subordinateId}/metadata")
class SubordinateMetadataController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun get(
        request: HttpServletRequest,
        @PathVariable subordinateId: Int
    ): Array<SubordinateMetadataDTO> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.findSubordinateMetadata(account, subordinateId)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        request: HttpServletRequest,
        @PathVariable subordinateId: Int,
        @RequestBody body: CreateMetadataDTO
    ): SubordinateMetadataDTO {
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
    ): SubordinateMetadataDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return subordinateService.deleteSubordinateMetadata(
            account,
            subordinateId,
            id
        )
    }
}
