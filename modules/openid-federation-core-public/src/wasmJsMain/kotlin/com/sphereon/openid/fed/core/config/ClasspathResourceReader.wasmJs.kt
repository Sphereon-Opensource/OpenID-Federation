package com.sphereon.openid.fed.core.config

actual object ClasspathResourceReader {
    actual fun readText(resourceName: String): String? = null
    actual fun readWorkingDirectoryFile(path: String): String? = null
}
