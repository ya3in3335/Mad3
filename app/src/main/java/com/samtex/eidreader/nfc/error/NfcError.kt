package com.samtex.eidreader.nfc.error

import java.io.IOException

/** Explicit classification of every failure the NFC engine can produce. */
enum class ErrorKind(val isConnectionLoss: Boolean = false, val isRecoverable: Boolean = true) {
    TAG_LOST(isConnectionLoss = true),
    TIMEOUT(isConnectionLoss = true),
    STALE_TAG(isConnectionLoss = true),
    COMMUNICATION,
    NOT_CONNECTED,
    UNSUPPORTED_TAG(isRecoverable = false),
    PAYLOAD_TOO_LARGE(isRecoverable = false),
    EXTENDED_LENGTH_UNSUPPORTED(isRecoverable = false),
    MALFORMED_RESPONSE,
    SESSION_BUSY,
    NFC_DISABLED,
    NFC_UNAVAILABLE(isRecoverable = false),
    POLICY_DENIED(isRecoverable = false),
    INVALID_INPUT,
    UNEXPECTED,
}

/**
 * Engine-level exception. Android-specific exceptions (TagLostException, SecurityException
 * for stale tags, …) are translated into this type at the transport boundary so that the rest
 * of the engine is platform-independent and unit-testable.
 *
 * Messages never contain card data.
 */
class NfcEngineException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
