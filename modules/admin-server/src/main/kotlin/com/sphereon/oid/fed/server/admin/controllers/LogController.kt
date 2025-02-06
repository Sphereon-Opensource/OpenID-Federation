package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.Log
import com.sphereon.oid.fed.services.LogService
import com.sphereon.oid.fed.services.mappers.toDTO
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/logs")
class LogController(
    private val logService: LogService
) {
    @GetMapping
    fun getRecentLogs(
        @RequestParam(defaultValue = "100") limit: Long
    ): List<Log> = logService.getRecentLogs(limit).map { it.toDTO() }

    @GetMapping("/search")
    fun searchLogs(
        @RequestParam searchTerm: String,
        @RequestParam(defaultValue = "100") limit: Long
    ): List<Log> = logService.searchLogs(searchTerm, limit).map { it.toDTO() }

    @GetMapping("/severity/{severity}")
    fun getLogsBySeverity(
        @PathVariable severity: String,
        @RequestParam(defaultValue = "100") limit: Long
    ): List<Log> = logService.getLogsBySeverity(severity, limit).map { it.toDTO() }

    @GetMapping("/tag/{tag}")
    fun getLogsByTag(
        @PathVariable tag: String,
        @RequestParam(defaultValue = "100") limit: Long
    ): List<Log> = logService.getLogsByTag(tag, limit).map { it.toDTO() }
}