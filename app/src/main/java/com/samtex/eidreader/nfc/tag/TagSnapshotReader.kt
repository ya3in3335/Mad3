package com.samtex.eidreader.nfc.tag

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.samtex.eidreader.core.util.toByteString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [TagSnapshot] from an Android [Tag] using only the values cached by the NFC
 * stack during discovery. No connection is opened and no command is sent here.
 */
@Singleton
class TagSnapshotReader @Inject constructor() {

    fun read(tag: Tag): TagSnapshot {
        val known = mutableListOf<NfcTechnology>()
        val unknown = mutableListOf<String>()
        for (name in tag.techList) {
            val tech = NfcTechnology.fromAndroidClass(name)
            if (tech != null) known += tech else unknown += name.substringAfterLast('.')
        }
        return TagSnapshot(
            uid = safe { tag.id }?.takeIf { it.isNotEmpty() }?.toByteString(),
            technologies = known,
            unknownTechnologies = unknown,
            nfcA = safe { NfcA.get(tag) }?.let { NfcAInfo(it.atqa.toByteString(), it.sak.toInt() and 0xFF, it.maxTransceiveLength) },
            nfcB = safe { NfcB.get(tag) }?.let { NfcBInfo(it.applicationData.toByteString(), it.protocolInfo.toByteString(), it.maxTransceiveLength) },
            nfcF = safe { NfcF.get(tag) }?.let { NfcFInfo(it.manufacturer.toByteString(), it.systemCode.toByteString()) },
            nfcV = safe { NfcV.get(tag) }?.let { NfcVInfo(it.dsfId.toInt() and 0xFF, it.responseFlags.toInt() and 0xFF) },
            isoDep = safe { IsoDep.get(tag) }?.let { iso ->
                IsoDepInfo(
                    historicalBytes = iso.historicalBytes?.toByteString(),
                    hiLayerResponse = iso.hiLayerResponse?.toByteString(),
                    extendedLengthSupported = safe { iso.isExtendedLengthApduSupported } ?: false,
                    maxTransceiveLength = iso.maxTransceiveLength,
                )
            },
            mifareClassic = safe { MifareClassic.get(tag) }?.let {
                MifareClassicInfo(classicType(it.type), it.size, it.sectorCount, it.blockCount)
            },
            mifareUltralight = safe { MifareUltralight.get(tag) }?.let { MifareUltralightInfo(ultralightType(it.type)) },
        )
    }

    private fun classicType(type: Int): String = when (type) {
        MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
        MifareClassic.TYPE_PLUS -> "MIFARE Plus"
        MifareClassic.TYPE_PRO -> "MIFARE Classic (SmartMX/Pro)"
        else -> "Unknown MIFARE Classic variant"
    }

    private fun ultralightType(type: Int): String = when (type) {
        MifareUltralight.TYPE_ULTRALIGHT -> "MIFARE Ultralight"
        MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C"
        else -> "Unknown MIFARE Ultralight variant"
    }

    /** Some vendor NFC stacks throw for technologies they only partially support. */
    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    }
}
