package com.samtex.eidreader.diagnostics

import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcErrorHandler
import com.samtex.eidreader.security.PrivacyManager
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticError(
    val kind: ErrorKind,
    val title: String,
    val explanation: String,
    val suggestion: String,
    val recoverable: Boolean,
    /** Sanitized technical message (no card data). */
    val technicalDetail: String,
)

/** Turns failures into explicit, user-facing diagnostics with a suggested remedy. */
@Singleton
class ErrorAnalyzer @Inject constructor(
    private val handler: NfcErrorHandler,
    private val privacy: PrivacyManager,
) {
    fun analyze(t: Throwable): DiagnosticError = forKind(handler.classify(t), privacy.sanitizeMessage(t.message))

    fun forKind(kind: ErrorKind, detail: String = ""): DiagnosticError {
        val (title, explanation, suggestion) = when (kind) {
            ErrorKind.TAG_LOST -> Triple(
                "Connection lost",
                "The card left the NFC field before communication finished.",
                "Hold the card flat and still against the back of the phone, then scan again.",
            )
            ErrorKind.TIMEOUT -> Triple(
                "Timeout",
                "The card did not answer within the allowed time.",
                "Keep the card still and avoid metal cases or other cards nearby.",
            )
            ErrorKind.STALE_TAG -> Triple(
                "Card session expired",
                "Android invalidated the tag handle (the card was re-detected).",
                "Scan the card again.",
            )
            ErrorKind.COMMUNICATION -> Triple(
                "Communication error",
                "The NFC controller reported an I/O failure.",
                "Reposition the card and scan again.",
            )
            ErrorKind.NOT_CONNECTED -> Triple(
                "Not connected",
                "No active ISO-DEP connection was available.",
                "Scan the card again.",
            )
            ErrorKind.UNSUPPORTED_TAG -> Triple(
                "Unsupported tag",
                "This tag does not support the requested operation.",
                "Only passive metadata is available for this tag.",
            )
            ErrorKind.PAYLOAD_TOO_LARGE -> Triple(
                "Command too long",
                "The command exceeds this phone's maximum transceive length.",
                "Use a shorter command.",
            )
            ErrorKind.EXTENDED_LENGTH_UNSUPPORTED -> Triple(
                "Extended length not supported",
                "This phone/card combination does not support extended-length APDUs.",
                "Use short APDUs.",
            )
            ErrorKind.MALFORMED_RESPONSE -> Triple(
                "Malformed response",
                "The card's answer did not contain a valid status word.",
                "Scan again; if this persists the card may not follow ISO/IEC 7816-4.",
            )
            ErrorKind.SESSION_BUSY -> Triple(
                "Busy",
                "Another communication session with the card is already in progress.",
                "Wait for the current operation to finish.",
            )
            ErrorKind.NFC_DISABLED -> Triple(
                "NFC is disabled",
                "NFC is turned off in the system settings.",
                "Enable NFC in Settings.",
            )
            ErrorKind.NFC_UNAVAILABLE -> Triple(
                "NFC unavailable",
                "This device has no NFC controller.",
                "Use a device with NFC.",
            )
            ErrorKind.POLICY_DENIED -> Triple(
                "Command not permitted",
                "The command is outside SAMTEX's documented read-only allowlist.",
                "Only documented, read-only commands can be sent.",
            )
            ErrorKind.INVALID_INPUT -> Triple(
                "Invalid input",
                "The provided data is not valid.",
                "Check the hexadecimal input.",
            )
            ErrorKind.UNEXPECTED -> Triple(
                "Unexpected error",
                "An unexpected internal error occurred.",
                "Scan again. The error is recorded in the session log.",
            )
        }
        return DiagnosticError(kind, title, explanation, suggestion, kind.isRecoverable, detail)
    }
}
