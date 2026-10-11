package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

enum class StatusCategory {
    /** 9000, 61XX */
    NORMAL,
    /** 62XX, 63XX */
    WARNING,
    /** 64XX, 65XX, 66XX */
    EXECUTION_ERROR,
    /** 67XX – 6FXX */
    CHECKING_ERROR,
    /** Anything outside the ISO/IEC 7816-4 interindustry ranges (e.g. 9XXX proprietary). */
    PROPRIETARY,
    /** Not a valid status word at all (e.g. SW1 outside 6X/9X). */
    INVALID,
}

data class StatusWordInfo(
    val sw: Int,
    val category: StatusCategory,
    val description: String,
    /** For 63CX: remaining retry counter X. Never used to drive retries. */
    val counter: Int? = null,
    /** For 61XX / 6CXX: the length indicated by SW2 (0 meaning 256). */
    val indicatedLength: Int? = null,
) {
    val hex: String get() = Hex.u16(sw)
    val isSuccess: Boolean get() = sw == StatusWords.SW_SUCCESS
    val isSecurityRelated: Boolean
        get() = sw in setOf(0x6982, 0x6983, 0x6984, 0x6985, 0x6987, 0x6988) || (sw and 0xFFF0) == 0x63C0

    override fun toString(): String = "$hex — $description"
}

/**
 * Status word interpreter based on ISO/IEC 7816-4 (interindustry
 * SW1-SW2 values). Proprietary values are reported as such instead of being guessed.
 */
object StatusWords {
    const val SW_SUCCESS = 0x9000
    const val SW_FILE_NOT_FOUND = 0x6A82
    const val SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982
    const val SW_WRONG_LENGTH = 0x6700
    const val SW_INS_NOT_SUPPORTED = 0x6D00
    const val SW_CLA_NOT_SUPPORTED = 0x6E00
    const val SW_END_OF_FILE = 0x6282

    private val exact: Map<Int, Pair<StatusCategory, String>> = mapOf(
        0x9000 to (StatusCategory.NORMAL to "Success: normal processing"),
        0x6200 to (StatusCategory.WARNING to "Warning: state of non-volatile memory unchanged, no information given"),
        0x6202 to (StatusCategory.WARNING to "Warning: triggering by the card"),
        0x6281 to (StatusCategory.WARNING to "Warning: part of returned data may be corrupted"),
        0x6282 to (StatusCategory.WARNING to "Warning: end of file or record reached before reading Ne bytes"),
        0x6283 to (StatusCategory.WARNING to "Warning: selected file deactivated"),
        0x6284 to (StatusCategory.WARNING to "Warning: file control information not formatted according to ISO/IEC 7816-4"),
        0x6285 to (StatusCategory.WARNING to "Warning: selected file in termination state"),
        0x6286 to (StatusCategory.WARNING to "Warning: no input data available from a sensor on the card"),
        0x6300 to (StatusCategory.WARNING to "Warning: state of non-volatile memory changed, no information given"),
        0x6381 to (StatusCategory.WARNING to "Warning: file filled up by the last write"),
        0x6400 to (StatusCategory.EXECUTION_ERROR to "Execution error: state of non-volatile memory unchanged"),
        0x6401 to (StatusCategory.EXECUTION_ERROR to "Execution error: immediate response required by the card"),
        0x6500 to (StatusCategory.EXECUTION_ERROR to "Execution error: state of non-volatile memory changed"),
        0x6581 to (StatusCategory.EXECUTION_ERROR to "Execution error: memory failure"),
        0x6600 to (StatusCategory.EXECUTION_ERROR to "Execution error: security-related issue"),
        0x6700 to (StatusCategory.CHECKING_ERROR to "Wrong length (Lc or Le field incorrect)"),
        0x6800 to (StatusCategory.CHECKING_ERROR to "Functions in CLA not supported"),
        0x6881 to (StatusCategory.CHECKING_ERROR to "Logical channel not supported"),
        0x6882 to (StatusCategory.CHECKING_ERROR to "Secure messaging not supported"),
        0x6883 to (StatusCategory.CHECKING_ERROR to "Last command of the chain expected"),
        0x6884 to (StatusCategory.CHECKING_ERROR to "Command chaining not supported"),
        0x6900 to (StatusCategory.CHECKING_ERROR to "Command not allowed"),
        0x6981 to (StatusCategory.CHECKING_ERROR to "Command incompatible with file structure"),
        0x6982 to (StatusCategory.CHECKING_ERROR to "Security status not satisfied (access requires authentication)"),
        0x6983 to (StatusCategory.CHECKING_ERROR to "Authentication method blocked"),
        0x6984 to (StatusCategory.CHECKING_ERROR to "Reference data not usable"),
        0x6985 to (StatusCategory.CHECKING_ERROR to "Conditions of use not satisfied"),
        0x6986 to (StatusCategory.CHECKING_ERROR to "Command not allowed (no current EF)"),
        0x6987 to (StatusCategory.CHECKING_ERROR to "Expected secure messaging data objects missing"),
        0x6988 to (StatusCategory.CHECKING_ERROR to "Incorrect secure messaging data objects"),
        0x6A00 to (StatusCategory.CHECKING_ERROR to "Wrong parameters P1-P2, no information given"),
        0x6A80 to (StatusCategory.CHECKING_ERROR to "Incorrect parameters in the command data field"),
        0x6A81 to (StatusCategory.CHECKING_ERROR to "Function not supported"),
        0x6A82 to (StatusCategory.CHECKING_ERROR to "File or application not found"),
        0x6A83 to (StatusCategory.CHECKING_ERROR to "Record not found"),
        0x6A84 to (StatusCategory.CHECKING_ERROR to "Not enough memory space in the file"),
        0x6A85 to (StatusCategory.CHECKING_ERROR to "Nc inconsistent with TLV structure"),
        0x6A86 to (StatusCategory.CHECKING_ERROR to "Incorrect parameters P1-P2"),
        0x6A87 to (StatusCategory.CHECKING_ERROR to "Nc inconsistent with parameters P1-P2"),
        0x6A88 to (StatusCategory.CHECKING_ERROR to "Referenced data or reference data not found"),
        0x6A89 to (StatusCategory.CHECKING_ERROR to "File already exists"),
        0x6A8A to (StatusCategory.CHECKING_ERROR to "DF name already exists"),
        0x6B00 to (StatusCategory.CHECKING_ERROR to "Wrong parameters P1-P2 (offset outside the EF)"),
        0x6D00 to (StatusCategory.CHECKING_ERROR to "Instruction code not supported or invalid"),
        0x6E00 to (StatusCategory.CHECKING_ERROR to "Class not supported"),
        0x6F00 to (StatusCategory.CHECKING_ERROR to "No precise diagnosis"),
    )

