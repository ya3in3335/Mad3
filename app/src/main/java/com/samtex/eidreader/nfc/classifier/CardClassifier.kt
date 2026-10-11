package com.samtex.eidreader.nfc.classifier

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.ats.AtqaInfo
import com.samtex.eidreader.nfc.ats.AtqaSakDecoder
import com.samtex.eidreader.nfc.ats.AtqbDecoder
import com.samtex.eidreader.nfc.ats.AtqbInfo
import com.samtex.eidreader.nfc.ats.HistoricalBytesFormat
import com.samtex.eidreader.nfc.ats.HistoricalBytesInfo
import com.samtex.eidreader.nfc.ats.HistoricalBytesParser
import com.samtex.eidreader.nfc.ats.SakInfo
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.nfc.protocol.ProtocolProbeResult
import com.samtex.eidreader.nfc.tag.NfcTechnology
import com.samtex.eidreader.nfc.tag.TagSnapshot
import javax.inject.Inject
import javax.inject.Singleton

enum class CardFamily(val label: String) {
    NATIONAL_EID("National electronic identity card"),
    EMRTD("ICAO 9303 eMRTD-compatible document"),
    ISO_DEP_SMART_CARD("ISO/IEC 14443-4 smart card"),
    MIFARE_CLASSIC("MIFARE Classic compatible tag"),
    MIFARE_ULTRALIGHT("MIFARE Ultralight / NTAG compatible tag"),
    FELICA("FeliCa (JIS X 6319-4) card"),
    ISO15693("ISO/IEC 15693 vicinity tag"),
    TYPE_A_TAG("ISO/IEC 14443-3 Type A tag"),
    TYPE_B_TAG("ISO/IEC 14443-3 Type B tag"),
    UNKNOWN("Unknown NFC tag"),
}

data class CardClassification(
    val family: CardFamily,
    val cardType: String,
    val cardTypeProvenance: Provenance,
    val isoStandards: List<String>,
    val technologies: List<String>,
    val isoDepCapable: Boolean,
    val manufacturer: Finding,
    val atqa: AtqaInfo?,
    val sak: SakInfo?,
    val atqb: AtqbInfo?,
    val historicalBytes: HistoricalBytesInfo,
    val signature: CardSignature?,
    val findings: List<Finding>,
)

/**
 * Classifies a card from the passive [TagSnapshot] plus the results of public protocol probes.
 * Every conclusion carries a [Provenance] so the UI can separate verified from inferred data.
 */
@Singleton
class CardClassifier @Inject constructor() {

