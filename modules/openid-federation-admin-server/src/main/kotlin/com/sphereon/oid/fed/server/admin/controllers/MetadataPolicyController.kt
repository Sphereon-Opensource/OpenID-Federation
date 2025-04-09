package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateMetadataPolicy
import com.sphereon.oid.fed.openapi.models.MetadataPolicy
import com.sphereon.oid.fed.openapi.models.MetadataPolicyResponse
import com.sphereon.oid.fed.server.admin.mappers.toJsonElement
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.MetadataPolicyService
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
@RequestMapping("/metadata-policy")
class MetadataPolicyController(
    private val metadataPolicyService: MetadataPolicyService
) {
    @GetMapping
    fun getMetadataPolicies(request: HttpServletRequest): ResponseEntity<MetadataPolicyResponse> {
        return metadataPolicyService.findByAccount(getAccountFromRequest(request)).let {
            ResponseEntity.ok(MetadataPolicyResponse(it.toTypedArray()))
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createMetadataPolicy(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateMetadataPolicy,
        bindingResult: BindingResult
    ): ResponseEntity<MetadataPolicy> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return metadataPolicyService.createPolicy(
            getAccountFromRequest(request),
            body.key,
            body.policy.toJsonElement()
        ).let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
    }

    @DeleteMapping("/{id}")
    fun deleteMetadataPolicy(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<MetadataPolicy> {
        return metadataPolicyService.deletePolicy(
            getAccountFromRequest(request),
            id
        ).let { ResponseEntity.ok(it) }
    }
}
