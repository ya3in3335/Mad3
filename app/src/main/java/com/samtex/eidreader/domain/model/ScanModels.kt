package com.samtex.eidreader.domain.model

import com.samtex.eidreader.diagnostics.DiagnosticError
import com.samtex.eidreader.diagnostics.LogEntry
import com.samtex.eidreader.nfc.classifier.CardClassification
import com.samtex.eidreader.nfc.protocol.ProtocolProbeResult
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduTransport

enum class ScanPhase {
    NFC_UNAVAILABLE,
    NFC_DISABLED,
    WAITING,
    CARD_DETECTED,
    CONNECTING,
    READING,
    COMPLETED,
    CONNECTION_LOST,
    ERROR,
}

enum class IsoDepStatus {
    /** The tag does not expose IsoDep. */
    NOT_SUPPORTED,
    /** IsoDep available, connection established and closed cleanly. */
    ESTABLISHED,
    /** Connection or exchange failed. */
    FAILED,
    /** The card left the field during communication. */
    CONNECTION_LOST,
}

data class IsoDepSessionReport(
    val status: IsoDepStatus,
    val maxTransceiveLength: Int? = null,
    val extendedLengthSupported: Boolean? = null,
    val transceiveTimeoutMs: Int? = null,
    val apduCount: Int = 0,
)

data class ScanOptions(
    /** Run the documented public probes (ICAO EF.CardAccess, eMRTD AID SELECT). */
    val publicProbe: Boolean = true,
)

data class ScanResult(
    val id: String,
    val timestampMs: Long,
    val durationMs: Long,
    val snapshot: TagSnapshot,
    val classification: CardClassification,
    val isoDep: IsoDepSessionReport,
    val probes: List<ProtocolProbeResult>,
    val log: List<LogEntry>,
    val error: DiagnosticError?,
    val options: ScanOptions,
)

/** Abstraction of a discovered card so the domain layer never touches android.nfc.Tag. */
interface DiscoveredCard {
    val snapshot: TagSnapshot

    /** Returns a fresh ISO-DEP transport, or null when the tag does not support ISO-DEP. */
    fun openIsoDepTransport(): ApduTransport?
}
