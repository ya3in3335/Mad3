package com.samtex.eidreader.nfc.classifier

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.ats.UidSize

/**
 * IC manufacturer codes from ISO/IEC 7816-6 (registration authority list).
 *
 * Per ISO/IEC 14443-3, UID0 is a manufacturer code **only for double and triple size UIDs**.
 * For single-size (4-byte) UIDs, UID0 = 0x08 denotes a random ID (RID) generated at each
 * activation, so no manufacturer can be derived. Some tools wrongly map 0x08 to "Fujitsu"
 * (whose registry code is also 0x08) — SAMTEX reports this explicitly instead.
 */
object IcManufacturers {

    private val registry: Map<Int, String> = mapOf(
        0x01 to "Motorola",
        0x02 to "STMicroelectronics",
        0x03 to "Hitachi",
        0x04 to "NXP Semiconductors",
        0x05 to "Infineon Technologies",
        0x06 to "Cylink",
        0x07 to "Texas Instruments",
        0x08 to "Fujitsu",
        0x09 to "Matsushita Electronics",
        0x0A to "NEC",
        0x0B to "Oki Electric",
        0x0C to "Toshiba",
        0x0D to "Mitsubishi Electric",
        0x0E to "Samsung Electronics",
        0x0F to "Hynix",
        0x10 to "LG Semiconductors",
        0x11 to "Emosyn-EM Microelectronics",
        0x12 to "INSIDE Technology",
        0x13 to "ORGA Kartensysteme",
        0x14 to "Sharp",
        0x15 to "ATMEL",
        0x16 to "EM Microelectronic-Marin",
        0x17 to "KSW Microtec",
        0x18 to "ZMD",
        0x19 to "XICOR",
        0x1A to "Sony",
        0x1B to "Malaysia Microelectronic Solutions",
        0x1C to "Emosyn",
        0x1D to "Shanghai Fudan Microelectronics",
        0x1E to "Magellan Technology",
        0x1F to "Melexis",
        0x20 to "Renesas Technology",
        0x21 to "TAGSYS",
        0x22 to "Transcore",
        0x23 to "Shanghai Belling",
        0x24 to "Masktech",
        0x25 to "Innovision Research and Technology",
        0x26 to "Hitachi ULSI Systems",
        0x27 to "Yubico",
        0x28 to "Ricoh",
        0x29 to "ASK",
        0x2A to "Unicore Microsystems",
        0x2B to "Dallas Semiconductor/Maxim",
        0x2C to "Impinj",
    )

    fun nameFor(code: Int): String? = registry[code]

    sealed interface Result {
        data class Identified(val code: Int, val name: String) : Result
        data class UnknownCode(val code: Int) : Result
        data class RandomUid(val misreportedAs: String?) : Result
        data class NotDerivable(val reason: String) : Result
    }

    fun identify(uid: ByteString?, uidSize: UidSize?): Result {
        if (uid == null || uid.isEmpty()) return Result.NotDerivable("No UID reported by Android")
        val first = uid[0]
        val size = uid.size
        return when {
            size == 4 && first == 0x08 -> Result.RandomUid(registry[0x08])
            size == 4 -> Result.NotDerivable(
                "Single-size UID (UID0 ${Hex.byte(first)}) does not encode a manufacturer per ISO/IEC 14443-3",
            )
            size == 7 || size == 10 || uidSize == UidSize.DOUBLE || uidSize == UidSize.TRIPLE ->
                registry[first]?.let { Result.Identified(first, it) } ?: Result.UnknownCode(first)
            else -> Result.NotDerivable("UID length $size does not follow ISO/IEC 14443-3 Type A sizes")
        }
    }
}
