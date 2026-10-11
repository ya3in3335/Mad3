package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.StatusWords
import com.samtex.eidreader.nfc.apdu.TlvParseException
import com.samtex.eidreader.nfc.classifier.Finding
import com.samtex.eidreader.nfc.classifier.Provenance
import com.samtex.eidreader.nfc.tag.NfcTechnology
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduChannel
import javax.inject.Inject

/**
 * Reads EF.CardAccess, which ICAO Doc 9303 Part 10 defines as readable **without**
 * access control in the Master File of PACE-capable documents. It contains only the list of
 * supported security protocols (no personal data).
 */
class IcaoCardAccessAdapter @Inject constructor() : CardProtocolAdapter {
    override val id = "icao.cardaccess"
    override val name = "EF.CardAccess (security protocols)"
    override val reference = "ICAO Doc 9303 Part 10 (EF.CardAccess), Part 11 (PACE)"

    override fun isApplicable(snapshot: TagSnapshot): Boolean = snapshot.has(NfcTechnology.ISO_DEP)

    override suspend fun probe(channel: ApduChannel): ProtocolProbeResult {
        return when (val read = TransparentFileReader.readTlvFile(channel, ApduCommands.FID_EF_CARD_ACCESS, "EF.CardAccess")) {
            is EfReadResult.SelectFailed -> when (read.status.sw) {
                StatusWords.SW_FILE_NOT_FOUND -> result(ProbeStatus.NOT_PRESENT, "EF.CardAccess not present (card may not support PACE)")
                StatusWords.SW_SECURITY_STATUS_NOT_SATISFIED -> result(ProbeStatus.ACCESS_RESTRICTED, "EF.CardAccess access is restricted on this card")
                else -> result(ProbeStatus.ERROR, "SELECT EF.CardAccess returned ${read.status}")
            }
            is EfReadResult.ReadFailed -> result(
                if (read.status.isSecurityRelated) ProbeStatus.ACCESS_RESTRICTED else ProbeStatus.ERROR,
                "READ BINARY EF.CardAccess returned ${read.status}",
            )
            is EfReadResult.Success -> decode(read.content, read.truncated)
        }
    }

    private fun decode(content: ByteArray, truncated: Boolean): ProtocolProbeResult {
        val findings = mutableListOf(
            Finding(
                key = "cardaccess.raw",
                label = "EF.CardAccess",
                value = "${content.size} bytes" + if (truncated) " (truncated)" else "",
                provenance = Provenance.VERIFIED,
                explanation = "Public file listing supported security protocols. Contains no personal data.",
                hex = Hex.encode(content),
            ),
        )
        val infos = try {
            SecurityInfoDecoder.decode(content)
        } catch (e: TlvParseException) {
            return result(ProbeStatus.ERROR, "EF.CardAccess could not be decoded: ${e.message}", findings)
        }
        infos.forEachIndexed { i, info ->
            findings += Finding(
                key = "cardaccess.info.$i",
                label = info.family,
                value = buildString {
                    append(info.protocolName)
                    info.version?.let { append(", version ").append(it) }
                    info.parameterName?.let { append(", ").append(it) }
                },
                provenance = Provenance.VERIFIED,
                explanation = "OID ${info.oid}",
            )
        }
        val pace = infos.any { it.family == "PACE" }
        val notes = buildList {
            if (pace) {
                add("PACE is advertised: personal data groups require PACE with the Card Access Number (CAN) or MRZ. SAMTEX does not perform PACE.")
            }
            add("Protected data (holder name, photo, fingerprints, address, document numbers) is not read.")
        }
        val summary = if (pace) "PACE supported (${infos.count { it.family == "PACE" }} configuration(s))" else "${infos.size} security info object(s)"
        return result(ProbeStatus.AVAILABLE, summary, findings, notes)
    }

    private fun result(
        status: ProbeStatus,
        summary: String,
        findings: List<Finding> = emptyList(),
        notes: List<String> = emptyList(),
    ) = ProtocolProbeResult(id, name, reference, status, summary, findings, notes)
}
