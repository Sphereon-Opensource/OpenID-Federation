package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.SubordinateMetadata
import com.sphereon.oid.fed.openapi.models.SubordinateMetadataResponse
import com.sphereon.oid.fed.server.admin.mappers.toJsonElement
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.mappers.toSubordinateMetadataResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
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
        @PathVariable subordinateId: String
    ): ResponseEntity<SubordinateMetadataResponse> {
        return subordinateService.findSubordinateMetadata(getAccountFromRequest(request), subordinateId)
            .toSubordinateMetadataResponse().let {
                ResponseEntity.ok(it)
            }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @Valid @RequestBody body: CreateMetadata,
        bindingResult: BindingResult
    ): ResponseEntity<SubordinateMetadata> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
            subordinateService.createMetadata(
                getAccountFromRequest(request),
                subordinateId,
                body.key,
                body.metadata.toJsonElement()
            )
        )
    }

    @DeleteMapping("/{id}")
    fun delete(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @PathVariable id: String
    ): ResponseEntity<SubordinateMetadata> {
        return subordinateService.deleteSubordinateMetadata(
            getAccountFromRequest(request),
            subordinateId,
            id
        ).let {
            ResponseEntity.ok(it)
        }
    }
}
