package com.samtex.eidreader.icao

import com.samtex.eidreader.icao.lds.PortraitImage
import com.samtex.eidreader.icao.mrz.MrzData
import com.samtex.eidreader.icao.pa.PassiveAuthReport

/** Explicit outcome categories of an identity read (shown to the user with Arabic explanations). */
enum class ReadOutcome {
    NFC_NOT_AVAILABLE,
    NFC_DISABLED,
    CARD_REMOVED,
    ISO_DEP_UNAVAILABLE,
    APPLICATION_NOT_FOUND,
    PROTOCOL_UNSUPPORTED,
    ACCESS_CREDENTIALS_REQUIRED,
    AUTHENTICATION_FAILED,
    ACCESS_DENIED,
    FILE_NOT_FOUND,
    DATA_FORMAT_UNSUPPORTED,
    VERIFICATION_UNAVAILABLE,
    READING_SUCCESSFUL,
}

enum class AccessProtocol(val label: String) {
    PACE_MRZ("PACE (MRZ)"),
    PACE_CAN("PACE (CAN)"),
    BAC("BAC"),
}

enum class ReadStep {
    CONNECTING,
    READING_CARD_ACCESS,
    AUTHENTICATING,
    READING_COM,
    READING_SOD,
    READING_DG1,
    READING_DG11,
    READING_DG12,
    READING_DG2,
    VERIFYING,
    DONE,
}

enum class FileReadStatus { READ, NOT_PRESENT, ACCESS_DENIED, ERROR, NOT_REQUESTED, SKIPPED_BY_POLICY }

/** Origin of an identity value, shown next to it. */
enum class FieldSource(val label: String) { DG1_MRZ("DG1 · MRZ"), DG11("DG11"), DG12("DG12") }

enum class IdentityFieldKey {
    DOCUMENT_TYPE, ISSUING_STATE, DOCUMENT_NUMBER, SURNAME, GIVEN_NAMES, NATIONALITY, DATE_OF_BIRTH, SEX,
    DATE_OF_EXPIRY, MRZ_OPTIONAL_DATA_1, MRZ_OPTIONAL_DATA_2,
    FULL_NAME_NATIONAL, OTHER_NAMES, PERSONAL_NUMBER, FULL_DATE_OF_BIRTH, PLACE_OF_BIRTH, ADDRESS, TELEPHONE,
    PROFESSION, TITLE, PERSONAL_SUMMARY, OTHER_TD_NUMBERS, CUSTODY_INFORMATION,
    ISSUING_AUTHORITY, DATE_OF_ISSUE, NAMES_OF_OTHER_PERSONS, ENDORSEMENTS, TAX_EXIT_REQUIREMENTS, PERSONALIZATION_TIME,
}

data class IdentityField(
    val key: IdentityFieldKey,
    val value: String,
    val source: FieldSource,
    /** True when the source data group's hash matched the signed SOD; null when not checked. */
    val hashVerified: Boolean?,
)

/** Personal data of one session. Lives only in memory and is wiped by [wipe]. */
class IdentityRecord(
    val fields: List<IdentityField>,
    val mrz: MrzData?,
    portrait: PortraitImage?,
    /** Data groups present on the card but intentionally not decoded (e.g. proprietary DG13). */
    val undecodedDataGroups: List<Int>,
) {
    @Volatile
    var portrait: PortraitImage? = portrait
        private set

    fun field(key: IdentityFieldKey): IdentityField? = fields.firstOrNull { it.key == key }

    fun wipe() {
        portrait?.wipe()
        portrait = null
    }
}

/** What happened technically. Contains no personal data and no secrets. */
data class IdentityTechnicalReport(
    val protocol: AccessProtocol?,
    val paceProtocolOid: String?,
    val paceAdvertised: Boolean,
    val files: Map<String, FileReadStatus>,
    val apduCount: Int,
    val durationMs: Long,
    val log: List<String>,
)

data class TrustLevels(
    val nfcConnection: Boolean,
    val accessAuthenticated: Boolean,
    val dataRead: Boolean,
    val hashesMatched: Boolean,
    val signatureVerified: Boolean,
    val chainVerified: Boolean,
) {
    /** Authenticity requires a full passive-authentication chain to a trusted CSCA. */
    val authenticityEstablished: Boolean
        get() = dataRead && hashesMatched && signatureVerified && chainVerified
}

data class IdentityReadResult(
    val outcome: ReadOutcome,
    val record: IdentityRecord?,
    val passiveAuth: PassiveAuthReport?,
    val trust: TrustLevels,
    val technical: IdentityTechnicalReport,
    /** Sanitized technical detail (no secrets, no personal data). */
    val detail: String?,
) {
    companion object {
        fun failure(outcome: ReadOutcome, detail: String? = null) = IdentityReadResult(
            outcome = outcome,
            record = null,
            passiveAuth = null,
            trust = TrustLevels(false, false, false, false, false, false),
            technical = IdentityTechnicalReport(null, null, false, emptyMap(), 0, 0, emptyList()),
            detail = detail,
        )
    }
}
