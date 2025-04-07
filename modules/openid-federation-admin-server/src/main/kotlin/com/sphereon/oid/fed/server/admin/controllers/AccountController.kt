package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AccountsResponse
import com.sphereon.oid.fed.server.admin.mappers.toKotlin
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.mappers.toAccountsResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val accountService: AccountService
) {
    @GetMapping
    fun getAccounts(): ResponseEntity<AccountsResponse> {
        return accountService.getAllAccounts().toAccountsResponse().let { ResponseEntity.ok(it) }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @Valid @RequestBody account: CreateAccount,
        bindingResult: BindingResult
    ): ResponseEntity<Account> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(account.toKotlin()))
    }

    @DeleteMapping
    fun deleteAccount(request: HttpServletRequest): ResponseEntity<Account> {
        return ResponseEntity.status(HttpStatus.OK).body(accountService.deleteAccount(getAccountFromRequest(request)))
    }
}
