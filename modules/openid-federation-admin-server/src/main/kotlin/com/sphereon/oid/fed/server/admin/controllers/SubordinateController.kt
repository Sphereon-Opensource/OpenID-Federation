package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateSubordinate
import com.sphereon.oid.fed.openapi.java.models.Jwk
import com.sphereon.oid.fed.openapi.models.PublishStatementRequest
import com.sphereon.oid.fed.openapi.models.Subordinate
import com.sphereon.oid.fed.openapi.models.SubordinateJwk
import com.sphereon.oid.fed.openapi.models.SubordinateJwksResponse
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import com.sphereon.oid.fed.openapi.models.SubordinatesResponse
import com.sphereon.oid.fed.server.admin.mappers.toKotlin
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.mappers.toSubordinateJwksResponse
import com.sphereon.oid.fed.services.mappers.toSubordinatesResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import kotlinx.coroutines.runBlocking
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
import com.sphereon.oid.fed.openapi.models.CreateSubordinate as CreateSubordinateKotlin

@RestController
@RequestMapping("/subordinates")
class SubordinateController(
    private val subordinateService: SubordinateService
) {
    @GetMapping
    fun getSubordinates(request: HttpServletRequest): ResponseEntity<SubordinatesResponse> {
        return subordinateService.findSubordinatesByAccount(getAccountFromRequest(request)).toSubordinatesResponse()
            .let {
                ResponseEntity.ok(it)
            }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubordinate(
        request: HttpServletRequest,
        @Valid @RequestBody subordinate: CreateSubordinate,
        bindingResult: BindingResult
    ): ResponseEntity<Subordinate> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(
                subordinateService.createSubordinate(
                    getAccountFromRequest(request),
                    CreateSubordinateKotlin(identifier = subordinate.identifier)
                )
            )
    }

    @DeleteMapping("/{subordinateId}")
    fun deleteSubordinate(
        request: HttpServletRequest,
        @PathVariable subordinateId: String
    ): ResponseEntity<Subordinate> {
        return subordinateService.deleteSubordinate(getAccountFromRequest(request), subordinateId).let {
            ResponseEntity.ok(it)
        }
    }

    @PostMapping("/{subordinateId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @Valid @RequestBody jwk: Jwk,
        bindingResult: BindingResult
    ): ResponseEntity<SubordinateJwk> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return subordinateService.createSubordinateJwk(getAccountFromRequest(request), subordinateId, jwk.toKotlin())
            .let {
                ResponseEntity.status(HttpStatus.CREATED).body(it)
            }
    }

    @GetMapping("/{subordinateId}/keys")
    fun getSubordinateJwks(
        request: HttpServletRequest,
        @PathVariable subordinateId: String
    ): ResponseEntity<SubordinateJwksResponse> {
        return subordinateService.getSubordinateJwks(getAccountFromRequest(request), subordinateId)
            .toSubordinateJwksResponse()
            .let {
                ResponseEntity.ok(it)
            }
    }

    @DeleteMapping("/{subordinateId}/keys/{jwkId}")
    fun deleteSubordinateJwk(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @PathVariable jwkId: String
    ): ResponseEntity<SubordinateJwk> {
        return subordinateService.deleteSubordinateJwk(getAccountFromRequest(request), subordinateId, jwkId).let {
            ResponseEntity.ok(it)
        }
    }

    @GetMapping("/{subordinateId}/statement")
    fun getSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable subordinateId: String
    ): ResponseEntity<SubordinateStatement> {
        return subordinateService.getSubordinateStatement(getAccountFromRequest(request), subordinateId).let {
            ResponseEntity.ok(it)
        }
    }

    @PostMapping("/{subordinateId}/statement")
    fun publishSubordinateStatement(
        request: HttpServletRequest,
        @PathVariable subordinateId: String,
        @Valid @RequestBody body: PublishStatementRequest?,
        bindingResult: BindingResult
    ): ResponseEntity<String> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        val result = runBlocking {
            subordinateService.publishSubordinateStatement(
                account = getAccountFromRequest(request),
                id = subordinateId,
                dryRun = body?.dryRun,
                kmsKeyRef = body?.kmsKeyRef,
                kid = body?.kid
            )
        }

        return if (body?.dryRun == true) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }
}
