package com.samtex.eidreader.diagnostics

/**
 * Small, dependency-free JSON serializer for report generation.
 * Supports Map<String, *>, Iterable<*>, String, Number, Boolean and null.
 */
object Json {

    fun encode(value: Any?, indent: String = "  "): String = StringBuilder().also { write(it, value, indent, "") }.toString()

    private fun write(sb: StringBuilder, value: Any?, indent: String, current: String) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Float -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Number -> sb.append(value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val next = current + indent
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(next)
                    quote(sb, k.toString())
                    sb.append(": ")
                    write(sb, v, indent, next)
                }
                sb.append('\n').append(current).append('}')
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                val next = current + indent
                items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",\n")
                    sb.append(next)
                    write(sb, v, indent, next)
                }
                sb.append('\n').append(current).append(']')
            }
            is Enum<*> -> quote(sb, value.name)
            else -> quote(sb, value.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}
