package com.samtex.eidreader.core.util

/**
 * Immutable byte sequence with value semantics, used in domain models instead of raw
 * [ByteArray] (which has identity-based equality and is mutable).
 */
class ByteString private constructor(private val bytes: ByteArray) {

    val size: Int get() = bytes.size

    fun isEmpty(): Boolean = bytes.isEmpty()

    /** Returns the unsigned value (0..255) at [index]. */
    operator fun get(index: Int): Int = bytes[index].toInt() and 0xFF

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun hex(separator: String = ""): String = Hex.encode(bytes, separator)

    fun ascii(): String = Hex.toPrintableAscii(bytes)

    fun isPrintableAscii(): Boolean = Hex.isPrintableAscii(bytes)

    fun slice(from: Int, toExclusive: Int = size): ByteString =
        ByteString(bytes.copyOfRange(from, toExclusive))

    override fun equals(other: Any?): Boolean =
        other is ByteString && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = hex()

    companion object {
        val EMPTY = ByteString(ByteArray(0))

        fun of(bytes: ByteArray): ByteString = ByteString(bytes.copyOf())

        fun of(vararg values: Int): ByteString =
            ByteString(ByteArray(values.size) { values[it].toByte() })

        fun fromHex(hex: String): ByteString = ByteString(Hex.decode(hex))
    }
}

fun ByteArray.toByteString(): ByteString = ByteString.of(this)
