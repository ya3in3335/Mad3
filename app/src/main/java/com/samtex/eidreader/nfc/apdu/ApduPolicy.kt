package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

/**
 * Authorization policy for the diagnostics console.
 *
 * Only documented, read-only interindustry commands without secure messaging are allowed.
 * Everything related to authentication, PIN handling, key management, secure messaging,
 * file modification or lifecycle changes is refused with an explicit reason. Unknown
 * instructions are refused by default (allowlist, not denylist).
 */
object ApduPolicy {

    data class Decision(val allowed: Boolean, val reason: String)

    private val allowedInstructions: Map<Int, String> = mapOf(
        0xA4 to "SELECT (ISO/IEC 7816-4)",
        0xB0 to "READ BINARY (ISO/IEC 7816-4)",
        0xB1 to "READ BINARY, odd INS (ISO/IEC 7816-4)",
        0xB2 to "READ RECORD (ISO/IEC 7816-4)",
        0xCA to "GET DATA (ISO/IEC 7816-4)",
        0xCB to "GET DATA, odd INS (ISO/IEC 7816-4)",
        0xC0 to "GET RESPONSE (ISO/IEC 7816-4)",
    )

    private val deniedInstructions: Map<Int, String> = mapOf(
        0x20 to "VERIFY (PIN/password verification) is never sent: no PIN attempts",
        0x21 to "VERIFY is never sent: no PIN attempts",
        0x24 to "CHANGE REFERENCE DATA modifies credentials",
        0x2C to "RESET RETRY COUNTER modifies credentials",
        0x22 to "MANAGE SECURITY ENVIRONMENT belongs to authentication protocols",
        0x2A to "PERFORM SECURITY OPERATION uses card keys",
        0x82 to "EXTERNAL/MUTUAL AUTHENTICATE is an authentication command",
        0x84 to "GET CHALLENGE is only meaningful for authentication protocols",
        0x86 to "GENERAL AUTHENTICATE (PACE/CA/TA) is an authentication command",
        0x87 to "GENERAL AUTHENTICATE (PACE/CA/TA) is an authentication command",
        0x88 to "INTERNAL AUTHENTICATE uses card keys",
        0x0E to "ERASE BINARY modifies card content",
        0xD0 to "WRITE BINARY modifies card content",
        0xD6 to "UPDATE BINARY modifies card content",
        0xD2 to "WRITE RECORD modifies card content",
        0xDC to "UPDATE RECORD modifies card content",
        0xE2 to "APPEND RECORD modifies card content",
        0xDA to "PUT DATA modifies card content",
        0xDB to "PUT DATA modifies card content",
        0xE0 to "CREATE FILE modifies card structure",
        0xE4 to "DELETE FILE modifies card structure",
        0x44 to "ACTIVATE FILE changes lifecycle state",
        0x04 to "DEACTIVATE FILE changes lifecycle state",
        0xE6 to "TERMINATE DF changes lifecycle state",
        0xE8 to "TERMINATE EF changes lifecycle state",
        0xFE to "TERMINATE CARD USAGE changes lifecycle state",
        0x70 to "MANAGE CHANNEL is not needed for diagnostics",
        0x46 to "GENERATE ASYMMETRIC KEY PAIR modifies card content",
    )

    fun evaluate(command: CommandApdu): Decision {
        if ((command.cla and 0x80) != 0) {
            return Decision(false, "Proprietary class (CLA ${Hex.byte(command.cla)}) is not documented for this card")
        }
        if (command.usesSecureMessaging) {
            return Decision(false, "Secure messaging (CLA ${Hex.byte(command.cla)}) is not supported by SAMTEX")
        }
        if ((command.cla and 0x10) != 0) {
            return Decision(false, "Command chaining (CLA bit b5) is not permitted in diagnostics")
        }
        if ((command.cla and 0x03) != 0 || (command.cla and 0x40) != 0) {
            return Decision(false, "Only the basic logical channel 0 is permitted")
        }
        deniedInstructions[command.ins]?.let { return Decision(false, it) }
        val description = allowedInstructions[command.ins]
            ?: return Decision(false, "INS ${Hex.byte(command.ins)} is not in the documented read-only allowlist")
        if (command.ins == 0xA4 && command.p1 !in setOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x08, 0x09)) {
            return Decision(false, "SELECT with P1=${Hex.byte(command.p1)} is not a documented selection method")
        }
        return Decision(true, "Allowed: $description")
    }
}
