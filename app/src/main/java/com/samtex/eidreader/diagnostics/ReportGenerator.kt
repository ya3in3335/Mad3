package com.samtex.eidreader.diagnostics

import com.samtex.eidreader.BuildConfig
import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.domain.model.ScanResult
import com.samtex.eidreader.nfc.classifier.Finding
import com.samtex.eidreader.security.PrivacyManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

enum class ReportFormat(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    TXT("text/plain", "txt"),
}

data class ReportOptions(
    val format: ReportFormat,
    /** Include unredacted tag identifiers. Requires explicit user confirmation in the UI. */
    val includeIdentifiers: Boolean = false,
)

/**
 * Builds technical reports from a [ScanResult]. Identifiers are redacted unless
 * [ReportOptions.includeIdentifiers] is set. Reports never contain personal identity data
 * because SAMTEX never reads it.
 */
@Singleton
class ReportGenerator @Inject constructor(
    private val privacy: PrivacyManager,
    private val clock: Clock,
) {

    fun generate(result: ScanResult, options: ReportOptions): String = when (options.format) {
        ReportFormat.JSON -> Json.encode(toMap(result, options)) + "\n"
        ReportFormat.TXT -> toText(result, options)
    }

    fun fileName(result: ScanResult, format: ReportFormat): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(result.timestampMs))
        return "SAMTEX-report-$stamp.${format.extension}"
    }

    private fun iso(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    private fun finding(f: Finding, options: ReportOptions): Map<String, Any?> {
        val sensitive = f.key == "uid"
        return linkedMapOf(
            "key" to f.key,
            "label" to f.label,
            "value" to f.value,
            "provenance" to f.provenance.name,
            "explanation" to f.explanation,
            "hex" to if (sensitive && !options.includeIdentifiers) null else f.hex,
        )
    }

    fun toMap(result: ScanResult, options: ReportOptions): Map<String, Any?> {
        val c = result.classification
        val s = result.snapshot
        return linkedMapOf(
            "report" to linkedMapOf(
                "generator" to "SAMTEX",
                "appVersion" to BuildConfig.VERSION_NAME,
                "formatVersion" to 1,
                "generatedAt" to iso(clock.nowMs()),
                "offline" to true,
                "identifiersRedacted" to !options.includeIdentifiers,
                "notice" to "Technical diagnostic report. SAMTEX does not read protected personal data.",
            ),
            "scan" to linkedMapOf(
                "id" to result.id,
                "timestamp" to iso(result.timestampMs),
                "durationMs" to result.durationMs,
                "publicProbeEnabled" to result.options.publicProbe,
                "status" to if (result.error == null) "COMPLETED" else result.error.kind.name,
            ),
            "card" to linkedMapOf(
                "type" to c.cardType,
                "typeProvenance" to c.cardTypeProvenance.name,
                "family" to c.family.name,
                "isoStandards" to c.isoStandards,
                "technologies" to c.technologies,
                "uid" to privacy.identifier(s.uid, options.includeIdentifiers),
                "uidLength" to s.uid?.size,
                "manufacturer" to finding(c.manufacturer, options),
            ),
            "nfcA" to s.nfcA?.let {
                linkedMapOf(
                    "atqa" to c.atqa?.hex,
                    "atqaUidSize" to c.atqa?.uidSize?.label,
                    "sak" to "0x" + Hex.byte(it.sak),
                    "iso14443_4Compliant" to c.sak?.iso14443_4Compliant,
                    "maxTransceiveLength" to it.maxTransceiveLength,
                )
            },
            "isoDep" to linkedMapOf(
                "status" to result.isoDep.status.name,
                "fullAtsExposed" to false,
                "historicalBytes" to s.isoDep?.historicalBytes?.hex(),
                "historicalBytesAscii" to c.historicalBytes.ascii,
                "historicalBytesFormat" to c.historicalBytes.format.name,
                "hiLayerResponse" to s.isoDep?.hiLayerResponse?.hex(),
                "maxTransceiveLength" to result.isoDep.maxTransceiveLength,
                "extendedLengthApdu" to result.isoDep.extendedLengthSupported,
                "transceiveTimeoutMs" to result.isoDep.transceiveTimeoutMs,
                "apduExchanges" to result.isoDep.apduCount,
            ),
            "signature" to c.signature?.let {
                linkedMapOf(
                    "text" to it.text,
                    "hex" to it.hex,
                    "textProvenance" to "VERIFIED",
                    "interpretation" to it.interpretation,
                    "interpretationProvenance" to "INFERRED",
                )
            },
            "findings" to c.findings.map { finding(it, options) },
            "protocols" to result.probes.map { p ->
                linkedMapOf(
                    "id" to p.adapterId,
                    "name" to p.name,
                    "reference" to p.reference,
                    "status" to p.status.name,
                    "summary" to p.summary,
                    "findings" to p.findings.map { finding(it, options) },
                    "accessNotes" to p.accessNotes,
                )
            },
            "error" to result.error?.let {
                linkedMapOf(
                    "kind" to it.kind.name,
                    "title" to it.title,
                    "explanation" to it.explanation,
                    "recoverable" to it.recoverable,
                    "detail" to it.technicalDetail,
                )
            },
            "sessionLog" to result.log.map {
                linkedMapOf("t" to iso(it.timestampMs), "level" to it.level.name, "message" to it.message)
            },
        )
    }

    fun toText(result: ScanResult, options: ReportOptions): String = buildString {
        val c = result.classification
        val s = result.snapshot
        appendLine("SAMTEX — Smart NFC & eID Reader")
        appendLine("Technical scan report (v${BuildConfig.VERSION_NAME})")
        appendLine("=".repeat(48))
        appendLine("Generated:      ${iso(clock.nowMs())}")
        appendLine("Scan time:      ${iso(result.timestampMs)} (${result.durationMs} ms)")
        appendLine("Status:         ${result.error?.title ?: "Completed"}")
        appendLine("Identifiers:    ${if (options.includeIdentifiers) "INCLUDED (user confirmed)" else "redacted"}")
        appendLine()
        appendLine("[Card]")
        appendLine("Type:           ${c.cardType} [${c.cardTypeProvenance}]")
        appendLine("Technologies:   ${c.technologies.joinToString(", ")}")
        appendLine("Standards:      ${c.isoStandards.joinToString(", ")}")
        appendLine("Manufacturer:   ${c.manufacturer.value} [${c.manufacturer.provenance}]")
        privacy.identifier(s.uid, options.includeIdentifiers)?.let { appendLine("Tag ID:         $it") }
        c.atqa?.let { appendLine("ATQA:           ${it.hex} (UID ${it.uidSize.label})") }
        c.sak?.let { appendLine("SAK:            ${it.hex}${if (it.iso14443_4Compliant) " (ISO/IEC 14443-4)" else ""}") }
        appendLine()
        appendLine("[ISO-DEP]")
        appendLine("Status:         ${result.isoDep.status}")
        appendLine("ATS:            full frame not exposed by Android")
        s.isoDep?.historicalBytes?.let { hb ->
            appendLine("Historical:     ${hb.hex(" ")}")
            c.historicalBytes.ascii?.let { appendLine("  ASCII:        \"$it\"") }
            appendLine("  Format:       ${c.historicalBytes.format.label}")
        }
        result.isoDep.maxTransceiveLength?.let { appendLine("Max transceive: $it bytes") }
        result.isoDep.extendedLengthSupported?.let { appendLine("Extended APDU:  ${if (it) "supported" else "not supported"}") }
        appendLine("APDUs sent:     ${result.isoDep.apduCount}")
        c.signature?.let {
            appendLine()
            appendLine("[Signature]")
            appendLine("Detected:       ${it.text} (${it.hex}) [VERIFIED]")
            appendLine("Meaning:        ${it.interpretation} [INFERRED]")
        }
        if (result.probes.isNotEmpty()) {
            appendLine()
            appendLine("[Public protocol probes]")
            result.probes.forEach { p ->
                appendLine("- ${p.name}: ${p.status} — ${p.summary}")
                appendLine("  Ref: ${p.reference}")
                p.findings.forEach { f -> appendLine("  * ${f.label}: ${f.value} [${f.provenance}]") }
                p.accessNotes.forEach { appendLine("  ! $it") }
            }
        }
        appendLine()
        appendLine("[Findings]")
        c.findings.forEach { f ->
            val hex = if (f.key == "uid" && !options.includeIdentifiers) null else f.hex
            appendLine("- ${f.label}: ${f.value} [${f.provenance}]${hex?.let { " ($it)" } ?: ""}")
            f.explanation?.let { appendLine("    $it") }
        }
        result.error?.let {
            appendLine()
            appendLine("[Error]")
            appendLine("${it.kind}: ${it.title}")
            appendLine(it.explanation)
            if (it.technicalDetail.isNotBlank()) appendLine("Detail: ${it.technicalDetail}")
        }
        appendLine()
        appendLine("[Session log]")
        result.log.forEach { appendLine("${iso(it.timestampMs)} ${it.level.name.padEnd(7)} ${it.message}") }
    }
}
