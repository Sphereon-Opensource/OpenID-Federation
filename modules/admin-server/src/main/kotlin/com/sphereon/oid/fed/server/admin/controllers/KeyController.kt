package com.sphereon.oid.fed.server.admin.controllers


import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.CreateKey
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.CreateKeyArgs
import com.sphereon.oid.fed.services.JwkService
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/keys")
class KeyController(
    private val jwkService: JwkService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createKey(request: HttpServletRequest, @RequestBody body: CreateKey): AccountJwk {
        val account = getAccountFromRequest(request)
        return runBlocking { jwkService.createKey(account, CreateKeyArgs.fromModel(body)) }
    }

    @GetMapping
    fun getKeys(request: HttpServletRequest): Array<AccountJwk> {
        val account = getAccountFromRequest(request)
        return jwkService.getKeys(account)
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        request: HttpServletRequest,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): AccountJwk {
        val account = getAccountFromRequest(request)
        return jwkService.revokeKey(account, keyId, reason)
    }
}
