package com.sphereon.openid.fed.core.config

import java.io.File

actual object ClasspathResourceReader {
    actual fun readText(resourceName: String): String? {
        val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(resourceName)
            ?: ClasspathResourceReader::class.java.classLoader?.getResourceAsStream(resourceName)
            ?: ClassLoader.getSystemResourceAsStream(resourceName)
            ?: return null
        return stream.bufferedReader().use { it.readText() }
    }

    actual fun readWorkingDirectoryFile(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }
}