    fun classify(snapshot: TagSnapshot, probes: List<ProtocolProbeResult> = emptyList()): CardClassification {
        val atqa = snapshot.nfcA?.let { AtqaSakDecoder.decodeAtqa(it.atqa) }
        val sak = snapshot.nfcA?.let { AtqaSakDecoder.decodeSak(it.sak) }
        val isoDep = snapshot.has(NfcTechnology.ISO_DEP)
        val historical = HistoricalBytesParser.parse(snapshot.isoDep?.historicalBytes)
        val atqb = snapshot.nfcB?.let { AtqbDecoder.decode(it.applicationData, it.protocolInfo) }
        // Type A exposes ATS historical bytes; Type B exposes the ATTRIB higher-layer response instead.
        val signature = EidSignatureDetector.detect(snapshot.isoDep?.historicalBytes)
            ?: EidSignatureDetector.detect(snapshot.isoDep?.hiLayerResponse)
        val emrtdPresent = probes.any { it.adapterId == "icao.emrtd" && it.status == ProbeStatus.AVAILABLE }
        val paceAdvertised = probes.any { p -> p.adapterId == "icao.cardaccess" && p.status == ProbeStatus.AVAILABLE && p.findings.any { it.label == "PACE" } }

        val family = when {
            signature != null -> CardFamily.NATIONAL_EID
            emrtdPresent -> CardFamily.EMRTD
            isoDep -> CardFamily.ISO_DEP_SMART_CARD
            snapshot.has(NfcTechnology.MIFARE_CLASSIC) -> CardFamily.MIFARE_CLASSIC
            snapshot.has(NfcTechnology.MIFARE_ULTRALIGHT) -> CardFamily.MIFARE_ULTRALIGHT
            snapshot.has(NfcTechnology.NFC_F) -> CardFamily.FELICA
            snapshot.has(NfcTechnology.NFC_V) -> CardFamily.ISO15693
            snapshot.has(NfcTechnology.NFC_A) -> CardFamily.TYPE_A_TAG
            snapshot.has(NfcTechnology.NFC_B) -> CardFamily.TYPE_B_TAG
            else -> CardFamily.UNKNOWN
        }

        val cardType = when (family) {
            CardFamily.NATIONAL_EID -> signature!!.interpretation + if (emrtdPresent) " (ICAO eMRTD application present)" else ""
            CardFamily.MIFARE_CLASSIC -> snapshot.mifareClassic?.let { "${it.type} (${it.sizeBytes} bytes)" } ?: family.label
            CardFamily.MIFARE_ULTRALIGHT -> snapshot.mifareUltralight?.type ?: family.label
            else -> family.label
        }
        // A card type derived from a signature or registry is an interpretation; the
        // technology-based families are reported directly by Android.
        val cardTypeProvenance = if (family == CardFamily.NATIONAL_EID) Provenance.INFERRED else Provenance.VERIFIED

        val standards = buildList {
            if (snapshot.has(NfcTechnology.NFC_A)) add("ISO/IEC 14443-3 Type A")
            if (snapshot.has(NfcTechnology.NFC_B)) add("ISO/IEC 14443-3 Type B")
            if (isoDep) add("ISO/IEC 14443-4 (ISO-DEP)")
            if (probes.isNotEmpty()) add("ISO/IEC 7816-4 (APDU)")
            if (emrtdPresent || paceAdvertised) add("ICAO Doc 9303")
            if (snapshot.has(NfcTechnology.NFC_F)) add("JIS X 6319-4 (FeliCa)")
            if (snapshot.has(NfcTechnology.NFC_V)) add("ISO/IEC 15693")
        }

        val manufacturer = manufacturerFinding(snapshot, atqa)
        val findings = buildFindings(snapshot, atqa, sak, atqb, historical, signature, manufacturer)

        return CardClassification(
            family = family,
            cardType = cardType,
            cardTypeProvenance = cardTypeProvenance,
            isoStandards = standards,
            technologies = snapshot.technologies.map { it.label } + snapshot.unknownTechnologies,
            isoDepCapable = isoDep,
            manufacturer = manufacturer,
            atqa = atqa,
            sak = sak,
            atqb = atqb,
            historicalBytes = historical,
            signature = signature,
            findings = findings,
        )
    }

    private fun manufacturerFinding(snapshot: TagSnapshot, atqa: AtqaInfo?): Finding {
        if (snapshot.has(NfcTechnology.NFC_A)) {
            return when (val r = IcManufacturers.identify(snapshot.uid, atqa?.uidSize)) {
                is IcManufacturers.Result.Identified -> Finding(
                    "manufacturer", "Manufacturer", r.name, Provenance.INFERRED,
                    "UID0 ${Hex.byte(r.code)} looked up in the ISO/IEC 7816-6 IC manufacturer registry.",
                )
                is IcManufacturers.Result.UnknownCode -> Finding(
                    "manufacturer", "Manufacturer", "Unregistered code ${Hex.byte(r.code)}", Provenance.INFERRED,
                    "UID0 is not in SAMTEX's ISO/IEC 7816-6 registry table.",
                )
                is IcManufacturers.Result.RandomUid -> Finding(
                    "manufacturer", "Manufacturer", "Not identifiable (random UID)", Provenance.VERIFIED,
                    "UID0 = 08 marks a random identifier regenerated at each activation (ISO/IEC 14443-3). " +
                        "Tools that report \"${r.misreportedAs ?: "a manufacturer"}\" are reading the 08 byte as an ISO/IEC 7816-6 " +
                        "manufacturer code, which does not apply to random single-size UIDs.",
                )
                is IcManufacturers.Result.NotDerivable -> Finding(
                    "manufacturer", "Manufacturer", "Not identifiable", Provenance.VERIFIED, r.reason,
                )
            }
        }
        if (snapshot.has(NfcTechnology.NFC_B)) {
            return Finding(
                "manufacturer", "Manufacturer", "Not identifiable", Provenance.VERIFIED,
                "Type B cards report a PUPI (pseudo-unique identifier), which carries no manufacturer code (ISO/IEC 14443-3).",
            )
        }
        if (snapshot.has(NfcTechnology.NFC_V)) {
            val uid = snapshot.uid
            // ISO/IEC 15693 UID (LSB first in Android): MSB = E0, next byte = manufacturer code.
            if (uid != null && uid.size == 8 && uid[7] == 0xE0) {
                val code = uid[6]
                val name = IcManufacturers.nameFor(code)
                return Finding(
                    "manufacturer", "Manufacturer", name ?: "Unregistered code ${Hex.byte(code)}", Provenance.INFERRED,
                    "ISO/IEC 15693 UID manufacturer byte ${Hex.byte(code)} (ISO/IEC 7816-6 registry).",
                )
            }
        }
        return Finding("manufacturer", "Manufacturer", "Not identifiable", Provenance.VERIFIED, "No standardized manufacturer field available.")
    }

