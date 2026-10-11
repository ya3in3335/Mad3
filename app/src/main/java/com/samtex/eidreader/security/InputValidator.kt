package com.samtex.eidreader.security

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.CommandApdu

/** Validation of all user-supplied input (diagnostics console, offline decoder). */
object InputValidator {

    const val MAX_INPUT_CHARS = 2 * (4 + 3 + 255 + 1) + 200

    sealed interface Result<out T> {
        data class Valid<T>(val value: T) : Result<T>
        data class Invalid(val reason: String) : Result<Nothing>
    }

    fun parseHex(input: String, minBytes: Int = 1, maxBytes: Int = 261): Result<ByteArray> {
        if (input.length > MAX_INPUT_CHARS) return Result.Invalid("Input is too long")
        if (input.isBlank()) return Result.Invalid("Input is empty")
        val bytes = try {
            Hex.decode(input)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid(e.message ?: "Invalid hexadecimal input")
        }
        if (bytes.size < minBytes) return Result.Invalid("At least $minBytes byte(s) required")
        if (bytes.size > maxBytes) return Result.Invalid("At most $maxBytes bytes allowed")
        return Result.Valid(bytes)
    }

    /** Parses a short command APDU typed by the user (extended APDUs are not accepted in the console). */
    fun parseCommandApdu(input: String): Result<CommandApdu> {
        return when (val hex = parseHex(input, minBytes = 4, maxBytes = 4 + 1 + 255 + 1)) {
            is Result.Invalid -> hex
            is Result.Valid -> try {
                val apdu = CommandApdu.decode(hex.value)
                if (apdu.isExtended) Result.Invalid("Extended-length APDUs are not accepted in the console") else Result.Valid(apdu)
            } catch (e: IllegalArgumentException) {
                Result.Invalid(e.message ?: "Malformed APDU")
            }
        }
    }

    /** Parses a response APDU (data + SW1 SW2) for offline decoding. */
    fun parseResponseApdu(input: String): Result<ByteArray> = parseHex(input, minBytes = 2, maxBytes = 65538)
}
