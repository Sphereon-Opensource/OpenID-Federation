package com.sphereon.oid.fed.server.admin.controllers


import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.AccountJwksResponse
import com.sphereon.oid.fed.openapi.models.CreateKey
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.CreateKeyArgs
import com.sphereon.oid.fed.services.JwkService
import com.sphereon.oid.fed.services.mappers.toAccountJwksResponse
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

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
    fun getKeys(request: HttpServletRequest): AccountJwksResponse {
        val account = getAccountFromRequest(request)
        return jwkService.getKeys(account).toAccountJwksResponse()
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
