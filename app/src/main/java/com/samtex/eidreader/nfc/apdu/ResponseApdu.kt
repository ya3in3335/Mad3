package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

/** ISO/IEC 7816-4 response APDU: optional data field followed by the SW1-SW2 trailer. */
class ResponseApdu(raw: ByteArray) {
    private val bytes: ByteArray = raw.copyOf()

    init {
        require(bytes.size >= 2) { "A response APDU must contain at least the SW1 SW2 trailer" }
    }

    val data: ByteArray get() = bytes.copyOfRange(0, bytes.size - 2)
    val dataLength: Int get() = bytes.size - 2
    val sw1: Int get() = bytes[bytes.size - 2].toInt() and 0xFF
    val sw2: Int get() = bytes[bytes.size - 1].toInt() and 0xFF
    val sw: Int get() = (sw1 shl 8) or sw2
    val isSuccess: Boolean get() = sw == StatusWords.SW_SUCCESS

    fun encode(): ByteArray = bytes.copyOf()

    fun toHex(separator: String = " "): String = Hex.encode(bytes, separator)

    override fun equals(other: Any?): Boolean = other is ResponseApdu && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ResponseApdu(${toHex()})"

    companion object {
        fun of(data: ByteArray, sw: Int): ResponseApdu =
            ResponseApdu(data + byteArrayOf((sw ushr 8).toByte(), sw.toByte()))
    }
}
