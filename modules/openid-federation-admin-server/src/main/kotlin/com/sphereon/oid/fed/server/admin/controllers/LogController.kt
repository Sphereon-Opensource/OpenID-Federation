package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.Log
import com.sphereon.oid.fed.services.LogService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/logs")
class LogController(
    private val logService: LogService
) {
    @GetMapping
    fun getRecentLogs(
        @RequestParam(defaultValue = "100") limit: Long
    ): ResponseEntity<List<Log>> {
        return logService.getRecentLogs(limit).let { ResponseEntity.ok(it) }
    }

    @GetMapping("/search")
    fun searchLogs(
        @RequestParam searchTerm: String,
        @RequestParam(defaultValue = "100") limit: Long
    ): ResponseEntity<List<Log>> {
        return logService.searchLogs(searchTerm, limit).let { ResponseEntity.ok(it) }
    }

    @GetMapping("/severity/{severity}")
    fun getLogsBySeverity(
        @PathVariable severity: String,
        @RequestParam(defaultValue = "100") limit: Long
    ): ResponseEntity<List<Log>> {
        return logService.getLogsBySeverity(severity, limit).let { ResponseEntity.ok(it) }
    }

    @GetMapping("/tag/{tag}")
    fun getLogsByTag(
        @PathVariable tag: String,
        @RequestParam(defaultValue = "100") limit: Long
    ): ResponseEntity<List<Log>> {
        return logService.getLogsByTag(tag, limit).let { ResponseEntity.ok(it) }
    }
}
