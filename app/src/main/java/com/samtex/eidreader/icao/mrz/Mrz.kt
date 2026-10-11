package com.samtex.eidreader.icao.mrz

/** ICAO Doc 9303 Part 3 check digit (weights 7-3-1; digits, A–Z = 10–35, '<' = 0). */
object MrzCheckDigit {
    private val WEIGHTS = intArrayOf(7, 3, 1)

    fun valueOf(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        '<' -> 0
        else -> throw IllegalArgumentException("Invalid MRZ character '$c'")
    }

    fun compute(field: CharSequence): Char {
        var sum = 0
        for (i in field.indices) sum += valueOf(field[i]) * WEIGHTS[i % 3]
        return '0' + (sum % 10)
    }

    fun isValid(field: CharSequence, check: Char): Boolean = try {
        // '<' as a check digit is used for empty optional fields and equals 0.
        val expected = compute(field)
        check == expected || (check == '<' && expected == '0' && field.all { it == '<' })
    } catch (_: IllegalArgumentException) {
        false
    }
}

enum class MrzFormat(val lineLength: Int, val lines: Int) {
    TD1(30, 3),
    TD2(36, 2),
    TD3(44, 2),
}

/** Parsed machine readable zone. Raw values keep ICAO filler characters removed. */
data class MrzData(
    val format: MrzFormat,
    val documentCode: String,
    val issuingState: String,
    val documentNumber: String,
    val documentNumberValid: Boolean,
    val optionalData1: String,
    val dateOfBirth: String,
    val dateOfBirthValid: Boolean,
    val sex: String,
    val dateOfExpiry: String,
    val dateOfExpiryValid: Boolean,
    val nationality: String,
    val optionalData2: String,
    val primaryIdentifier: String,
    val secondaryIdentifier: String,
    val compositeValid: Boolean,
) {
    val allChecksValid: Boolean
        get() = documentNumberValid && dateOfBirthValid && dateOfExpiryValid && compositeValid
}

class MrzFormatException(message: String) : Exception(message)

/** Parser for TD1 (ID cards, 3×30), TD2 (2×36) and TD3 (passports, 2×44) MRZs. */
object MrzParser {

    /** Accepts either line-separated text or the concatenated form stored in DG1. */
    fun parse(input: String): MrzData {
        val clean = input.replace("\r", "").replace("\n", "").replace(" ", "").uppercase()
        if (!clean.all { it in 'A'..'Z' || it in '0'..'9' || it == '<' }) {
            throw MrzFormatException("MRZ contains characters outside A–Z, 0–9 and '<'")
        }
        return when (clean.length) {
            90 -> parseTd1(clean)
            72 -> parseTd2or3(clean, MrzFormat.TD2)
            88 -> parseTd2or3(clean, MrzFormat.TD3)
            else -> throw MrzFormatException("Unsupported MRZ length ${clean.length}")
        }
    }

    private fun strip(s: String) = s.trimEnd('<')

    private fun names(field: String): Pair<String, String> {
        val idx = field.indexOf("<<")
        val primary = if (idx >= 0) field.substring(0, idx) else field
        val secondary = if (idx >= 0) field.substring(idx + 2) else ""
        fun tidy(s: String) = s.trimEnd('<').replace('<', ' ').trim()
        return tidy(primary) to tidy(secondary)
    }

    private fun parseTd1(m: String): MrzData {
        val l1 = m.substring(0, 30)
        val l2 = m.substring(30, 60)
        val l3 = m.substring(60, 90)
        var docNumber = l1.substring(5, 14)
        var docCheck = l1[14]
        var optional1 = l1.substring(15, 30)
        // Long document numbers (> 9 chars) continue in the optional data field (Doc 9303 Part 5).
        if (docCheck == '<') {
            val ext = optional1.substringBefore('<')
            if (ext.isNotEmpty()) {
                docNumber += ext.dropLast(1)
                docCheck = ext.last()
                optional1 = optional1.substring(ext.length)
            }
        }
        val composite = l1.substring(5, 30) + l2.substring(0, 7) + l2.substring(8, 15) + l2.substring(18, 29)
        val (primary, secondary) = names(l3)
        return MrzData(
            format = MrzFormat.TD1,
            documentCode = strip(l1.substring(0, 2)),
            issuingState = strip(l1.substring(2, 5)),
            documentNumber = strip(docNumber),
            documentNumberValid = MrzCheckDigit.isValid(docNumber, docCheck),
            optionalData1 = strip(optional1),
            dateOfBirth = l2.substring(0, 6),
            dateOfBirthValid = MrzCheckDigit.isValid(l2.substring(0, 6), l2[6]),
            sex = l2.substring(7, 8).replace('<', 'X'),
            dateOfExpiry = l2.substring(8, 14),
            dateOfExpiryValid = MrzCheckDigit.isValid(l2.substring(8, 14), l2[14]),
            nationality = strip(l2.substring(15, 18)),
            optionalData2 = strip(l2.substring(18, 29)),
            primaryIdentifier = primary,
            secondaryIdentifier = secondary,
            compositeValid = MrzCheckDigit.isValid(composite, l2[29]),
        )
    }

    private fun parseTd2or3(m: String, format: MrzFormat): MrzData {
        val len = format.lineLength
        val l1 = m.substring(0, len)
        val l2 = m.substring(len, 2 * len)
        val optionalEnd = len - 2
        val composite = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, len - 1)
        val (primary, secondary) = names(l1.substring(5))
        val optional = l2.substring(28, optionalEnd)
        val optionalValid = if (format == MrzFormat.TD3) MrzCheckDigit.isValid(optional, l2[42]) else true
        return MrzData(
            format = format,
            documentCode = strip(l1.substring(0, 2)),
            issuingState = strip(l1.substring(2, 5)),
            documentNumber = strip(l2.substring(0, 9)),
            documentNumberValid = MrzCheckDigit.isValid(l2.substring(0, 9), l2[9]),
            optionalData1 = strip(if (format == MrzFormat.TD3) l2.substring(28, 42) else optional + l2[optionalEnd]),
            dateOfBirth = l2.substring(13, 19),
            dateOfBirthValid = MrzCheckDigit.isValid(l2.substring(13, 19), l2[19]),
            sex = l2.substring(20, 21).replace('<', 'X'),
            dateOfExpiry = l2.substring(21, 27),
            dateOfExpiryValid = MrzCheckDigit.isValid(l2.substring(21, 27), l2[27]),
            nationality = strip(l2.substring(10, 13)),
            optionalData2 = "",
            primaryIdentifier = primary,
            secondaryIdentifier = secondary,
            compositeValid = optionalValid && MrzCheckDigit.isValid(composite, l2[len - 1]),
        )
    }
}
