package com.samtex.eidreader.nfc.ats

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex

enum class UidSize(val bytes: Int, val label: String) {
    SINGLE(4, "single (4 bytes)"),
    DOUBLE(7, "double (7 bytes)"),
    TRIPLE(10, "triple (10 bytes)"),
    RFU(0, "RFU (invalid)"),
}

data class AtqaInfo(
    /** ATQA as a 16-bit value in conventional notation (e.g. 0x0008). */
    val value: Int,
    val uidSize: UidSize,
    /** Bit frame anticollision bits b5..b1 (exactly one should be set). */
    val bitFrameAnticollision: Int,
    val proprietaryCoding: Int,
    val compliant: Boolean,
) {
    val hex: String get() = "0x" + Hex.u16(value)
}

data class SakInfo(
    val value: Int,
    /** b6 set: PICC compliant with ISO/IEC 14443-4. */
    val iso14443_4Compliant: Boolean,
    /** b3 set: UID not complete (cascade level follows). */
    val uidIncomplete: Boolean,
    /** b7 set: compliant with ISO/IEC 18092 (NFC-DEP). */
    val nfcDepCompliant: Boolean,
    /** Product families commonly associated with this SAK (NXP AN10833). Inferred only. */
    val commonlyAssociatedWith: List<String>,
) {
    val hex: String get() = "0x" + Hex.byte(value)
}

/** Decoding of ISO/IEC 14443-3 Type A ATQA and SAK values. */
object AtqaSakDecoder {

    /**
     * Decodes ATQA. Android's `NfcA.getAtqa()` returns the two bytes in reception order
     * (LSB first), so `[0x08, 0x00]` is ATQA 0x0008.
     */
    fun decodeAtqa(androidBytes: ByteString): AtqaInfo? {
        if (androidBytes.size != 2) return null
        val value = (androidBytes[1] shl 8) or androidBytes[0]
        val lsb = value and 0xFF
        val uidSize = when ((lsb ushr 6) and 0x03) {
            0 -> UidSize.SINGLE
            1 -> UidSize.DOUBLE
            2 -> UidSize.TRIPLE
            else -> UidSize.RFU
        }
        val anticollision = lsb and 0x1F
        val rfuBit6 = (lsb and 0x20) != 0
        val rfuHigh = (value ushr 12) and 0x0F
        val oneBitSet = anticollision != 0 && (anticollision and (anticollision - 1)) == 0
        return AtqaInfo(
            value = value,
            uidSize = uidSize,
            bitFrameAnticollision = anticollision,
            proprietaryCoding = (value ushr 8) and 0x0F,
            compliant = uidSize != UidSize.RFU && !rfuBit6 && rfuHigh == 0 && oneBitSet,
        )
    }

    fun decodeSak(sak: Int): SakInfo {
        require(sak in 0..0xFF) { "SAK out of range" }
        val associations = when (sak) {
            0x00 -> listOf("MIFARE Ultralight / NTAG family")
            0x08 -> listOf("MIFARE Classic 1K", "MIFARE Plus (SL1, 2K)")
            0x09 -> listOf("MIFARE Mini")
            0x10 -> listOf("MIFARE Plus 2K (SL2)")
            0x11 -> listOf("MIFARE Plus 4K (SL2)")
            0x18 -> listOf("MIFARE Classic 4K", "MIFARE Plus (SL1, 4K)")
            0x20 -> listOf("ISO/IEC 14443-4 smart card (e.g. eID/ePassport, DESFire, JavaCard)")
            0x28 -> listOf("ISO/IEC 14443-4 card with MIFARE Classic 1K emulation")
            0x38 -> listOf("ISO/IEC 14443-4 card with MIFARE Classic 4K emulation")
            0x88 -> listOf("MIFARE Classic 1K (Infineon)")
            else -> emptyList()
        }
        return SakInfo(
            value = sak,
            iso14443_4Compliant = (sak and 0x20) != 0,
            uidIncomplete = (sak and 0x04) != 0,
            nfcDepCompliant = (sak and 0x40) != 0,
            commonlyAssociatedWith = associations,
        )
    }
}
