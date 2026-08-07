package com.sphereon.openid.fed.common.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Architecture guard: product and test Kotlin sources under `modules/` must not call
 * `System.getenv` directly. Process environment is accessed only via IDK [com.sphereon.core.api.conf.Env]
 * (and thus [rawGetenv] / [getEnvironmentVariable] / [OidfEnvBridgePropertySource]).
 *
 * Build scripts (Gradle) may still use System.getenv for credentials; they are out of scope.
 *
 * Allowed exceptions (file path contains any of these substrings): none currently —
 * even comments that only *mention* System.getenv are fine; only call-site patterns fail.
 */
class NoSystemGetenvArchTest {

    @Test
    fun modules_kotlin_sources_must_not_call_System_getenv() {
        val modulesRoot = findModulesRoot()
        val violations = mutableListOf<String>()

        modulesRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "build" !in it.toPath().map { p -> p.toString() } }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    // Skip pure comments
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    // Ban call sites: System.getenv( ...
                    if (SYSTEM_GETENV_CALL.containsMatchIn(line)) {
                        violations += "${relativePath(modulesRoot, file)}:${index + 1}: $trimmed"
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Found System.getenv call sites under modules/ (use IDK Env / getEnvironmentVariable instead):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        assertTrue(violations.isEmpty())
    }

    private fun findModulesRoot(): File {
        // jvmTest cwd is typically the module dir; walk up to repo root.
        var dir = File(".").canonicalFile
        repeat(8) {
            val modules = File(dir, "modules")
            if (modules.isDirectory && File(dir, "settings.gradle.kts").exists()) {
                return modules
            }
            dir = dir.parentFile ?: return@repeat
        }
        // Fallback: relative from common module
        val fromModule = File("../../").canonicalFile
        val modules = File(fromModule, "modules")
        assertTrue(modules.isDirectory, "Could not locate modules/ directory from ${File(".").canonicalPath}")
        return modules
    }

    private fun relativePath(root: File, file: File): String =
        file.canonicalPath.removePrefix(root.canonicalPath).trimStart(File.separatorChar)

    companion object {
        private val SYSTEM_GETENV_CALL = Regex("""\bSystem\.getenv\s*\(""")
    }
}
