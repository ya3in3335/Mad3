package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.nfc.classifier.Finding
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.nfc.transport.ApduChannel

enum class ProbeStatus {
    /** The documented object/application was found and read. */
    AVAILABLE,
    /** The card answered that the object/application does not exist. */
    NOT_PRESENT,
    /** The card requires authorization SAMTEX does not have (and does not attempt). */
    ACCESS_RESTRICTED,
    /** The probe failed for another reason (see findings). */
    ERROR,
}

data class ProtocolProbeResult(
    val adapterId: String,
    val name: String,
    val reference: String,
    val status: ProbeStatus,
    val summary: String,
    val findings: List<Finding> = emptyList(),
    /** Notes about protected data and the credentials it would require. */
    val accessNotes: List<String> = emptyList(),
)

/**
 * A protocol adapter encapsulates the documented, publicly readable part of one card
 * specification. Adapters must:
 * - only send commands defined by a public specification,
 * - never authenticate, verify PINs, or establish secure messaging,
 * - never enumerate files or applications beyond what the specification marks as public,
 * - report protected data as [ProbeStatus.ACCESS_RESTRICTED] instead of trying to read it.
 *
 * Future official, user-authorized access methods (e.g. PACE with a user-entered CAN) can be
 * added as separate adapters without touching the transport or UI layers.
 */
interface CardProtocolAdapter {
    val id: String
    val name: String
    val reference: String

    fun isApplicable(snapshot: TagSnapshot): Boolean

    suspend fun probe(channel: ApduChannel): ProtocolProbeResult
}
