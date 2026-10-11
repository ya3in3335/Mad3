package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

/**
 * Minimal, defensive BER-TLV / DER parser (ISO/IEC 8825-1, ISO/IEC 7816-4).
 * Supports multi-byte tags, definite short and long lengths (up to 4 length bytes) and
 * constructed encodings. Indefinite lengths are rejected.
 */
class Tlv(
    val tag: Int,
    val tagBytes: Int,
    val constructed: Boolean,
    private val valueBytes: ByteArray,
    val children: List<Tlv>,
) {
    val value: ByteArray get() = valueBytes.copyOf()
    val length: Int get() = valueBytes.size
    val tagHex: String get() = Hex.encode(ByteArray(tagBytes) { i -> (tag ushr (8 * (tagBytes - 1 - i))).toByte() })

    fun find(tag: Int): Tlv? {
        if (this.tag == tag) return this
        for (c in children) c.find(tag)?.let { return it }
        return null
    }
}

class TlvParseException(message: String) : Exception(message)

object BerTlv {
    private const val MAX_DEPTH = 16

    /** Parses all TLV objects in [bytes]. @throws TlvParseException on malformed input. */
    fun parse(bytes: ByteArray): List<Tlv> = parse(bytes, 0, bytes.size, 0)

    fun parseOrNull(bytes: ByteArray): List<Tlv>? = try {
        parse(bytes).takeIf { it.isNotEmpty() }
    } catch (_: TlvParseException) {
        null
    }

    /** Returns header length and total encoded length of the first TLV in [bytes], or null if incomplete. */
    fun encodedLength(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        var i = 0
        if ((bytes[0].toInt() and 0x1F) == 0x1F) {
            i++
            while (i < bytes.size && (bytes[i].toInt() and 0x80) != 0) i++
        }
        i++
        if (i >= bytes.size) return null
        val first = bytes[i].toInt() and 0xFF
        i++
        val length: Int
        if (first < 0x80) {
            length = first
        } else {
            val n = first and 0x7F
            if (n == 0 || n > 4 || i + n > bytes.size) return null
            var l = 0
            repeat(n) { l = (l shl 8) or (bytes[i++].toInt() and 0xFF) }
            if (l < 0) return null
            length = l
        }
        return i + length
    }

    private fun parse(bytes: ByteArray, start: Int, end: Int, depth: Int): List<Tlv> {
        if (depth > MAX_DEPTH) throw TlvParseException("TLV nesting too deep")
        val out = mutableListOf<Tlv>()
        var i = start
        while (i < end) {
            val b0 = bytes[i].toInt() and 0xFF
            // Skip ISO/IEC 7816-4 padding bytes between objects.
            if (b0 == 0x00 || b0 == 0xFF) {
                i++
                continue
            }
            var tag = b0
            var tagBytes = 1
            i++
            if ((b0 and 0x1F) == 0x1F) {
                do {
                    if (i >= end) throw TlvParseException("Truncated tag")
                    val b = bytes[i++].toInt() and 0xFF
                    tag = (tag shl 8) or b
                    tagBytes++
                    if (tagBytes > 4) throw TlvParseException("Tag longer than 4 bytes")
                } while ((b and 0x80) != 0)
            }
            if (i >= end) throw TlvParseException("Missing length")
            val lb = bytes[i++].toInt() and 0xFF
            val length: Int = when {
                lb < 0x80 -> lb
                lb == 0x80 -> throw TlvParseException("Indefinite length not supported")
                else -> {
                    val n = lb and 0x7F
                    if (n > 4 || i + n > end) throw TlvParseException("Invalid long-form length")
                    var l = 0L
                    repeat(n) { l = (l shl 8) or (bytes[i++].toLong() and 0xFF) }
                    if (l > Int.MAX_VALUE) throw TlvParseException("Length too large")
                    l.toInt()
                }
            }
            if (length < 0 || i + length > end) throw TlvParseException("Value exceeds available data")
            val constructed = (b0 and 0x20) != 0
            val children = if (constructed) parse(bytes, i, i + length, depth + 1) else emptyList()
            out += Tlv(tag, tagBytes, constructed, bytes.copyOfRange(i, i + length), children)
            i += length
        }
        return out
    }

    /** Decodes a DER OBJECT IDENTIFIER value into dotted notation. */
    fun decodeOid(value: ByteArray): String {
        if (value.isEmpty()) throw TlvParseException("Empty OID")
        val parts = mutableListOf<Long>()
        var acc = 0L
        var first = true
        for ((idx, b) in value.withIndex()) {
            val v = b.toInt() and 0xFF
            acc = (acc shl 7) or (v and 0x7F).toLong()
            if (acc > Long.MAX_VALUE / 256) throw TlvParseException("OID arc too large")
            if ((v and 0x80) == 0) {
                if (first) {
                    val x = if (acc < 40) 0L else if (acc < 80) 1L else 2L
                    parts += x
                    parts += acc - 40 * x
                    first = false
                } else {
                    parts += acc
                }
                acc = 0
            } else if (idx == value.lastIndex) {
                throw TlvParseException("Truncated OID")
            }
        }
        return parts.joinToString(".")
    }

    /** Decodes a DER INTEGER (up to 8 bytes) as a signed long. */
    fun decodeInteger(value: ByteArray): Long {
        if (value.isEmpty() || value.size > 8) throw TlvParseException("Unsupported INTEGER length")
        var r = if (value[0] < 0) -1L else 0L
        for (b in value) r = (r shl 8) or (b.toLong() and 0xFF)
        return r
    }

    /** Human-readable indented rendering of a TLV tree (for the diagnostics console). */
    fun render(nodes: List<Tlv>, indent: String = ""): String = buildString {
        for (n in nodes) {
            append(indent).append(n.tagHex).append(" [").append(n.length).append("]")
            if (n.constructed) {
                append('\n').append(render(n.children, "$indent  "))
            } else {
                append(' ').append(Hex.encode(n.value)).append('\n')
            }
        }
    }
}
