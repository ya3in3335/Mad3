package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

/**
 * Factory for the small set of documented, read-only ISO/IEC 7816-4 commands SAMTEX uses.
 * Nothing here authenticates, writes, verifies PINs or establishes secure messaging.
 */
object ApduCommands {
    const val INS_SELECT = 0xA4
    const val INS_READ_BINARY = 0xB0
    const val INS_GET_RESPONSE = 0xC0

    /** ICAO Doc 9303-10 LDS1 eMRTD application identifier. */
    val AID_ICAO_EMRTD: ByteArray = Hex.decode("A0000002471001")

    /** ICAO Doc 9303-10: EF.CardAccess (in the MF, readable without authentication). */
    const val FID_EF_CARD_ACCESS = 0x011C

    /** ISO/IEC 7816-4: EF.DIR (application directory, in the MF). */
    const val FID_EF_DIR = 0x2F00

    /** ISO/IEC 7816-4: EF.ATR/INFO (in the MF). */
    const val FID_EF_ATR_INFO = 0x2F01

    /** ISO/IEC 7816-4: Master File identifier. */
    const val FID_MF = 0x3F00

    /** SELECT by DF name (AID), first occurrence, no response data requested (P2 = 0C). */
    fun selectByAid(aid: ByteArray): CommandApdu {
        require(aid.size in 5..16) { "An AID must be 5 to 16 bytes long" }
        return CommandApdu(0x00, INS_SELECT, 0x04, 0x0C, aid)
    }

    /** SELECT the Master File (P1 = 00, FID 3F00, no response data). */
    fun selectMasterFile(): CommandApdu =
        CommandApdu(0x00, INS_SELECT, 0x00, 0x0C, fid(FID_MF))

    /** SELECT an EF under the current DF by file identifier (P1 = 02, no response data). */
    fun selectEf(fid: Int): CommandApdu =
        CommandApdu(0x00, INS_SELECT, 0x02, 0x0C, fid(fid))

    /** READ BINARY from the currently selected EF at a 15-bit offset. */
    fun readBinary(offset: Int, length: Int): CommandApdu {
        require(offset in 0..0x7FFF) { "READ BINARY offset must fit in 15 bits" }
        require(length in 1..CommandApdu.MAX_SHORT_NE) { "READ BINARY length must be 1..256 for short APDUs" }
        return CommandApdu(0x00, INS_READ_BINARY, offset ushr 8, offset and 0xFF, ne = length)
    }

    /** GET RESPONSE used to retrieve remaining bytes after a 61XX status word. */
    fun getResponse(length: Int): CommandApdu =
        CommandApdu(0x00, INS_GET_RESPONSE, 0x00, 0x00, ne = length.coerceIn(1, CommandApdu.MAX_SHORT_NE))

    private fun fid(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "File identifier out of range" }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }
}
