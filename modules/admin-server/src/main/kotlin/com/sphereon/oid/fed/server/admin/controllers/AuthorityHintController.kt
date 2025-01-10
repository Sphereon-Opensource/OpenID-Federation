package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.AuthorityHintDTO
import com.sphereon.oid.fed.openapi.models.CreateAuthorityHintDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AuthorityHintService
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
@RequestMapping("/authority-hints")
class AuthorityHintController(
    private val authorityHintService: AuthorityHintService
) {
    @GetMapping
    fun getAuthorityHints(request: HttpServletRequest): List<AuthorityHintDTO> {
        return authorityHintService.findByAccount(
            request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        )
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAuthorityHint(
        request: HttpServletRequest,
        @RequestBody body: CreateAuthorityHintDTO
    ): AuthorityHintDTO {
        return authorityHintService.createAuthorityHint(
            request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account,
            body.identifier
        )
    }

    @DeleteMapping("/{id}")
    fun deleteAuthorityHint(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): AuthorityHintDTO {
        return authorityHintService.deleteAuthorityHint(
            request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account,
            id
        )
    }
}
