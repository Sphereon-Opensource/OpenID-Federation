package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.services.LogService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/logs")
class LogController(
    private val logService: LogService
) {
    @GetMapping
    fun getRecentLogs(
        @RequestParam(defaultValue = "100") limit: Long
    ) = logService.getRecentLogs(limit)

    @GetMapping("/search")
    fun searchLogs(
        @RequestParam searchTerm: String,
        @RequestParam(defaultValue = "100") limit: Long
    ) = logService.searchLogs(searchTerm, limit)

    @GetMapping("/severity/{severity}")
    fun getLogsBySeverity(
        @PathVariable severity: String,
        @RequestParam(defaultValue = "100") limit: Long
    ) = logService.getLogsBySeverity(severity, limit)

    @GetMapping("/tag/{tag}")
    fun getLogsByTag(
        @PathVariable tag: String,
        @RequestParam(defaultValue = "100") limit: Long
    ) = logService.getLogsByTag(tag, limit)
}