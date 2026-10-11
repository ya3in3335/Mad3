package com.samtex.eidreader.icao.lds

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.icao.mrz.MrzData
import com.samtex.eidreader.icao.mrz.MrzFormatException
import com.samtex.eidreader.icao.mrz.MrzParser
import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.Tlv
import com.samtex.eidreader.nfc.apdu.TlvParseException
import org.jmrtd.lds.icao.DG2File
import java.io.ByteArrayInputStream
import java.io.IOException

class LdsFormatException(message: String) : Exception(message)

/** EF.COM (ICAO Doc 9303 Part 10): LDS version and the list of present data groups. */
data class ComFile(val ldsVersion: String?, val unicodeVersion: String?, val dataGroups: List<Int>)

/** DG11 — additional personal details. Only documented elements are decoded. */
data class Dg11(
    val fullName: String?,
    val otherNames: List<String>,
    val personalNumber: String?,
    val fullDateOfBirth: String?,
    val placeOfBirth: String?,
    val permanentAddress: String?,
    val telephone: String?,
    val profession: String?,
    val title: String?,
    val personalSummary: String?,
    val otherValidTdNumbers: String?,
    val custodyInformation: String?,
    /** Tags present but not decoded (proprietary or image elements). Values are not kept. */
    val undecodedTags: List<String>,
)

/** DG12 — additional document details. */
data class Dg12(
    val issuingAuthority: String?,
    val dateOfIssue: String?,
    val namesOfOtherPersons: List<String>,
    val endorsementsAndObservations: String?,
    val taxOrExitRequirements: String?,
    val personalizationDateTime: String?,
    val personalizationSystemSerialNumber: String?,
    val undecodedTags: List<String>,
)

data class PortraitImage(val mimeType: String, val width: Int, val height: Int, val data: ByteArray) {
    val isJpeg2000: Boolean get() = mimeType.contains("jp2", ignoreCase = true) || mimeType.contains("jpeg2000", ignoreCase = true)

    fun wipe() = data.fill(0)

    override fun equals(other: Any?): Boolean = other is PortraitImage && other.mimeType == mimeType && other.data.contentEquals(data)
    override fun hashCode(): Int = 31 * mimeType.hashCode() + data.contentHashCode()
}

/**
 * Decoders for the ICAO LDS1 elementary files that SAMTEX reads. All parsing is defensive:
 * malformed input raises [LdsFormatException] and never produces invented values.
 */
object LdsParsers {

    /** Data group tag → data group number (ICAO Doc 9303 Part 10). */
    val TAG_TO_DG: Map<Int, Int> = mapOf(
        0x61 to 1, 0x75 to 2, 0x63 to 3, 0x76 to 4, 0x65 to 5, 0x66 to 6, 0x67 to 7, 0x68 to 8,
        0x69 to 9, 0x6A to 10, 0x6B to 11, 0x6C to 12, 0x6D to 13, 0x6E to 14, 0x6F to 15, 0x70 to 16,
    )

    private fun root(bytes: ByteArray, expectedTag: Int, name: String): Tlv {
        val nodes = try {
            BerTlv.parse(bytes)
        } catch (e: TlvParseException) {
            throw LdsFormatException("$name is not valid BER-TLV: ${e.message}")
        }
        return nodes.firstOrNull { it.tag == expectedTag }
            ?: throw LdsFormatException("$name does not start with tag ${Hex.byte(expectedTag)}")
    }

    private fun text(t: Tlv?): String? = t?.value?.let { String(it, Charsets.UTF_8).trim().ifEmpty { null } }

    /** Converts an ICAO name field ("PRIMARY<<SECONDARY<NAMES") into readable text. */
    fun readableName(raw: String): String {
        val idx = raw.indexOf("<<")
        if (idx < 0) return raw.replace('<', ' ').trim()
        val primary = raw.substring(0, idx).replace('<', ' ').trim()
        val secondary = raw.substring(idx + 2).replace('<', ' ').trim()
        return if (secondary.isEmpty()) primary else "$primary, $secondary"
    }

    /** Dates in DG11/DG12 are 8 ASCII digits (yyyymmdd) or 4 BCD bytes. */
    fun decodeDate(value: ByteArray): String? {
        if (value.size == 8 && value.all { it in '0'.code.toByte()..'9'.code.toByte() }) {
            return String(value, Charsets.US_ASCII)
        }
        if (value.size == 4) {
            val bcd = Hex.encode(value)
            if (bcd.all { it.isDigit() }) return bcd
        }
        return String(value, Charsets.UTF_8).trim().ifEmpty { null }
    }

