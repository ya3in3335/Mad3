package com.samtex.eidreader.testing

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.domain.model.DiscoveredCard
import com.samtex.eidreader.nfc.tag.IsoDepInfo
import com.samtex.eidreader.nfc.tag.NfcAInfo
import com.samtex.eidreader.nfc.tag.NfcTechnology
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduTransport

object Fixtures {
    /**
     * Values previously observed on an Algerian eID (diagnostic fixture, NOT a guarantee for
     * every card): ISO 14443-4, NfcA + IsoDep, ATQA 0x0008, SAK 0x20,
     * historical bytes "AlgeriaEIDv2". The UID is a synthetic random UID (first byte 08).
     */
    const val ALGERIA_HISTORICAL_HEX = "416C67657269614549447632"
    val ALGERIA_UID: ByteString = ByteString.fromHex("08A1B2C3")

    val algeriaSnapshot = TagSnapshot(
        uid = ALGERIA_UID,
        technologies = listOf(NfcTechnology.ISO_DEP, NfcTechnology.NFC_A),
        nfcA = NfcAInfo(atqa = ByteString.of(0x08, 0x00), sak = 0x20, maxTransceiveLength = 253),
        isoDep = IsoDepInfo(
            historicalBytes = ByteString.fromHex(ALGERIA_HISTORICAL_HEX),
            hiLayerResponse = null,
            extendedLengthSupported = true,
            maxTransceiveLength = 65279,
        ),
    )

    val ultralightSnapshot = TagSnapshot(
        uid = ByteString.fromHex("04112233445566"),
        technologies = listOf(NfcTechnology.NFC_A, NfcTechnology.MIFARE_ULTRALIGHT),
        nfcA = NfcAInfo(atqa = ByteString.of(0x44, 0x00), sak = 0x00, maxTransceiveLength = 253),
    )

    /** Typical EF.CardAccess: PACE ECDH-GM AES-128, version 2, brainpoolP256r1 (parameter 13). */
    val EF_CARD_ACCESS: ByteArray = Hex.decode("3114301206 0A 04007F0007020204 0202 020102 02010D")

    fun card(snapshot: TagSnapshot, transport: ApduTransport?): DiscoveredCard = object : DiscoveredCard {
        override val snapshot: TagSnapshot = snapshot
        override fun openIsoDepTransport(): ApduTransport? = transport
    }
}
