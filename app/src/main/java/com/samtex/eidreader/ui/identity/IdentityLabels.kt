package com.samtex.eidreader.ui.identity

import androidx.annotation.StringRes
import com.samtex.eidreader.R
import com.samtex.eidreader.icao.IdentityFieldKey
import com.samtex.eidreader.icao.ReadOutcome
import com.samtex.eidreader.icao.ReadStep

@StringRes
fun outcomeTitle(o: ReadOutcome): Int = when (o) {
    ReadOutcome.NFC_NOT_AVAILABLE -> R.string.outcome_nfc_not_available_title
    ReadOutcome.NFC_DISABLED -> R.string.outcome_nfc_disabled_title
    ReadOutcome.CARD_REMOVED -> R.string.outcome_card_removed_title
    ReadOutcome.ISO_DEP_UNAVAILABLE -> R.string.outcome_iso_dep_unavailable_title
    ReadOutcome.APPLICATION_NOT_FOUND -> R.string.outcome_application_not_found_title
    ReadOutcome.PROTOCOL_UNSUPPORTED -> R.string.outcome_protocol_unsupported_title
    ReadOutcome.ACCESS_CREDENTIALS_REQUIRED -> R.string.outcome_access_credentials_required_title
    ReadOutcome.AUTHENTICATION_FAILED -> R.string.outcome_authentication_failed_title
    ReadOutcome.ACCESS_DENIED -> R.string.outcome_access_denied_title
    ReadOutcome.FILE_NOT_FOUND -> R.string.outcome_file_not_found_title
    ReadOutcome.DATA_FORMAT_UNSUPPORTED -> R.string.outcome_data_format_unsupported_title
    ReadOutcome.VERIFICATION_UNAVAILABLE -> R.string.outcome_verification_unavailable_title
    ReadOutcome.READING_SUCCESSFUL -> R.string.outcome_reading_successful_title
}

@StringRes
fun outcomeExplanation(o: ReadOutcome): Int = when (o) {
    ReadOutcome.NFC_NOT_AVAILABLE -> R.string.outcome_nfc_not_available
    ReadOutcome.NFC_DISABLED -> R.string.outcome_nfc_disabled
    ReadOutcome.CARD_REMOVED -> R.string.outcome_card_removed
    ReadOutcome.ISO_DEP_UNAVAILABLE -> R.string.outcome_iso_dep_unavailable
    ReadOutcome.APPLICATION_NOT_FOUND -> R.string.outcome_application_not_found
    ReadOutcome.PROTOCOL_UNSUPPORTED -> R.string.outcome_protocol_unsupported
    ReadOutcome.ACCESS_CREDENTIALS_REQUIRED -> R.string.outcome_access_credentials_required
    ReadOutcome.AUTHENTICATION_FAILED -> R.string.outcome_authentication_failed
    ReadOutcome.ACCESS_DENIED -> R.string.outcome_access_denied
    ReadOutcome.FILE_NOT_FOUND -> R.string.outcome_file_not_found
    ReadOutcome.DATA_FORMAT_UNSUPPORTED -> R.string.outcome_data_format_unsupported
    ReadOutcome.VERIFICATION_UNAVAILABLE -> R.string.outcome_verification_unavailable
    ReadOutcome.READING_SUCCESSFUL -> R.string.outcome_reading_successful
}

@StringRes
fun stepLabel(s: ReadStep): Int = when (s) {
    ReadStep.CONNECTING -> R.string.step_connecting
    ReadStep.READING_CARD_ACCESS -> R.string.step_card_access
    ReadStep.AUTHENTICATING -> R.string.step_authenticating
    ReadStep.READING_COM -> R.string.step_com
    ReadStep.READING_SOD -> R.string.step_sod
    ReadStep.READING_DG1 -> R.string.step_dg1
    ReadStep.READING_DG11 -> R.string.step_dg11
    ReadStep.READING_DG12 -> R.string.step_dg12
    ReadStep.READING_DG2 -> R.string.step_dg2
    ReadStep.VERIFYING -> R.string.step_verifying
    ReadStep.DONE -> R.string.step_done
}

@StringRes
fun fieldLabel(k: IdentityFieldKey): Int = when (k) {
    IdentityFieldKey.DOCUMENT_TYPE -> R.string.field_document_type
    IdentityFieldKey.ISSUING_STATE -> R.string.field_issuing_state
    IdentityFieldKey.DOCUMENT_NUMBER -> R.string.field_document_number
    IdentityFieldKey.SURNAME -> R.string.field_surname
    IdentityFieldKey.GIVEN_NAMES -> R.string.field_given_names
    IdentityFieldKey.NATIONALITY -> R.string.field_nationality
    IdentityFieldKey.DATE_OF_BIRTH -> R.string.field_date_of_birth
    IdentityFieldKey.SEX -> R.string.field_sex
    IdentityFieldKey.DATE_OF_EXPIRY -> R.string.field_date_of_expiry
    IdentityFieldKey.MRZ_OPTIONAL_DATA_1 -> R.string.field_mrz_optional_1
    IdentityFieldKey.MRZ_OPTIONAL_DATA_2 -> R.string.field_mrz_optional_2
    IdentityFieldKey.FULL_NAME_NATIONAL -> R.string.field_full_name_national
    IdentityFieldKey.OTHER_NAMES -> R.string.field_other_names
    IdentityFieldKey.PERSONAL_NUMBER -> R.string.field_personal_number
    IdentityFieldKey.FULL_DATE_OF_BIRTH -> R.string.field_full_date_of_birth
    IdentityFieldKey.PLACE_OF_BIRTH -> R.string.field_place_of_birth
    IdentityFieldKey.ADDRESS -> R.string.field_address
    IdentityFieldKey.TELEPHONE -> R.string.field_telephone
    IdentityFieldKey.PROFESSION -> R.string.field_profession
    IdentityFieldKey.TITLE -> R.string.field_title
    IdentityFieldKey.PERSONAL_SUMMARY -> R.string.field_personal_summary
    IdentityFieldKey.OTHER_TD_NUMBERS -> R.string.field_other_td_numbers
    IdentityFieldKey.CUSTODY_INFORMATION -> R.string.field_custody
    IdentityFieldKey.ISSUING_AUTHORITY -> R.string.field_issuing_authority
    IdentityFieldKey.DATE_OF_ISSUE -> R.string.field_date_of_issue
    IdentityFieldKey.NAMES_OF_OTHER_PERSONS -> R.string.field_other_persons
    IdentityFieldKey.ENDORSEMENTS -> R.string.field_endorsements
    IdentityFieldKey.TAX_EXIT_REQUIREMENTS -> R.string.field_tax_exit
    IdentityFieldKey.PERSONALIZATION_TIME -> R.string.field_personalization_time
}