    private fun buildFindings(
        snapshot: TagSnapshot,
        atqa: AtqaInfo?,
        sak: SakInfo?,
        atqb: AtqbInfo?,
        historical: HistoricalBytesInfo,
        signature: CardSignature?,
        manufacturer: Finding,
    ): List<Finding> = buildList {
        add(
            Finding(
                "technologies", "Technologies",
                (snapshot.technologies.map { it.label } + snapshot.unknownTechnologies).joinToString(", ").ifEmpty { "None reported" },
                Provenance.VERIFIED, "Technologies reported by the Android NFC stack for this tag.",
            ),
        )
        snapshot.uid?.let { uid ->
            add(
                Finding(
                    "uid", "Tag identifier", "${uid.size} bytes", Provenance.VERIFIED,
                    when {
                        snapshot.has(NfcTechnology.NFC_B) -> "PUPI (Type B pseudo-unique identifier); often random per activation."
                        uid.size == 4 && uid[0] == 0x08 -> "Random UID: changes at every activation, not a stable identifier."
                        else -> "Anticollision identifier reported by Android."
                    },
                    uid.hex(),
                ),
            )
        }
        if (atqa != null) {
            add(
                Finding(
                    "atqa", "ATQA", atqa.hex, Provenance.VERIFIED,
                    "UID size: ${atqa.uidSize.label}; bit-frame anticollision bits ${Hex.byte(atqa.bitFrameAnticollision)}" +
                        if (atqa.compliant) "." else "; non-standard coding.",
                    Hex.u16(atqa.value),
                ),
            )
        }
        if (sak != null) {
            val parts = mutableListOf<String>()
            parts += if (sak.iso14443_4Compliant) "ISO/IEC 14443-4 compliant (b6 set)" else "not ISO/IEC 14443-4 compliant"
            if (sak.uidIncomplete) parts += "UID incomplete (cascade bit set)"
            if (sak.nfcDepCompliant) parts += "ISO/IEC 18092 NFC-DEP"
            add(Finding("sak", "SAK", sak.hex, Provenance.VERIFIED, parts.joinToString("; ") + ".", Hex.byte(sak.value)))
            if (sak.commonlyAssociatedWith.isNotEmpty()) {
                add(
                    Finding(
                        "sak.family", "SAK typical products", sak.commonlyAssociatedWith.joinToString("; "), Provenance.INFERRED,
                        "Common associations for this SAK value (NXP AN10833). Not proof of the actual product.",
                    ),
                )
            }
        }
        snapshot.isoDep?.let { iso ->
            add(
                Finding(
                    "ats", "ATS", "Full ATS not exposed by Android", Provenance.RESTRICTED,
                    "Android's IsoDep API only exposes the historical bytes of the ATS (Type A) or the higher-layer response (Type B).",
                ),
            )
            if (iso.historicalBytes != null) {
                add(
                    Finding(
                        "historical", "ATS historical bytes",
                        historical.ascii?.let { "\"$it\"" } ?: "${historical.raw.size} bytes",
                        Provenance.VERIFIED, historical.format.label, historical.raw.hex(),
                    ),
                )
                historical.compactTlv.forEach { obj ->
                    add(Finding("historical.tlv.${obj.tag}", obj.tagName, obj.value.hex(), Provenance.VERIFIED, "Compact-TLV object in historical bytes.", obj.value.hex()))
                }
                if (historical.format == HistoricalBytesFormat.PROPRIETARY && historical.categoryIndicator != null) {
                    add(
                        Finding(
                            "historical.category", "Category indicator", Hex.byte(historical.categoryIndicator), Provenance.VERIFIED,
                            "Not an ISO/IEC 7816-4 category (00, 10, 8X): the historical bytes use a proprietary layout.",
                        ),
                    )
                }
            }
            iso.hiLayerResponse?.let {
                add(Finding("hilayer", "Higher-layer response", "${it.size} bytes", Provenance.VERIFIED, "ATTRIB response (Type B).", it.hex()))
            }
            add(Finding("maxTransceive", "Max transceive length", "${iso.maxTransceiveLength} bytes", Provenance.VERIFIED, "Largest command Android can send to this tag."))
            add(
                Finding(
                    "extendedLength", "Extended-length APDUs",
                    if (iso.extendedLengthSupported) "Supported" else "Not supported", Provenance.VERIFIED,
                    "Reported by IsoDep.isExtendedLengthApduSupported() for this device/tag combination.",
                ),
            )
        }
        if (signature != null) {
            add(
                Finding(
                    "signature", "Detected signature", signature.text, Provenance.VERIFIED,
                    if (snapshot.isoDep?.historicalBytes != null) "ASCII text present in the ATS historical bytes." else "ASCII text present in the Type B higher-layer response.",
                    signature.hex,
                ),
            )
            add(
                Finding(
                    "signature.meaning", "Signature interpretation", signature.interpretation, Provenance.INFERRED,
                    "Interpretation of the signature text; the internal applications of the card are not assumed.",
                ),
            )
        }
        snapshot.nfcB?.let {
            add(Finding("nfcb.appdata", "Application data (ATQB)", it.applicationData.hex(" "), Provenance.VERIFIED, "Proprietary application data from the ATQB.", it.applicationData.hex()))
            add(Finding("nfcb.protocol", "Protocol info (ATQB)", it.protocolInfo.hex(" "), Provenance.VERIFIED, null, it.protocolInfo.hex()))
        }
        atqb?.let { b ->
            add(
                Finding(
                    "atqb.decoded", "ATQB protocol",
                    buildString {
                        append(if (b.iso14443_4Compliant) "ISO/IEC 14443-4 compliant" else "not ISO/IEC 14443-4 compliant")
                        b.maxFrameSize?.let { append("; max frame ").append(it).append(" bytes") }
                        append("; FWI ").append(b.fwi)
                        append("; CID ").append(if (b.cidSupported) "yes" else "no")
                        append("; NAD ").append(if (b.nadSupported) "yes" else "no")
                    },
                    Provenance.VERIFIED,
                    "Bit rates PICC→PCD ${b.supportedBitRatesPiccToPcd.joinToString("/")} kbit/s, PCD→PICC ${b.supportedBitRatesPcdToPicc.joinToString("/")} kbit/s.",
                ),
            )
        }
        snapshot.nfcF?.let {
            add(Finding("nfcf.pmm", "Manufacturer parameter (PMm)", it.manufacturer.hex(" "), Provenance.VERIFIED, null, it.manufacturer.hex()))
            add(Finding("nfcf.sc", "System code", it.systemCode.hex(" "), Provenance.VERIFIED, null, it.systemCode.hex()))
        }
        snapshot.nfcV?.let {
            add(Finding("nfcv.dsfid", "DSFID", Hex.byte(it.dsfId), Provenance.VERIFIED))
            add(Finding("nfcv.flags", "Response flags", Hex.byte(it.responseFlags), Provenance.VERIFIED))
        }
        snapshot.mifareClassic?.let {
            add(Finding("classic", "MIFARE Classic", "${it.type}: ${it.sizeBytes} bytes, ${it.sectorCount} sectors, ${it.blockCount} blocks", Provenance.VERIFIED, "Sector contents require keys and are not read."))
        }
        snapshot.mifareUltralight?.let {
            add(Finding("ultralight", "MIFARE Ultralight", it.type, Provenance.VERIFIED))
        }
        add(manufacturer)
    }
}