    fun parseCom(bytes: ByteArray): ComFile {
        val r = root(bytes, 0x60, "EF.COM")
        val tagList = r.children.firstOrNull { it.tag == 0x5C }?.value ?: ByteArray(0)
        return ComFile(
            ldsVersion = text(r.children.firstOrNull { it.tag == 0x5F01 }),
            unicodeVersion = text(r.children.firstOrNull { it.tag == 0x5F36 }),
            dataGroups = tagList.toList().mapNotNull { TAG_TO_DG[it.toInt() and 0xFF] }.distinct().sorted(),
        )
    }

    fun parseDg1(bytes: ByteArray): MrzData {
        val r = root(bytes, 0x61, "DG1")
        val mrz = r.children.firstOrNull { it.tag == 0x5F1F }?.value
            ?: throw LdsFormatException("DG1 contains no MRZ data object (5F1F)")
        return try {
            MrzParser.parse(String(mrz, Charsets.US_ASCII))
        } catch (e: MrzFormatException) {
            throw LdsFormatException("DG1 MRZ: ${e.message}")
        }
    }

    private fun listOfNames(r: Tlv, itemTag: Int): List<String> {
        val container = r.children.firstOrNull { it.tag == 0xA0 }
        val items = (container?.children ?: emptyList()) + r.children
        return items.filter { it.tag == itemTag }.mapNotNull { text(it) }.map(::readableName)
    }

    fun parseDg11(bytes: ByteArray): Dg11 {
        val r = root(bytes, 0x6B, "DG11")
        val known = setOf(0x5C, 0x5F0E, 0x5F0F, 0xA0, 0x5F10, 0x5F2B, 0x5F11, 0x5F42, 0x5F12, 0x5F13, 0x5F14, 0x5F15, 0x5F17, 0x5F18)
        fun t(tag: Int) = text(r.children.firstOrNull { it.tag == tag })
        return Dg11(
            fullName = t(0x5F0E)?.let(::readableName),
            otherNames = listOfNames(r, 0x5F0F),
            personalNumber = t(0x5F10),
            fullDateOfBirth = r.children.firstOrNull { it.tag == 0x5F2B }?.value?.let(::decodeDate),
            placeOfBirth = t(0x5F11)?.replace('<', ' ')?.trim(),
            permanentAddress = t(0x5F42)?.replace('<', ' ')?.trim(),
            telephone = t(0x5F12),
            profession = t(0x5F13),
            title = t(0x5F14),
            personalSummary = t(0x5F15),
            otherValidTdNumbers = t(0x5F17),
            custodyInformation = t(0x5F18),
            undecodedTags = r.children.map { it.tag }.filter { it !in known }.map { Hex.encode(byteArrayOf((it ushr 8).toByte(), it.toByte())).trimStart('0').ifEmpty { "0" } },
        )
    }

    fun parseDg12(bytes: ByteArray): Dg12 {
        val r = root(bytes, 0x6C, "DG12")
        val known = setOf(0x5C, 0x5F19, 0x5F26, 0xA0, 0x5F1A, 0x5F1B, 0x5F1C, 0x5F55, 0x5F56)
        fun t(tag: Int) = text(r.children.firstOrNull { it.tag == tag })
        return Dg12(
            issuingAuthority = t(0x5F19),
            dateOfIssue = r.children.firstOrNull { it.tag == 0x5F26 }?.value?.let(::decodeDate),
            namesOfOtherPersons = listOfNames(r, 0x5F1A),
            endorsementsAndObservations = t(0x5F1B),
            taxOrExitRequirements = t(0x5F1C),
            personalizationDateTime = r.children.firstOrNull { it.tag == 0x5F55 }?.value?.let { v ->
                if (v.size == 7) Hex.encode(v) else String(v, Charsets.US_ASCII)
            },
            personalizationSystemSerialNumber = t(0x5F56),
            undecodedTags = r.children.map { it.tag }.filter { it !in known }.map { Hex.encode(byteArrayOf((it ushr 8).toByte(), it.toByte())).trimStart('0').ifEmpty { "0" } },
        )
    }

    /** Extracts the first facial image from DG2 (ISO/IEC 19794-5 or 39794-5 via JMRTD). */
    fun parseDg2(bytes: ByteArray): PortraitImage? {
        val dg2 = try {
            DG2File(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            throw LdsFormatException("DG2 could not be decoded: ${e.message}")
        } catch (e: RuntimeException) {
            throw LdsFormatException("DG2 could not be decoded: ${e.javaClass.simpleName}")
        }
        val image = dg2.faceInfos.asSequence().flatMap { it.faceImageInfos.asSequence() }.firstOrNull() ?: return null
        val data = image.imageInputStream.use { it.readBytes() }
        return PortraitImage(image.mimeType ?: "application/octet-stream", image.width, image.height, data)
    }
}
