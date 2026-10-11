package com.samtex.eidreader.nfc.ats

import com.samtex.eidreader.core.util.ByteString

/** Decoded ISO/IEC 14443-4 Answer To Select (complete frame without CRC). */
data class AtsInfo(
    val raw: ByteString,
    val tl: Int,
    val fsci: Int,
    /** Maximum frame size the PICC can receive, in bytes (null when FSCI is RFU). */
    val fsc: Int?,
    val ta: Int?,
    val tb: Int?,
    val tc: Int?,
    val sameBitRateBothDirections: Boolean,
    val supportedBitRatesPiccToPcd: List<Int>,
    val supportedBitRatesPcdToPicc: List<Int>,
    val fwi: Int,
    val sfgi: Int,
    val fwtMicros: Double,
    val sfgtMicros: Double,
    val nadSupported: Boolean,
    val cidSupported: Boolean,
    val historicalBytes: ByteString,
    val warnings: List<String>,
)

/**
 * Parser for complete ATS frames.
 *
 * **Important**: Android does not expose the full ATS. `IsoDep.getHistoricalBytes()` returns
 * only the historical bytes (T1..Tk). This analyzer is therefore used for complete ATS frames
 * obtained from other sources (e.g. external reader logs pasted into diagnostics or test
 * fixtures); for Android scans SAMTEX analyses historical bytes with [HistoricalBytesParser].
 */
object AtsAnalyzer {

    private val FSC_TABLE = intArrayOf(16, 24, 32, 40, 48, 64, 96, 128, 256, 512, 1024, 2048, 4096)

    /** One elementary time unit (ETU) scaled factor: 256 × 16 / fc, fc = 13.56 MHz → µs. */
    private const val FWT_BASE_MICROS = 256.0 * 16.0 / 13.56

    sealed interface Result {
        data class Success(val info: AtsInfo) : Result
        data class Failure(val reason: String) : Result
    }

    fun parse(ats: ByteArray): Result {
        if (ats.isEmpty()) return Result.Failure("ATS is empty")
        val warnings = mutableListOf<String>()
        val tl = ats[0].toInt() and 0xFF
        if (tl != ats.size) {
            if (tl == ats.size - 2) {
                return Result.Failure("ATS appears to include CRC_A bytes; provide the frame without CRC")
            }
            return Result.Failure("Length byte TL=$tl does not match the frame length ${ats.size}")
        }
        if (tl == 1) {
            return Result.Success(defaults(ByteString.of(ats), tl, warnings))
        }
        val t0 = ats[1].toInt() and 0xFF
        if ((t0 and 0x80) != 0) warnings += "T0 bit b8 is set (must be 0)"
        val fsci = t0 and 0x0F
        var idx = 2
        fun next(present: Boolean): Int? {
            if (!present) return null
            if (idx >= ats.size) return null
            return ats[idx++].toInt() and 0xFF
        }
        val taPresent = (t0 and 0x10) != 0
        val tbPresent = (t0 and 0x20) != 0
        val tcPresent = (t0 and 0x40) != 0
        val ta = next(taPresent)
        val tb = next(tbPresent)
        val tc = next(tcPresent)
        if ((taPresent && ta == null) || (tbPresent && tb == null) || (tcPresent && tc == null)) {
            return Result.Failure("ATS truncated: interface bytes announced by T0 are missing")
        }
        val historical = ByteString.of(ats.copyOfRange(idx, ats.size))

        val taValue = ta ?: 0x00
        if ((taValue and 0x08) != 0) warnings += "TA bit b4 is set (RFU, must be 0)"
        val piccToPcd = mutableListOf(106)
        if ((taValue and 0x10) != 0) piccToPcd += 212
        if ((taValue and 0x20) != 0) piccToPcd += 424
        if ((taValue and 0x40) != 0) piccToPcd += 848
        val pcdToPicc = mutableListOf(106)
        if ((taValue and 0x01) != 0) pcdToPicc += 212
        if ((taValue and 0x02) != 0) pcdToPicc += 424
        if ((taValue and 0x04) != 0) pcdToPicc += 848

        val tbValue = tb ?: 0x40 // default FWI = 4, SFGI = 0
        var fwi = (tbValue ushr 4) and 0x0F
        var sfgi = tbValue and 0x0F
        if (fwi == 15) {
            warnings += "FWI=15 is RFU; default FWI=4 assumed"
            fwi = 4
        }
        if (sfgi == 15) {
            warnings += "SFGI=15 is RFU; default SFGI=0 assumed"
            sfgi = 0
        }
        val tcValue = tc ?: 0x02 // default: CID supported, NAD not supported
        if (fsci > 12) warnings += "FSCI=$fsci is RFU"

        return Result.Success(
            AtsInfo(
                raw = ByteString.of(ats),
                tl = tl,
                fsci = fsci,
                fsc = FSC_TABLE.getOrNull(fsci),
                ta = ta,
                tb = tb,
                tc = tc,
                sameBitRateBothDirections = (taValue and 0x80) != 0,
                supportedBitRatesPiccToPcd = piccToPcd,
                supportedBitRatesPcdToPicc = pcdToPicc,
                fwi = fwi,
                sfgi = sfgi,
                fwtMicros = FWT_BASE_MICROS * (1 shl fwi),
                sfgtMicros = if (sfgi == 0) 0.0 else FWT_BASE_MICROS * (1 shl sfgi),
                nadSupported = (tcValue and 0x01) != 0,
                cidSupported = (tcValue and 0x02) != 0,
                historicalBytes = historical,
                warnings = warnings,
            ),
        )
    }

    private fun defaults(raw: ByteString, tl: Int, warnings: MutableList<String>): AtsInfo = AtsInfo(
        raw = raw, tl = tl, fsci = 2, fsc = 32, ta = null, tb = null, tc = null,
        sameBitRateBothDirections = false, supportedBitRatesPiccToPcd = listOf(106),
        supportedBitRatesPcdToPicc = listOf(106), fwi = 4, sfgi = 0,
        fwtMicros = FWT_BASE_MICROS * 16, sfgtMicros = 0.0, nadSupported = false, cidSupported = true,
        historicalBytes = ByteString.EMPTY, warnings = warnings,
    )
}
