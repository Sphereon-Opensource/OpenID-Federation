package com.sphereon.openid.fed.core.config

/**
 * JS: no classpath packaging for reference.conf by default; rely on env + OidfConfigDefaults.
 * Node may later load files via fs if needed.
 */
actual object ClasspathResourceReader {
    actual fun readText(resourceName: String): String? = null
    actual fun readWorkingDirectoryFile(path: String): String? = null
}
