package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.server.admin.services.AccountService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/account")
class AccountController {
    private val accountService = AccountService()

    @GetMapping
    fun getAccounts(): List<AccountDTO> {
        return accountService.findAll()
    }

    @PostMapping
    fun createAccount(@RequestBody account: CreateAccountDTO) {
        return accountService.create(account)
    }
}
