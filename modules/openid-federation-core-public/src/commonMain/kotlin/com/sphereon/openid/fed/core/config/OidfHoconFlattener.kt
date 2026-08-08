package com.sphereon.openid.fed.core.config

/**
 * Minimal HOCON-ish flattener for OIDF `reference.conf` style files.
 *
 * ## Scope (developer boundary)
 * Supports only what OIDF ships today:
 * - Nested `{ }` blocks
 * - `key = value` / `key: value` assignments
 * - Quoted strings, bare tokens, numbers, booleans
 * - `#` and `//` line comments
 *
 * Does **not** implement full HOCON (includes, substitutions, multi-line, lists as arrays).
 * Complex needs should use flat `reference.properties` or IDK YAML property sources.
 */
object OidfHoconFlattener {

    fun flatten(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val stack = ArrayDeque<String>()
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            // Strip comments outside of quotes (# and //)
            line = stripCommentsOutsideQuotes(line)
            if (line.isEmpty()) continue

            when {
                line == "}" -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
                line.endsWith("{") -> {
                    val name = line.removeSuffix("{").trim().trimEnd('=', ':').trim()
                    if (name.isNotEmpty()) stack.addLast(name)
                }
                else -> {
                    val sep = when {
                        line.contains('=') -> '='
                        line.contains(':') -> ':'
                        else -> null
                    } ?: continue
                    val idx = line.indexOf(sep)
                    val key = line.substring(0, idx).trim()
                    var value = line.substring(idx + 1).trim().trimEnd(',')
                    if (value.endsWith("{")) {
                        // rare: key { on same line without =
                        val blockName = key
                        if (blockName.isNotEmpty()) stack.addLast(blockName)
                        continue
                    }
                    value = unquote(value)
                    if (key.isEmpty() || value.isEmpty()) continue
                    val fullKey = (stack + key).joinToString(".")
                    result[fullKey] = value
                }
            }
        }
        return result
    }

    private fun unquote(value: String): String {
        val v = value.trim()
        return if (v.length >= 2 &&
            ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))
        ) {
            v.substring(1, v.length - 1)
        } else {
            v
        }
    }

    /**
     * Remove `#` / `//` comments that are not inside single- or double-quoted strings.
     * Required so values like `"http://localhost"` are not truncated at `//`.
     */
    private fun stripCommentsOutsideQuotes(line: String): String {
        var inDouble = false
        var inSingle = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\'' && !inDouble -> inSingle = !inSingle
                !inDouble && !inSingle && c == '#' -> return line.substring(0, i).trim()
                !inDouble && !inSingle && c == '/' && i + 1 < line.length && line[i + 1] == '/' ->
                    return line.substring(0, i).trim()
            }
            i++
        }
        return line.trim()
    }
}
