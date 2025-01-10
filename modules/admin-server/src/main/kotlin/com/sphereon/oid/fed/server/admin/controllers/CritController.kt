package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.CreateCritDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.persistence.models.Crit
import com.sphereon.oid.fed.services.CritService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/crits")
class CritController(
    private val critService: CritService
) {
    @PostMapping
    fun createCrit(
        request: HttpServletRequest,
        @RequestBody body: CreateCritDTO
    ): Crit {
        return critService.create(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            body.claim
        )
    }

    @GetMapping
    fun getCrits(request: HttpServletRequest): Array<Crit> {
        return critService.findByAccountUsername(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username
        )
    }

    @DeleteMapping("/{id}")
    fun deleteCrit(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): Crit {
        return critService.delete(
            (request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account).username,
            id
        )
    }
}
