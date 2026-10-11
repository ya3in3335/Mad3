package com.samtex.eidreader.testing

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException

/**
 * SIMULATED ISO/IEC 7816-4 card with a tiny file system, used to exercise the protocol
 * adapters without hardware. It implements only SELECT (by AID / EF), READ BINARY and
 * returns 6D00 for everything else.
 */
class VirtualIsoCard(
    private val files: Map<Int, ByteArray> = mapOf(0x011C to Fixtures.EF_CARD_ACCESS),
    private val applications: Set<String> = setOf("A0000002471001"),
    private val protectedFiles: Set<Int> = emptySet(),
    /** Throw TAG_LOST after this many commands (null = never). */
    private val removeAfterCommands: Int? = null,
) {
    private var selected: Int? = null
    var commandCount = 0
        private set

    fun respond(command: ByteArray): ByteArray {
        commandCount++
        removeAfterCommands?.let {
            if (commandCount > it) throw NfcEngineException(ErrorKind.TAG_LOST, "simulated removal")
        }
        val ins = command[1].toInt() and 0xFF
        val p1 = command[2].toInt() and 0xFF
        return when (ins) {
            0xA4 -> select(command, p1)
            0xB0 -> readBinary(command)
            else -> sw(0x6D00)
        }
    }

    private fun select(command: ByteArray, p1: Int): ByteArray {
        val lc = command[4].toInt() and 0xFF
        val data = command.copyOfRange(5, 5 + lc)
        return when (p1) {
            0x04 -> if (Hex.encode(data) in applications) sw(0x9000) else sw(0x6A82)
            0x00, 0x02 -> {
                val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                when {
                    fid == 0x3F00 -> { selected = null; sw(0x9000) }
                    fid in protectedFiles -> sw(0x6982)
                    fid in files -> { selected = fid; sw(0x9000) }
                    else -> sw(0x6A82)
                }
            }
            else -> sw(0x6A86)
        }
    }

    private fun readBinary(command: ByteArray): ByteArray {
        val fid = selected ?: return sw(0x6986)
        val content = files.getValue(fid)
        val offset = ((command[2].toInt() and 0x7F) shl 8) or (command[3].toInt() and 0xFF)
        val le = if (command.size == 5) (command[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 0
        if (offset > content.size) return sw(0x6B00)
        val end = minOf(content.size, offset + le)
        val chunk = content.copyOfRange(offset, end)
        return chunk + if (end - offset < le) byteArrayOf(0x62, 0x82.toByte()) else byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun sw(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
}
