package com.samtex.eidreader.core.util

/**
 * Hexadecimal encoding / decoding helpers used throughout the NFC engine.
 *
 * Decoding is tolerant of common human formatting (spaces, colons, dashes and `0x` prefixes)
 * but strict about the digits themselves: any non-hex character or an odd digit count is rejected.
 */
object Hex {
    private const val DIGITS = "0123456789ABCDEF"
    private val SEPARATORS = Regex("[\\s:,\\-]+")

    fun encode(bytes: ByteArray, separator: String = ""): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        bytes.forEachIndexed { index, b ->
            if (index > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Formats a single unsigned byte value as two upper-case hex digits. */
    fun byte(value: Int): String {
        require(value in 0..0xFF) { "Byte value out of range: $value" }
        return "${DIGITS[value ushr 4]}${DIGITS[value and 0x0F]}"
    }

    /** Formats a 16-bit value as four upper-case hex digits. */
    fun u16(value: Int): String {
        require(value in 0..0xFFFF) { "16-bit value out of range: $value" }
        return byte(value ushr 8) + byte(value and 0xFF)
    }

    /**
     * Decodes a hex string. Accepts separators (whitespace, `:`, `,`, `-`) and an optional
     * `0x` prefix on each token.
     *
     * @throws IllegalArgumentException when the input contains invalid characters or an odd digit count.
     */
    fun decode(input: String): ByteArray {
        val clean = StringBuilder(input.length)
        for (token in input.trim().split(SEPARATORS)) {
            if (token.isEmpty()) continue
            val t = if (token.startsWith("0x") || token.startsWith("0X")) token.substring(2) else token
            clean.append(t)
        }
        require(clean.length % 2 == 0) { "Hex input must contain an even number of digits" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = digit(clean[2 * i])
            val lo = digit(clean[2 * i + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun decodeOrNull(input: String): ByteArray? = try {
        decode(input)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex character '$c'")
    }

    fun isPrintable(value: Int): Boolean = value in 0x20..0x7E

    /** Renders bytes as ASCII, replacing non-printable characters with '.'. */
    fun toPrintableAscii(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(if (isPrintable(v)) v.toChar() else '.')
        }
        return sb.toString()
    }

    /** True when every byte is printable 7-bit ASCII (and the array is non-empty). */
    fun isPrintableAscii(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes.all { isPrintable(it.toInt() and 0xFF) }
}

fun ByteArray.toHex(separator: String = ""): String = Hex.encode(this, separator)
