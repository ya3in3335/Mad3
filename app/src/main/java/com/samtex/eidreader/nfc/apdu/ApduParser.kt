package com.samtex.eidreader.nfc.apdu

import com.samtex.eidreader.core.util.Hex

/** Human-readable decoding of command and response APDUs for the diagnostics UI and reports. */
object ApduParser {

    private val instructionNames = mapOf(
        0x04 to "DEACTIVATE FILE", 0x0E to "ERASE BINARY", 0x20 to "VERIFY", 0x21 to "VERIFY",
        0x22 to "MANAGE SECURITY ENVIRONMENT", 0x24 to "CHANGE REFERENCE DATA", 0x2A to "PERFORM SECURITY OPERATION",
        0x2C to "RESET RETRY COUNTER", 0x44 to "ACTIVATE FILE", 0x46 to "GENERATE ASYMMETRIC KEY PAIR",
        0x70 to "MANAGE CHANNEL", 0x82 to "EXTERNAL AUTHENTICATE", 0x84 to "GET CHALLENGE",
        0x86 to "GENERAL AUTHENTICATE", 0x87 to "GENERAL AUTHENTICATE", 0x88 to "INTERNAL AUTHENTICATE",
        0xA4 to "SELECT", 0xB0 to "READ BINARY", 0xB1 to "READ BINARY", 0xB2 to "READ RECORD", 0xB3 to "READ RECORD",
        0xC0 to "GET RESPONSE", 0xCA to "GET DATA", 0xCB to "GET DATA", 0xD0 to "WRITE BINARY",
        0xD2 to "WRITE RECORD", 0xD6 to "UPDATE BINARY", 0xDA to "PUT DATA", 0xDB to "PUT DATA",
        0xDC to "UPDATE RECORD", 0xE0 to "CREATE FILE", 0xE2 to "APPEND RECORD", 0xE4 to "DELETE FILE",
        0xE6 to "TERMINATE DF", 0xE8 to "TERMINATE EF", 0xFE to "TERMINATE CARD USAGE",
    )

    fun instructionName(ins: Int): String = instructionNames[ins] ?: "Unknown instruction (${Hex.byte(ins)})"

    data class CommandDescription(
        val hex: String,
        val instruction: String,
        val case: ApduCase,
        val header: String,
        val lc: Int,
        val ne: Int?,
        val dataHex: String,
        val notes: List<String>,
    )

    data class ResponseDescription(
        val hex: String,
        val dataLength: Int,
        val dataHex: String,
        val ascii: String?,
        val status: StatusWordInfo,
        val tlv: String?,
    )

    fun describe(command: CommandApdu): CommandDescription {
        val notes = mutableListOf<String>()
        if (command.usesSecureMessaging) notes += "CLA indicates secure messaging"
        if ((command.cla and 0x80) != 0) notes += "Proprietary class byte"
        if (command.ins == 0xA4) notes += describeSelect(command)
        if (command.ins == 0xB0 && (command.p1 and 0x80) != 0) {
            notes += "Short EF identifier ${command.p1 and 0x1F}, offset ${command.p2}"
        } else if (command.ins == 0xB0) {
            notes += "Offset ${(command.p1 shl 8) or command.p2}"
        }
        return CommandDescription(
            hex = command.toHex(),
            instruction = instructionName(command.ins),
            case = command.case,
            header = Hex.encode(byteArrayOf(command.cla.toByte(), command.ins.toByte(), command.p1.toByte(), command.p2.toByte()), " "),
            lc = command.nc,
            ne = command.ne,
            dataHex = Hex.encode(command.data, " "),
            notes = notes,
        )
    }

    fun describe(response: ResponseApdu): ResponseDescription {
        val data = response.data
        return ResponseDescription(
            hex = response.toHex(),
            dataLength = data.size,
            dataHex = Hex.encode(data, " "),
            ascii = if (data.isNotEmpty() && Hex.isPrintableAscii(data)) Hex.toPrintableAscii(data) else null,
            status = StatusWords.interpret(response.sw),
            tlv = BerTlv.parseOrNull(data)?.let { BerTlv.render(it).trimEnd() },
        )
    }

    private fun describeSelect(command: CommandApdu): String {
        val method = when (command.p1) {
            0x00 -> "MF, DF or EF by file identifier"
            0x01 -> "child DF by file identifier"
            0x02 -> "EF under current DF by file identifier"
            0x03 -> "parent DF"
            0x04 -> "by DF name (AID)"
            0x08 -> "by path from MF"
            0x09 -> "by path from current DF"
            else -> "unknown method"
        }
        val response = when (command.p2 and 0x0C) {
            0x00 -> "return FCI"
            0x04 -> "return FCP"
            0x08 -> "return FMD"
            else -> "no response data"
        }
        return "SELECT $method; $response"
    }
}
