package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateAuthorityHint
import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.openapi.models.AuthorityHintsResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.mappers.toAuthorityHintsResponse
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
@RequestMapping("/authority-hints")
class AuthorityHintController(
    private val authorityHintService: AuthorityHintService
) {
    @GetMapping
    fun getAuthorityHints(request: HttpServletRequest): ResponseEntity<AuthorityHintsResponse> {
        return ResponseEntity.ok(
            authorityHintService.findByAccount(getAccountFromRequest(request)).toAuthorityHintsResponse()
        )
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAuthorityHint(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateAuthorityHint,
        bindingResult: BindingResult
    ): ResponseEntity<AuthorityHint> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(authorityHintService.createAuthorityHint(getAccountFromRequest(request), body.identifier))
    }

    @DeleteMapping("/{id}")
    fun deleteAuthorityHint(
        request: HttpServletRequest,
        @PathVariable id: String
    ): ResponseEntity<AuthorityHint> {
        return authorityHintService.deleteAuthorityHint(getAccountFromRequest(request), id)
            .let { ResponseEntity.ok(it) }
    }
}
