package com.samtex.eidreader.nfc.tag

import com.samtex.eidreader.core.util.ByteString

enum class NfcTechnology(val androidClass: String, val label: String) {
    NFC_A("android.nfc.tech.NfcA", "NfcA"),
    NFC_B("android.nfc.tech.NfcB", "NfcB"),
    NFC_F("android.nfc.tech.NfcF", "NfcF"),
    NFC_V("android.nfc.tech.NfcV", "NfcV"),
    ISO_DEP("android.nfc.tech.IsoDep", "IsoDep"),
    MIFARE_CLASSIC("android.nfc.tech.MifareClassic", "MifareClassic"),
    MIFARE_ULTRALIGHT("android.nfc.tech.MifareUltralight", "MifareUltralight"),
    NDEF("android.nfc.tech.Ndef", "Ndef"),
    NDEF_FORMATABLE("android.nfc.tech.NdefFormatable", "NdefFormatable"),
    NFC_BARCODE("android.nfc.tech.NfcBarcode", "NfcBarcode");

    companion object {
        fun fromAndroidClass(name: String): NfcTechnology? = entries.firstOrNull { it.androidClass == name }
    }
}

/** Passive NfcA (ISO/IEC 14443-3 Type A) anticollision data. ATQA is kept in Android byte order. */
data class NfcAInfo(val atqa: ByteString, val sak: Int, val maxTransceiveLength: Int)

/** NfcB (ISO/IEC 14443-3 Type B) ATQB fields. */
data class NfcBInfo(val applicationData: ByteString, val protocolInfo: ByteString, val maxTransceiveLength: Int)

data class NfcFInfo(val manufacturer: ByteString, val systemCode: ByteString)

data class NfcVInfo(val dsfId: Int, val responseFlags: Int)

/**
 * ISO-DEP (ISO/IEC 14443-4) data exposed by Android **without** sending any APDU.
 *
 * Note: Android does not expose the complete ATS frame. [historicalBytes] is what
 * `IsoDep.getHistoricalBytes()` returns (Type A only) and [hiLayerResponse] what
 * `IsoDep.getHiLayerResponse()` returns (Type B ATTRIB response).
 */
data class IsoDepInfo(
    val historicalBytes: ByteString?,
    val hiLayerResponse: ByteString?,
    val extendedLengthSupported: Boolean,
    val maxTransceiveLength: Int,
)

data class MifareClassicInfo(val type: String, val sizeBytes: Int, val sectorCount: Int, val blockCount: Int)

data class MifareUltralightInfo(val type: String)

/** Everything Android reported at discovery time. No APDUs are needed to build it. */
data class TagSnapshot(
    val uid: ByteString?,
    val technologies: List<NfcTechnology>,
    val unknownTechnologies: List<String> = emptyList(),
    val nfcA: NfcAInfo? = null,
    val nfcB: NfcBInfo? = null,
    val nfcF: NfcFInfo? = null,
    val nfcV: NfcVInfo? = null,
    val isoDep: IsoDepInfo? = null,
    val mifareClassic: MifareClassicInfo? = null,
    val mifareUltralight: MifareUltralightInfo? = null,
) {
    fun has(tech: NfcTechnology): Boolean = tech in technologies
}
