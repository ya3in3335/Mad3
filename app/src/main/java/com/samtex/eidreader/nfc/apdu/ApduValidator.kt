package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

data class ValidationIssue(val severity: Severity, val message: String) {
    enum class Severity { ERROR, WARNING }
}

/** Device/transport limits relevant for validating an APDU before transmission. */
data class TransportLimits(
    val maxTransceiveLength: Int,
    val extendedLengthSupported: Boolean,
)

/**
 * Structural validation of command APDUs against ISO/IEC 7816-3/-4 and the limits reported by
 * the Android transport. Errors block transmission; warnings are surfaced in diagnostics.
 */
object ApduValidator {

    fun validate(command: CommandApdu, limits: TransportLimits?): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (command.cla == 0xFF) {
            issues += ValidationIssue(ValidationIssue.Severity.ERROR, "CLA FF is invalid (reserved for PPS)")
        }
        val insHigh = command.ins and 0xF0
        if (insHigh == 0x60 || insHigh == 0x90) {
            issues += ValidationIssue(
                ValidationIssue.Severity.ERROR,
                "INS ${Hex.byte(command.ins)} is invalid: 6X and 9X are reserved for procedure/status bytes",
            )
        }
        if (limits != null) {
            val encodedLength = command.encode().size
            if (command.isExtended && !limits.extendedLengthSupported) {
                issues += ValidationIssue(
                    ValidationIssue.Severity.ERROR,
                    "Extended-length APDU but this device/card combination does not support extended length",
                )
            }
            if (encodedLength > limits.maxTransceiveLength) {
                issues += ValidationIssue(
                    ValidationIssue.Severity.ERROR,
                    "APDU is $encodedLength bytes but the transport accepts at most ${limits.maxTransceiveLength}",
                )
            }
            val ne = command.ne
            if (ne != null && ne + 2 > limits.maxTransceiveLength) {
                issues += ValidationIssue(
                    ValidationIssue.Severity.WARNING,
                    "Requested Ne=$ne may exceed the transceive buffer (${limits.maxTransceiveLength} bytes)",
                )
            }
        }
        return issues
    }

    fun hasErrors(issues: List<ValidationIssue>): Boolean =
        issues.any { it.severity == ValidationIssue.Severity.ERROR }
}
