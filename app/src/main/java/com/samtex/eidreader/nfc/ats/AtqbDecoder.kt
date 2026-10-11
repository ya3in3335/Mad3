package com.samtex.eidreader.nfc.ats

import com.samtex.eidreader.core.util.ByteString

/** Decoded ISO/IEC 14443-3 Type B ATQB protocol information (as exposed by `NfcB.getProtocolInfo()`). */
data class AtqbInfo(
    val applicationData: ByteString,
    val protocolInfo: ByteString,
    val sameBitRateBothDirections: Boolean,
    val supportedBitRatesPiccToPcd: List<Int>,
    val supportedBitRatesPcdToPicc: List<Int>,
    /** Maximum frame size code and its value in bytes (null when RFU). */
    val maxFrameSizeCode: Int,
    val maxFrameSize: Int?,
    /** Protocol type b1: PICC compliant with ISO/IEC 14443-4. */
    val iso14443_4Compliant: Boolean,
    val fwi: Int,
    val fwtMicros: Double,
    val nadSupported: Boolean,
    val cidSupported: Boolean,
    val warnings: List<String>,
)

/**
 * Decoder for the ATQB "Protocol Info" field (ISO/IEC 14443-3 Type B):
 * byte 1 = bit rate capability, byte 2 = max frame size (high nibble) | protocol type (low nibble),
 * byte 3 = FWI (high nibble) | ADC (b4-b3) | FO (b2 = NAD, b1 = CID).
 */
object AtqbDecoder {
    private val FRAME_SIZES = intArrayOf(16, 24, 32, 40, 48, 64, 96, 128, 256, 512, 1024, 2048, 4096)
    private const val FWT_BASE_MICROS = 256.0 * 16.0 / 13.56

    fun decode(applicationData: ByteString, protocolInfo: ByteString): AtqbInfo? {
        if (protocolInfo.size < 3) return null
        val warnings = mutableListOf<String>()
        val rates = protocolInfo[0]
        val piccToPcd = mutableListOf(106)
        if ((rates and 0x10) != 0) piccToPcd += 212
        if ((rates and 0x20) != 0) piccToPcd += 424
        if ((rates and 0x40) != 0) piccToPcd += 848
        val pcdToPicc = mutableListOf(106)
        if ((rates and 0x01) != 0) pcdToPicc += 212
        if ((rates and 0x02) != 0) pcdToPicc += 424
        if ((rates and 0x04) != 0) pcdToPicc += 848
        if ((rates and 0x08) != 0) warnings += "Bit rate capability b4 is set (RFU)"

        val frameCode = protocolInfo[1] ushr 4
        val protocolType = protocolInfo[1] and 0x0F
        if (frameCode > 12) warnings += "Maximum frame size code $frameCode is RFU"
        if ((protocolType and 0x08) != 0) warnings += "Protocol type b4 is set (RFU)"

        var fwi = protocolInfo[2] ushr 4
        if (fwi == 15) {
            warnings += "FWI=15 is RFU; default FWI=4 assumed"
            fwi = 4
        }
        return AtqbInfo(
            applicationData = applicationData,
            protocolInfo = protocolInfo,
            sameBitRateBothDirections = (rates and 0x80) != 0,
            supportedBitRatesPiccToPcd = piccToPcd,
            supportedBitRatesPcdToPicc = pcdToPicc,
            maxFrameSizeCode = frameCode,
            maxFrameSize = FRAME_SIZES.getOrNull(frameCode),
            iso14443_4Compliant = (protocolType and 0x01) != 0,
            fwi = fwi,
            fwtMicros = FWT_BASE_MICROS * (1 shl fwi),
            nadSupported = (protocolInfo[2] and 0x02) != 0,
            cidSupported = (protocolInfo[2] and 0x01) != 0,
            warnings = warnings,
        )
    }
}
