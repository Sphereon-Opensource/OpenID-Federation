package com.sphereon.oid.fed.server.admin.controllers


import com.sphereon.oid.fed.openapi.java.models.CreateKey
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.openapi.models.AccountJwksResponse
import com.sphereon.oid.fed.server.admin.mappers.toKotlin
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.CreateKeyArgs
import com.sphereon.oid.fed.services.JwkService
import com.sphereon.oid.fed.services.mappers.toAccountJwksResponse
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import com.sphereon.oid.fed.openapi.models.CreateKey as CreateKeyKotlin

@RestController
@RequestMapping("/keys")
class KeyController(
    private val jwkService: JwkService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createKey(
        request: HttpServletRequest,
        @Valid @RequestBody(required = false) body: CreateKey?,
        bindingResult: BindingResult
    ): ResponseEntity<AccountJwk> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        val key = runBlocking {
            jwkService.createKey(
                getAccountFromRequest(request),
                CreateKeyArgs.fromModel(body?.toKotlin() ?: CreateKeyKotlin())
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(key)
    }

    @GetMapping
    fun getKeys(request: HttpServletRequest): ResponseEntity<AccountJwksResponse> {
        return jwkService.getKeys(getAccountFromRequest(request)).toAccountJwksResponse().let { ResponseEntity.ok(it) }
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        request: HttpServletRequest,
        @PathVariable keyId: String,
        @RequestParam reason: String?
    ): ResponseEntity<AccountJwk> {
        return jwkService.revokeKey(getAccountFromRequest(request), keyId, reason).let { ResponseEntity.ok(it) }
    }
}
