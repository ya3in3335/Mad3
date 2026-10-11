package com.samtex.eidreader.icao.access

import com.samtex.eidreader.icao.mrz.MrzCheckDigit
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Card-holder supplied access data for ICAO Doc 9303 access control (BAC / PACE-MRZ / PACE-CAN).
 *
 * Secrets are kept in [CharArray]s so they can be wiped with [close] as soon as the read
 * attempt ends. They are never persisted, logged or exported. (JMRTD's key classes require
 * `String`s; those short-lived copies cannot be wiped and are left to the garbage collector.)
 */
class AccessCredentials private constructor(
    private val documentNumber: CharArray?,
    private val dateOfBirth: CharArray?,
    private val dateOfExpiry: CharArray?,
    private val can: CharArray?,
) : AutoCloseable {

    @Volatile
    private var wiped = false

    val hasMrzKey: Boolean get() = !wiped && documentNumber != null && dateOfBirth != null && dateOfExpiry != null
    val hasCan: Boolean get() = !wiped && can != null

    /** Calls [block] with (documentNumber, YYMMDD birth, YYMMDD expiry). */
    fun <T> withMrzKey(block: (String, String, String) -> T): T {
        check(hasMrzKey) { "MRZ access data not available" }
        return block(String(documentNumber!!), String(dateOfBirth!!), String(dateOfExpiry!!))
    }

    fun <T> withCan(block: (String) -> T): T {
        check(hasCan) { "CAN not available" }
        return block(String(can!!))
    }

    /** ICAO "MRZ information" used for key derivation: docNo(+filler)+cd + DOB+cd + DOE+cd. */
    fun mrzInformation(): String = withMrzKey { doc, dob, doe ->
        val paddedDoc = doc.padEnd(9, '<')
        paddedDoc + MrzCheckDigit.compute(paddedDoc) + dob + MrzCheckDigit.compute(dob) + doe + MrzCheckDigit.compute(doe)
    }

    override fun close() {
        wiped = true
        documentNumber?.fill('\u0000')
        dateOfBirth?.fill('\u0000')
        dateOfExpiry?.fill('\u0000')
        can?.fill('\u0000')
    }

    sealed interface Validation {
        data class Valid(val credentials: AccessCredentials) : Validation
        data class Invalid(val errors: Set<Problem>) : Validation
    }

    enum class Problem { DOCUMENT_NUMBER, DATE_OF_BIRTH, DATE_OF_EXPIRY, CAN, MISSING }

    companion object {
        private val YYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
        private val DOC_NUMBER = Regex("[A-Z0-9]{1,22}")
        private val CAN_FORMAT = Regex("[0-9]{6}")

        fun normalizeDocumentNumber(input: String): String =
            input.uppercase().filter { !it.isWhitespace() && it != '-' && it != '<' }

        /**
         * Builds credentials. Either a complete MRZ key (number + birth + expiry) or a CAN must be
         * supplied; both may be given (CAN is then preferred for PACE when the card supports it).
         */
        fun create(
            documentNumber: String?,
            dateOfBirth: LocalDate?,
            dateOfExpiry: LocalDate?,
            can: String?,
            today: LocalDate = LocalDate.now(),
        ): Validation {
            val problems = mutableSetOf<Problem>()
            val doc = documentNumber?.let(::normalizeDocumentNumber)?.takeIf { it.isNotEmpty() }
            val anyMrz = doc != null || dateOfBirth != null || dateOfExpiry != null
            val canDigits = can?.filter { !it.isWhitespace() }?.takeIf { it.isNotEmpty() }

            if (anyMrz) {
                if (doc == null || !DOC_NUMBER.matches(doc)) problems += Problem.DOCUMENT_NUMBER
                if (dateOfBirth == null || dateOfBirth.isAfter(today) || dateOfBirth.year < 1900) problems += Problem.DATE_OF_BIRTH
                if (dateOfExpiry == null || (dateOfBirth != null && !dateOfExpiry.isAfter(dateOfBirth))) problems += Problem.DATE_OF_EXPIRY
            }
            if (canDigits != null && !CAN_FORMAT.matches(canDigits)) problems += Problem.CAN
            if (!anyMrz && canDigits == null) problems += Problem.MISSING
            if (problems.isNotEmpty()) return Validation.Invalid(problems)

            return Validation.Valid(
                AccessCredentials(
                    documentNumber = doc?.toCharArray(),
                    dateOfBirth = dateOfBirth?.format(YYMMDD)?.toCharArray(),
                    dateOfExpiry = dateOfExpiry?.format(YYMMDD)?.toCharArray(),
                    can = canDigits?.toCharArray(),
                ),
            )
        }
    }
}
