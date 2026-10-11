package com.samtex.eidreader.security

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central redaction rules.
 *
 * - Tag identifiers are redacted by default everywhere (UI, logs, reports, history). Only the
 *   first byte (which carries the UID type / manufacturer code, not identity) is kept.
 * - APDU response data is only kept in logs when it comes from a file that the governing
 *   specification declares public (e.g. EF.CardAccess). Everything else is reduced to its length.
 * - Nothing in SAMTEX writes card data to Logcat.
 */
@Singleton
class PrivacyManager @Inject constructor() {

    fun redactIdentifier(id: ByteString): String {
        if (id.isEmpty()) return ""
        val sb = StringBuilder(Hex.byte(id[0]))
        repeat(id.size - 1) { sb.append(" **") }
        return sb.toString()
    }

    fun identifier(id: ByteString?, reveal: Boolean): String? = when {
        id == null -> null
        reveal -> id.hex(" ")
        else -> redactIdentifier(id)
    }

    fun sanitizeData(data: ByteArray, publicData: Boolean): String = when {
        data.isEmpty() -> ""
        publicData -> Hex.encode(data, " ")
        else -> "<${data.size} bytes redacted>"
    }

    /** Removes anything that looks like long hex strings or digit runs from free-text messages. */
    fun sanitizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return ""
        return message
            .replace(LONG_HEX, "<redacted>")
            .replace(LONG_DIGITS, "<redacted>")
            .take(MAX_MESSAGE)
    }

    companion object {
        private val LONG_HEX = Regex("\\b[0-9A-Fa-f]{2}(?:[ :]?[0-9A-Fa-f]{2}){7,}\\b")
        private val LONG_DIGITS = Regex("\\d{9,}")
        private const val MAX_MESSAGE = 300
    }
}
