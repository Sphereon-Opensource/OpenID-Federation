package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateMetadata
import com.sphereon.oid.fed.openapi.models.Metadata
import com.sphereon.oid.fed.openapi.models.MetadataResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.MetadataService
import com.sphereon.oid.fed.services.mappers.toMetadataResponse
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
@RequestMapping("/metadata")
class MetadataController(
    private val metadataService: MetadataService
) {
    @GetMapping
    fun getMetadata(request: HttpServletRequest): MetadataResponse {
        return metadataService.findByAccount(getAccountFromRequest(request)).toMetadataResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createMetadata(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateMetadata,
        bindingResult: BindingResult
    ): ResponseEntity<Metadata> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return metadataService.createMetadata(
            getAccountFromRequest(request),
            body.key,
            body.metadata
        ).let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
    }

    @DeleteMapping("/{id}")
    fun deleteMetadata(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<Metadata> {
        return metadataService.deleteMetadata(
            getAccountFromRequest(request),
            id
        ).let { ResponseEntity.ok(it) }
    }
}
