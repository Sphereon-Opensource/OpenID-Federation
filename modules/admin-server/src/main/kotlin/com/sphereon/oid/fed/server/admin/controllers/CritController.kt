package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateCritDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Crit
import com.sphereon.oid.fed.services.CritService
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
@RequestMapping("/crits")
class CritController(
    private val critService: CritService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createCrit(
        request: HttpServletRequest,
        @RequestBody body: CreateCritDTO
    ): Crit {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return critService.create(account, body.claim)
    }

    @GetMapping
    fun getCrits(request: HttpServletRequest): Array<Crit> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return critService.findByAccountUsername(account)
    }

    @DeleteMapping("/{id}")
    fun deleteCrit(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Crit {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return critService.delete(account, id)
    }
}
