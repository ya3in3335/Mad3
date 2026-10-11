package com.samtex.eidreader.nfc.ats

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex

enum class HistoricalBytesFormat(val label: String) {
    EMPTY("No historical bytes"),
    COMPACT_TLV_WITH_STATUS("ISO/IEC 7816-4 compact-TLV, mandatory status indicator (category 00)"),
    DIR_DATA_REFERENCE("ISO/IEC 7816-4 DIR data reference (category 10)"),
    COMPACT_TLV("ISO/IEC 7816-4 compact-TLV (category 80)"),
    PROPRIETARY("Proprietary format (category indicator not defined by ISO/IEC 7816-4)"),
}

data class CompactTlvObject(val tag: Int, val value: ByteString) {
    val tagName: String
        get() = when (tag) {
            0x1 -> "Country code"
            0x2 -> "Issuer identification number"
            0x3 -> "Card service data"
            0x4 -> "Initial access data"
            0x5 -> "Card issuer's data"
            0x6 -> "Pre-issuing data"
            0x7 -> "Card capabilities"
            0x8 -> "Status indicator"
            0xF -> "Application identifier"
            else -> "Tag ${Hex.byte(tag)}"
        }
}

data class HistoricalBytesInfo(
    val raw: ByteString,
    val format: HistoricalBytesFormat,
    val categoryIndicator: Int?,
    val compactTlv: List<CompactTlvObject>,
    val statusIndicator: ByteString?,
    /** Printable ASCII rendering when every byte is printable, otherwise null. */
    val ascii: String?,
    val warnings: List<String>,
)

/** Parser for ISO/IEC 7816-4 historical bytes (as returned by IsoDep.getHistoricalBytes()). */
object HistoricalBytesParser {

    fun parse(bytes: ByteString?): HistoricalBytesInfo {
        if (bytes == null || bytes.isEmpty()) {
            return HistoricalBytesInfo(ByteString.EMPTY, HistoricalBytesFormat.EMPTY, null, emptyList(), null, null, emptyList())
        }
        val ascii = if (bytes.isPrintableAscii()) bytes.ascii() else null
        val category = bytes[0]
        val warnings = mutableListOf<String>()
        return when (category) {
            0x00 -> {
                if (bytes.size < 4) {
                    warnings += "Category 00 requires a 3-byte status indicator"
                    HistoricalBytesInfo(bytes, HistoricalBytesFormat.COMPACT_TLV_WITH_STATUS, category, emptyList(), null, ascii, warnings)
                } else {
                    val body = bytes.slice(1, bytes.size - 3)
                    val tlv = parseCompactTlv(body, warnings)
                    HistoricalBytesInfo(bytes, HistoricalBytesFormat.COMPACT_TLV_WITH_STATUS, category, tlv, bytes.slice(bytes.size - 3), ascii, warnings)
                }
            }
            0x10 -> HistoricalBytesInfo(bytes, HistoricalBytesFormat.DIR_DATA_REFERENCE, category, emptyList(), null, ascii, warnings)
            0x80 -> {
                val tlv = parseCompactTlv(bytes.slice(1), warnings)
                val status = tlv.lastOrNull { it.tag == 0x8 }?.value
                HistoricalBytesInfo(bytes, HistoricalBytesFormat.COMPACT_TLV, category, tlv, status, ascii, warnings)
            }
            else -> HistoricalBytesInfo(bytes, HistoricalBytesFormat.PROPRIETARY, category, emptyList(), null, ascii, warnings)
        }
    }

    fun parseCompactTlv(body: ByteString, warnings: MutableList<String> = mutableListOf()): List<CompactTlvObject> {
        val out = mutableListOf<CompactTlvObject>()
        var i = 0
        while (i < body.size) {
            val header = body[i++]
            val tag = header ushr 4
            val len = header and 0x0F
            if (i + len > body.size) {
                warnings += "Compact-TLV object ${Hex.byte(header)} truncated"
                break
            }
            out += CompactTlvObject(tag, body.slice(i, i + len))
            i += len
        }
        return out
    }
}
