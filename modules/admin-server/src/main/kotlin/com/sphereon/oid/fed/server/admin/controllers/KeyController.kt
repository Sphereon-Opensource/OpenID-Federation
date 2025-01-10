package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.KeyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/keys")
class KeyController(
    private val keyService: KeyService
) {
    @PostMapping
    fun create(request: HttpServletRequest): JwkAdminDTO {
        return keyService.create(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id
        )
    }

    @GetMapping
    fun getKeys(request: HttpServletRequest): Array<JwkAdminDTO> {
        return keyService.getKeys(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id
        )
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        request: HttpServletRequest,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): JwkAdminDTO {
        return keyService.revokeKey(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).id,
            keyId,
            reason
        )
    }
}