    fun interpret(sw: Int): StatusWordInfo {
        require(sw in 0..0xFFFF) { "Status word out of range" }
        exact[sw]?.let { (category, description) -> return StatusWordInfo(sw, category, description) }
        val sw1 = sw ushr 8
        val sw2 = sw and 0xFF
        return when {
            sw1 == 0x61 -> {
                val n = if (sw2 == 0) 256 else sw2
                StatusWordInfo(sw, StatusCategory.NORMAL, "Success: $n response bytes still available (use GET RESPONSE)", indicatedLength = n)
            }
            sw1 == 0x6C -> {
                val n = if (sw2 == 0) 256 else sw2
                StatusWordInfo(sw, StatusCategory.CHECKING_ERROR, "Wrong Le field: exact length is $n", indicatedLength = n)
            }
            sw1 == 0x63 && (sw2 and 0xF0) == 0xC0 -> {
                val x = sw2 and 0x0F
                StatusWordInfo(sw, StatusCategory.WARNING, "Warning: counter value $x (for verification commands: remaining tries)", counter = x)
            }
            sw1 == 0x62 -> StatusWordInfo(sw, StatusCategory.WARNING, "Warning: non-volatile memory unchanged (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x63 -> StatusWordInfo(sw, StatusCategory.WARNING, "Warning: non-volatile memory changed (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x64 -> StatusWordInfo(sw, StatusCategory.EXECUTION_ERROR, "Execution error: non-volatile memory unchanged (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x65 -> StatusWordInfo(sw, StatusCategory.EXECUTION_ERROR, "Execution error: non-volatile memory changed (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x66 -> StatusWordInfo(sw, StatusCategory.EXECUTION_ERROR, "Execution error: security-related issue (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x67 -> StatusWordInfo(sw, StatusCategory.CHECKING_ERROR, "Wrong length (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x68 -> StatusWordInfo(sw, StatusCategory.CHECKING_ERROR, "Functions in CLA not supported (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x69 -> StatusWordInfo(sw, StatusCategory.CHECKING_ERROR, "Command not allowed (SW2 ${Hex.byte(sw2)})")
            sw1 == 0x6A -> StatusWordInfo(sw, StatusCategory.CHECKING_ERROR, "Wrong parameters P1-P2 (SW2 ${Hex.byte(sw2)})")
            sw1 in 0x6B..0x6F -> StatusWordInfo(sw, StatusCategory.CHECKING_ERROR, "Checking error (SW1 ${Hex.byte(sw1)}, SW2 ${Hex.byte(sw2)})")
            sw1 in 0x90..0x9F -> StatusWordInfo(sw, StatusCategory.PROPRIETARY, "Proprietary status word (application-specific meaning)")
            sw1 in 0x60..0x6F -> StatusWordInfo(sw, StatusCategory.INVALID, "Invalid SW1 value (${Hex.byte(sw1)} is reserved for procedure bytes)")
            else -> StatusWordInfo(sw, StatusCategory.INVALID, "Not an ISO/IEC 7816-4 status word")
        }
    }
}
