package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.apdu.StatusWords
import com.samtex.eidreader.nfc.classifier.Finding
import com.samtex.eidreader.nfc.classifier.Provenance
import com.samtex.eidreader.nfc.tag.NfcTechnology
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduChannel
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** One application template (tag 61) from EF.DIR. */
data class DirectoryEntry(val aid: String, val label: String?, val knownAs: String?)

/**
 * Reads the ISO/IEC 7816-4 application directory EF.DIR (FID 2F00) from the MF, which lists
 * the applications a card advertises publicly. This replaces any form of AID brute-forcing:
 * SAMTEX only learns about applications the card itself publishes.
 *
 * EF.DIR may be transparent (READ BINARY) or linear (READ RECORD); both are supported with
 * strict bounds (max 1 KiB / 16 records).
 */
class EfDirAdapter @Inject constructor() : CardProtocolAdapter {
    override val id = "iso.efdir"
    override val name = "EF.DIR (application directory)"
    override val reference = "ISO/IEC 7816-4 (EF.DIR, FID 2F00)"

    override fun isApplicable(snapshot: TagSnapshot): Boolean = snapshot.has(NfcTechnology.ISO_DEP)

    override suspend fun probe(channel: ApduChannel): ProtocolProbeResult {
        // Some cards reject an explicit MF selection but are positioned on the MF after reset anyway,
        // so the status of this SELECT is informational only.
        channel.transmit(ApduCommands.selectMasterFile(), "SELECT MF", publicData = true)
        val select = channel.transmit(ApduCommands.selectEf(ApduCommands.FID_EF_DIR), "SELECT EF.DIR", publicData = true)
        if (!select.status.isSuccess) {
            return when (select.status.sw) {
                StatusWords.SW_FILE_NOT_FOUND -> result(ProbeStatus.NOT_PRESENT, "EF.DIR not present (the card does not publish an application directory)")
                StatusWords.SW_SECURITY_STATUS_NOT_SATISFIED -> result(ProbeStatus.ACCESS_RESTRICTED, "EF.DIR access restricted")
                else -> result(ProbeStatus.ERROR, "SELECT EF.DIR returned ${select.status}")
            }
        }
        val content = readContent(channel) ?: return result(ProbeStatus.ERROR, "EF.DIR could not be read")
        val entries = parse(content)
        val findings = mutableListOf(
            Finding("efdir.raw", "EF.DIR", "${content.size} bytes", Provenance.VERIFIED, "Public application directory.", Hex.encode(content)),
        )
        entries.forEachIndexed { i, e ->
            findings += Finding(
                key = "efdir.app.$i",
                label = e.label ?: "Application",
                value = e.aid + (e.knownAs?.let { " — $it" } ?: ""),
                provenance = if (e.knownAs != null) Provenance.INFERRED else Provenance.VERIFIED,
                explanation = if (e.knownAs != null) "AID listed by the card; name from public AID registrations." else "AID listed by the card.",
                hex = e.aid,
            )
        }
        return result(ProbeStatus.AVAILABLE, "${entries.size} application(s) listed", findings)
    }

    private suspend fun readContent(channel: ApduChannel): ByteArray? {
        val out = ByteArrayOutputStream()
        // Transparent structure first.
        while (out.size() < MAX_BYTES) {
            val len = minOf(channel.maxShortReadLength, MAX_BYTES - out.size())
            val r = channel.transmit(ApduCommands.readBinary(out.size(), len), "READ BINARY EF.DIR @${out.size()}", publicData = true)
            when {
                r.status.isSuccess -> {
                    out.write(r.response.data)
                    if (r.response.dataLength < len) break
                }
                r.response.sw == StatusWords.SW_END_OF_FILE -> {
                    out.write(r.response.data)
                    break
                }
                r.response.sw == 0x6B00 && out.size() > 0 -> break
                r.response.sw == 0x6981 -> return readRecords(channel)
                else -> return if (out.size() > 0) out.toByteArray() else null
            }
            if (r.response.dataLength == 0) break
        }
        return out.toByteArray()
    }

    private suspend fun readRecords(channel: ApduChannel): ByteArray? {
        val out = ByteArrayOutputStream()
        for (record in 1..MAX_RECORDS) {
            val cmd = CommandApdu(0x00, 0xB2, record, 0x04, ne = 256)
            val r = channel.transmit(cmd, "READ RECORD EF.DIR #$record", publicData = true)
            if (!r.status.isSuccess) break
            out.write(r.response.data)
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    companion object {
        private const val MAX_BYTES = 1024
        private const val MAX_RECORDS = 16

        /** Public, documented AIDs only (no guessing). */
        private val KNOWN_AIDS = mapOf(
            "A0000002471001" to "ICAO eMRTD LDS1 application (ICAO Doc 9303)",
            "A0000002472001" to "ICAO LDS2 travel records application (ICAO Doc 9303)",
            "E80704007F00070302" to "BSI TR-03110 eID application",
            "A000000167455349474E" to "eSign application (CEN EN 419212)",
        )

        fun parse(content: ByteArray): List<DirectoryEntry> {
            val nodes = BerTlv.parseOrNull(content) ?: return emptyList()
            return nodes.filter { it.tag == 0x61 }.mapNotNull { t ->
                val aid = t.children.firstOrNull { it.tag == 0x4F }?.value?.let { Hex.encode(it) } ?: return@mapNotNull null
                val label = t.children.firstOrNull { it.tag == 0x50 }?.value
                    ?.takeIf { Hex.isPrintableAscii(it) }?.let { String(it, Charsets.US_ASCII) }
                DirectoryEntry(aid, label, KNOWN_AIDS[aid])
            }
        }
    }

    private fun result(status: ProbeStatus, summary: String, findings: List<Finding> = emptyList()) =
        ProtocolProbeResult(id, name, reference, status, summary, findings)
}
