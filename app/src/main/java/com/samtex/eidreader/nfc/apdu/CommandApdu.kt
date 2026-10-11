package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex
import java.io.ByteArrayOutputStream

/** ISO/IEC 7816-3 command APDU cases. */
enum class ApduCase(val label: String) {
    CASE_1("Case 1 (no data, no response data)"),
    CASE_2_SHORT("Case 2S (response data expected)"),
    CASE_3_SHORT("Case 3S (command data)"),
    CASE_4_SHORT("Case 4S (command and response data)"),
    CASE_2_EXTENDED("Case 2E (extended Le)"),
    CASE_3_EXTENDED("Case 3E (extended Lc)"),
    CASE_4_EXTENDED("Case 4E (extended Lc and Le)"),
}

/**
 * ISO/IEC 7816-4 command APDU.
 *
 * @param ne Maximum number of response bytes expected (Ne), or `null` when no response data is
 *   expected (Le field absent). Short APDUs support 1..256, extended APDUs 1..65536.
 */
class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    data: ByteArray = ByteArray(0),
    val ne: Int? = null,
) {
    private val dataBytes: ByteArray = data.copyOf()

    init {
        require(cla in 0..0xFF) { "CLA out of range" }
        require(ins in 0..0xFF) { "INS out of range" }
        require(p1 in 0..0xFF) { "P1 out of range" }
        require(p2 in 0..0xFF) { "P2 out of range" }
        require(dataBytes.size <= MAX_EXTENDED_NC) { "Command data exceeds $MAX_EXTENDED_NC bytes" }
        if (ne != null) require(ne in 1..MAX_EXTENDED_NE) { "Ne must be within 1..$MAX_EXTENDED_NE" }
    }

    val data: ByteArray get() = dataBytes.copyOf()

    /** Number of command data bytes (Nc). */
    val nc: Int get() = dataBytes.size

    val isExtended: Boolean get() = nc > MAX_SHORT_NC || (ne != null && ne > MAX_SHORT_NE)

    val case: ApduCase
        get() = when {
            nc == 0 && ne == null -> ApduCase.CASE_1
            nc == 0 -> if (isExtended) ApduCase.CASE_2_EXTENDED else ApduCase.CASE_2_SHORT
            ne == null -> if (isExtended) ApduCase.CASE_3_EXTENDED else ApduCase.CASE_3_SHORT
            else -> if (isExtended) ApduCase.CASE_4_EXTENDED else ApduCase.CASE_4_SHORT
        }

    /** Secure messaging indication bits of an inter-industry CLA (b4-b3). */
    val usesSecureMessaging: Boolean get() = (cla and 0x80) == 0 && (cla and 0x0C) != 0

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream(4 + 3 + nc + 3)
        out.write(cla)
        out.write(ins)
        out.write(p1)
        out.write(p2)
        if (!isExtended) {
            if (nc > 0) {
                out.write(nc)
                out.write(dataBytes, 0, nc)
            }
            if (ne != null) out.write(if (ne == MAX_SHORT_NE) 0 else ne)
        } else {
            out.write(0) // extended-length marker
            if (nc > 0) {
                out.write(nc ushr 8)
                out.write(nc and 0xFF)
                out.write(dataBytes, 0, nc)
            }
            if (ne != null) {
                val le = if (ne == MAX_EXTENDED_NE) 0 else ne
                out.write(le ushr 8)
                out.write(le and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun toHex(separator: String = " "): String = Hex.encode(encode(), separator)

    fun withNe(newNe: Int?): CommandApdu = CommandApdu(cla, ins, p1, p2, dataBytes, newNe)

    override fun equals(other: Any?): Boolean =
        other is CommandApdu && other.cla == cla && other.ins == ins && other.p1 == p1 &&
            other.p2 == p2 && other.ne == ne && other.dataBytes.contentEquals(dataBytes)

    override fun hashCode(): Int {
        var result = cla
        result = 31 * result + ins
        result = 31 * result + p1
        result = 31 * result + p2
        result = 31 * result + dataBytes.contentHashCode()
        result = 31 * result + (ne ?: 0)
        return result
    }

    override fun toString(): String = "CommandApdu(${toHex()})"

    companion object {
        const val MAX_SHORT_NC = 255
        const val MAX_SHORT_NE = 256
        const val MAX_EXTENDED_NC = 65535
        const val MAX_EXTENDED_NE = 65536

        /**
         * Parses an encoded command APDU following ISO/IEC 7816-3 case detection.
         *
         * @throws IllegalArgumentException if the byte sequence is not a well-formed APDU.
         */
        fun decode(bytes: ByteArray): CommandApdu {
            require(bytes.size >= 4) { "A command APDU needs at least 4 header bytes" }
            val cla = bytes[0].u()
            val ins = bytes[1].u()
            val p1 = bytes[2].u()
            val p2 = bytes[3].u()
            val len = bytes.size
            if (len == 4) return CommandApdu(cla, ins, p1, p2)
            val b0 = bytes[4].u()
            if (len == 5) {
                return CommandApdu(cla, ins, p1, p2, ne = if (b0 == 0) MAX_SHORT_NE else b0)
            }
            if (b0 != 0) {
                val lc = b0
                return when (len) {
                    5 + lc -> CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(5, 5 + lc))
                    6 + lc -> {
                        val le = bytes[len - 1].u()
                        CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(5, 5 + lc), if (le == 0) MAX_SHORT_NE else le)
                    }
                    else -> throw IllegalArgumentException(
                        "Length mismatch: Lc=$lc but APDU body is ${len - 5} bytes",
                    )
                }
            }
            // Extended length (B0 == 00)
            require(len >= 7) { "Malformed extended-length APDU" }
            val l = (bytes[5].u() shl 8) or bytes[6].u()
            if (len == 7) {
                return CommandApdu(cla, ins, p1, p2, ne = if (l == 0) MAX_EXTENDED_NE else l)
            }
            require(l != 0) { "Extended Lc must not be zero" }
            return when (len) {
                7 + l -> CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(7, 7 + l))
                9 + l -> {
                    val le = (bytes[len - 2].u() shl 8) or bytes[len - 1].u()
                    CommandApdu(cla, ins, p1, p2, bytes.copyOfRange(7, 7 + l), if (le == 0) MAX_EXTENDED_NE else le)
                }
                else -> throw IllegalArgumentException(
                    "Length mismatch: extended Lc=$l but APDU body is ${len - 7} bytes",
                )
            }
        }

        private fun Byte.u(): Int = toInt() and 0xFF
    }
}
