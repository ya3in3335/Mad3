package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.StatusWords
import com.samtex.eidreader.nfc.classifier.Finding
import com.samtex.eidreader.nfc.classifier.Provenance
import com.samtex.eidreader.nfc.tag.NfcTechnology
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduChannel
import javax.inject.Inject

/**
 * Checks for the ICAO LDS1 eMRTD application with a single, documented SELECT by AID.
 * No data group is read: all LDS1 data groups require BAC or PACE.
 */
class IcaoEmrtdApplicationAdapter @Inject constructor() : CardProtocolAdapter {
    override val id = "icao.emrtd"
    override val name = "ICAO eMRTD application (LDS1)"
    override val reference = "ICAO Doc 9303 Part 10 (LDS1 AID A0000002471001)"

    override fun isApplicable(snapshot: TagSnapshot): Boolean = snapshot.has(NfcTechnology.ISO_DEP)

    override suspend fun probe(channel: ApduChannel): ProtocolProbeResult {
        val select = channel.transmit(ApduCommands.selectByAid(ApduCommands.AID_ICAO_EMRTD), "SELECT eMRTD AID", publicData = true)
        val aidFinding = Finding(
            key = "emrtd.select",
            label = "SELECT A0000002471001",
            value = select.status.toString(),
            provenance = Provenance.VERIFIED,
            hex = Hex.u16(select.status.sw),
        )
        val restrictedNote = "LDS1 data groups (DG1 MRZ, DG2 facial image, DG3 fingerprints, …) require BAC or PACE " +
            "with credentials printed on the document. SAMTEX reports them as restricted and does not attempt access."
        return when (select.status.sw) {
            StatusWords.SW_SUCCESS -> ProtocolProbeResult(
                id, name, reference, ProbeStatus.AVAILABLE,
                "eMRTD application present",
                listOf(
                    aidFinding,
                    Finding(
                        key = "emrtd.datagroups",
                        label = "Data groups",
                        value = "Access restricted",
                        provenance = Provenance.RESTRICTED,
                        explanation = restrictedNote,
                    ),
                ),
                listOf(restrictedNote),
            )
            StatusWords.SW_FILE_NOT_FOUND -> ProtocolProbeResult(
                id, name, reference, ProbeStatus.NOT_PRESENT, "eMRTD application not found", listOf(aidFinding),
            )
            else -> ProtocolProbeResult(
                id, name, reference,
                if (select.status.isSecurityRelated) ProbeStatus.ACCESS_RESTRICTED else ProbeStatus.ERROR,
                "SELECT returned ${select.status.hex}",
                listOf(aidFinding),
                if (select.status.isSecurityRelated) listOf(restrictedNote) else emptyList(),
            )
        }
    }
}
